package xyz.chouxuewei.mobile_agent.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 导航快路径用的瞬态观察契约（不落库、不含截图）：
 * device_observe 的 ToolResult.ephemeral 携带该 JSON，结构固定——
 * {observation_id, session_id, revision, package, viewport:{w,h},
 *  nodes:[{id,text,desc,bounds:[l,t,r,b],actions:[...],editable,enabled}]}
 * 只接受这里列出的字段；缺关键字段视为契约违例。
 */
data class ObservationLite(
    val observationId: String,
    val sessionId: String,
    val revision: Long,
    val foregroundPackage: String?,
    val viewportW: Int,
    val viewportH: Int,
    val nodes: List<NodeLite>,
)

data class NodeLite(
    val id: String,
    val text: String?,
    val description: String?,
    val bounds: List<Int>,
    val actions: Set<String>,
    val editable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
) {
    val label: String get() = listOfNotNull(text, description).firstOrNull { it.isNotBlank() } ?: id
}

object ObservationLiteCodec {
    fun parse(json: JsonObject): ObservationLite? {
        val observationId = json["observation_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionId = json["session_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val viewport = json["viewport"]?.jsonObject
        val w = viewport?.get("w")?.jsonPrimitive?.intOrNull ?: 0
        val h = viewport?.get("h")?.jsonPrimitive?.intOrNull ?: 0
        val nodes = json["nodes"]?.jsonArray?.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NodeLite(
                id = id,
                text = o["text"]?.jsonPrimitive?.contentOrNull?.take(DecisionLimits.MAX_NODE_TEXT_CHARS),
                description = o["desc"]?.jsonPrimitive?.contentOrNull?.take(DecisionLimits.MAX_NODE_TEXT_CHARS),
                bounds = o["bounds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }?.take(4) ?: emptyList(),
                actions = o["actions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                    ?: emptySet(),
                editable = o["editable"]?.jsonPrimitive?.contentOrNull == "true" ||
                    o["editable"]?.jsonPrimitive?.booleanOrNull == true,
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull != false,
                visible = o["visible"]?.jsonPrimitive?.booleanOrNull != false,
            )
        }?.take(DecisionLimits.MAX_NAV_NODES) ?: emptyList()
        return ObservationLite(
            observationId = observationId,
            sessionId = sessionId,
            revision = json["revision"]?.jsonPrimitive?.longOrNull ?: 0L,
            foregroundPackage = json["package"]?.jsonPrimitive?.contentOrNull,
            viewportW = w,
            viewportH = h,
            nodes = nodes,
        )
    }
}

/** 本地构造的候选动作；服务只能从中选择一个 id，不能发明动作或参数。 */
data class ActionCandidate(
    val id: String,
    /** device_action 的 action 枚举值。 */
    val action: String,
    /** device_action 参数（含 observation_id；session_id 由执行方注入）。 */
    val arguments: JsonObject,
    /** 评分依据用的简短描述。 */
    val rubric: String,
    /** 期望执行后前台包名不变；open_app 类候选可指定目标包名。 */
    val expectedPackage: String? = null,
)

object ActionCandidateBuilder {

    /** 从瞬态观察构造有界候选集：只允许节点语义动作，坐标/文本输入不在快路径内。 */
    fun build(observation: ObservationLite): List<ActionCandidate> {
        val candidates = mutableListOf<ActionCandidate>()
        var index = 0
        for (node in observation.nodes) {
            if (candidates.size >= DecisionLimits.MAX_NAV_CANDIDATES) break
            if (!node.enabled || !node.visible) continue
            val label = node.label.take(60)
            when {
                "click" in node.actions -> {
                    candidates += candidate(++index, observation, node, "click_node",
                        localizedText("点击「$label」", "Tap \"$label\""))
                }
                "long_click" in node.actions -> {
                    candidates += candidate(++index, observation, node, "long_click_node",
                        localizedText("长按「$label」", "Long-press \"$label\""))
                }
                "scroll_forward" in node.actions || "scroll_down" in node.actions -> {
                    candidates += candidate(++index, observation, node, "scroll_node",
                        localizedText("向下滚动「$label」", "Scroll \"$label\" down"),
                        extra = { put("scroll_direction", "forward") })
                }
            }
        }
        return candidates
    }

    private fun candidate(
        index: Int,
        observation: ObservationLite,
        node: NodeLite,
        action: String,
        rubric: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = ActionCandidate(
        id = "c$index",
        action = action,
        arguments = buildJsonObject {
            put("observation_id", observation.observationId)
            put("action", action)
            put("node_ref", node.id)
            extra()
        },
        rubric = rubric,
        expectedPackage = observation.foregroundPackage,
    )
}

/**
 * 从用户原始请求中提取硬性约束短语（"不要发送""先别修改""只打开""取消"等）。
 * 约束随请求传给决策服务并在本地下限再次校验：候选动作与禁止类约束冲突时不得采纳。
 */
fun userConstraints(text: String): List<String> {
    val normalized = text.lowercase()
    val constraints = mutableListOf<String>()
    CONSTRAINT_PATTERNS.forEach { (pattern, label) ->
        if (pattern.containsMatchIn(normalized) || pattern.containsMatchIn(text)) constraints += label
    }
    return constraints.take(8)
}

private val CONSTRAINT_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("不要?发送|别发送|don'?t send|do not send") to "forbid_send",
    Regex("不要?修改|先别修改|别改|don'?t (modify|change|edit)|do not (modify|change|edit)") to "forbid_modify",
    Regex("只(打开|看看|查看)|仅(打开|查看)|just open|only open|view only") to "view_only",
    Regex("取消|不要执行|先停下|abort|cancel") to "cancel_like",
    Regex("不要?点击|don'?t (click|tap)|do not (click|tap)") to "forbid_click",
    Regex("不要?删除|别删|don'?t delete|do not delete") to "forbid_delete",
)

/** 导航选择请求构建器：目标 + 有界观察摘要 + 用户约束 + 候选集 + 两个保留选项。 */
fun navigationDecisionRequest(
    goal: String,
    observation: ObservationLite,
    candidates: List<ActionCandidate>,
    constraints: List<String>,
    requestId: String,
    keyGeneration: Int,
): DecisionChoiceRequest {
    val state = buildJsonObject {
        put("goal", goal.take(DecisionLimits.MAX_GOAL_CHARS))
        put("foreground_package", observation.foregroundPackage ?: "")
        putJsonObject("viewport") {
            put("w", observation.viewportW)
            put("h", observation.viewportH)
        }
        if (constraints.isNotEmpty()) {
            putJsonArray("user_constraints") {
                constraints.forEach { add(it) }
            }
        }
        putJsonArray("nodes") {
            observation.nodes.forEach { node ->
                add(buildJsonObject {
                    put("id", node.id)
                    node.text?.let { put("text", it.take(DecisionLimits.MAX_NODE_TEXT_CHARS)) }
                    node.description?.let { put("desc", it.take(DecisionLimits.MAX_NODE_TEXT_CHARS)) }
                })
            }
        }
    }
    val options = buildMap {
        candidates.forEach { put(it.id, it.rubric.take(DecisionLimits.MAX_OPTION_DESC_CHARS)) }
        put(DecisionPolicy.OPTION_GOAL_MET, localizedText("导航目标在当前屏幕已经达成，无需再执行动作", "The navigation goal is already satisfied on this screen"))
        put(DecisionPolicy.OPTION_NONE, localizedText("没有候选能推进目标，应回退给主模型规划", "No candidate advances the goal; fall back to the main planner"))
    }
    return DecisionChoiceRequest(
        purpose = DecisionPurpose.NAVIGATION,
        requestId = requestId,
        state = state,
        instructions = localizedText(
            "用户界面导航目标见 state.goal。从候选中选择一个最能推进目标的动作；若目标已达成选 goal_met，若无合适候选选 none。候选之外的动作不可选择。state.user_constraints 列出用户的硬性约束，任何违反约束的选择一律选 none。",
            "The UI navigation goal is state.goal. Pick the candidate that best advances it; choose goal_met if it is already satisfied, or none if no candidate applies. Do not invent actions outside the candidates. state.user_constraints lists the user's hard constraints; any choice that would violate them must be none.",
        ),
        options = options,
        keyGeneration = keyGeneration,
    )
}

/**
 * 本地约束下限：候选天然只是导航类节点动作（无输入文本、无发送、无删除），
 * 因此"不要发送/先别修改/只打开"由动作空间本身保证不可违反；
 * 只有与快路径动作面直接冲突的约束（取消类、禁点类）在本地直接判不可采纳。
 */
fun constraintsPermitAutoAction(constraints: List<String>): Boolean =
    constraints.none { it == "cancel_like" || it == "forbid_click" }
