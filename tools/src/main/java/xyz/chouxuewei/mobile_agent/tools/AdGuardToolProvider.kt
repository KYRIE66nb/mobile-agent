package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.AdGuardAction
import xyz.chouxuewei.mobile_agent.core.AdGuardController
import xyz.chouxuewei.mobile_agent.core.AdGuardRule
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolAvailability
import xyz.chouxuewei.mobile_agent.core.ToolAvailabilityState
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect
import java.util.UUID

/**
 * 广告守卫工具：模型不负责拦截本身（广告只闪现几秒，模型太慢），
 * 而是读取守卫状态、按用户描述增删确定性规则——例如"这个 App 摇一摇就跳广告"
 * 对应一条 auto_back 规则，"老弹红包弹窗"对应一条限定包名的 click_text 规则。
 */
class AdGuardToolProvider(private val controller: AdGuardController) : ToolProvider {
    override val id = "ad_guard"
    override val title get() = localizedText("广告守卫", "Ad guard")
    override val description get() = localizedText("自动跳过开屏广告、关闭带“广告”标记的弹窗、拦截摇一摇跳转；由 Agent 按用户描述配置规则。", "Automatically skip splash ads, close popups marked as ads, and cancel shake-triggered jumps; rules are configured by the agent from user descriptions.")
    override val definitions get() = listOf(
        ToolDefinition(
            "adguard_status",
            localizedText("读取广告守卫状态", "Read ad guard status"),
            localizedText("返回守卫开关、无障碍服务连接状态、规则数量和最近的拦截记录。用户反馈广告问题前先调用它确认守卫已开启且无障碍服务已连接。", "Report the guard switch, accessibility connection, rule count, and recent blocks. Call this first when the user reports ad problems to confirm the guard is on and the accessibility service is connected."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取广告守卫状态。", "Read the ad guard status."),
        ),
        ToolDefinition(
            "adguard_set",
            localizedText("开关广告守卫", "Toggle ad guard"),
            localizedText("开启或关闭广告守卫与拦截提示。开启需要无障碍服务已连接；未连接时用 system_open_panel 打开 accessibility 面板请用户先开启。", "Enable or disable the ad guard and its block toasts. Enabling requires the accessibility service; when disconnected, open the accessibility system panel for the user first."),
            localizedJsonSchema("""{"type":"object","properties":{"enabled":{"type":"boolean","description":localizedText("可选，守卫总开关", "Optional master switch")},"show_toast":{"type":"boolean","description":localizedText("可选，拦截成功时是否弹出提示", "Optional: show a toast after each block")}},"additionalProperties":false}"""),
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("修改广告守卫开关。", "Change ad guard switches."),
        ),
        ToolDefinition(
            "adguard_add_rule",
            localizedText("添加广告拦截规则", "Add an ad-blocking rule"),
            localizedText("新增一条确定性拦截规则。click_text：点击文字/描述命中 match_texts 的可点击节点，context_texts 全部出现在界面时才触发，适合弹窗关闭按钮；auto_back：前台离开 package_name 指定的应用时按返回键撤销跳转，class_pattern 可限定目标 Activity 类名正则（如 (?i)(ad|splash|landing)），适合摇一摇广告跳转。package_name 缺省表示对所有应用生效。", "Add a deterministic blocking rule. click_text taps a clickable node whose text or description matches match_texts and only when all context_texts appear, ideal for popup close buttons; auto_back presses back when the foreground leaves the app given by package_name, with class_pattern optionally restricting the target activity regex (for example (?i)(ad|splash|landing)), ideal for shake-ad jumps. Omitting package_name applies the rule to all apps."),
            localizedJsonSchema("""{"type":"object","properties":{"name":{"type":"string","maxLength":40,"description":localizedText("规则名称，如“跳过拼多多开屏广告”", "Rule name such as \"Skip Pinduoduo splash ads\"")},"action":{"type":"string","enum":["click_text","auto_back"],"description":localizedText("click_text 点击命中节点；auto_back 离开应用时按返回", "click_text taps a matching node; auto_back presses back on app leave")},"package_name":{"type":"string","maxLength":255,"description":localizedText("可选，规则生效的应用包名；auto_back 必填", "Optional app package scope; required for auto_back")},"match_texts":{"type":"array","items":{"type":"string","maxLength":30},"maxItems":8,"description":localizedText("click_text 必填，命中文本或描述，如 [\"跳过\",\"×\"]", "Required for click_text: matching texts or descriptions, e.g. [\"跳过\",\"×\"]")},"context_texts":{"type":"array","items":{"type":"string","maxLength":30},"maxItems":6,"description":localizedText("可选，全部出现在界面上才点击，如 [\"广告\"]", "Optional: all must appear on screen before clicking, e.g. [\"广告\"]")},"class_pattern":{"type":"string","maxLength":200,"description":localizedText("auto_back 可选，目标 Activity 类名正则", "Optional auto_back: target activity class regex")}},"required":["name","action"],"additionalProperties":false}"""),
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("添加一条广告拦截规则。", "Add an ad-blocking rule."),
        ),
        ToolDefinition(
            "adguard_remove_rule",
            localizedText("删除广告拦截规则", "Remove an ad-blocking rule"),
            localizedText("按 rule_id 删除一条规则，可用 adguard_status 查看现有规则 ID。", "Remove a rule by rule_id; list existing rules with adguard_status."),
            """{"type":"object","properties":{"rule_id":{"type":"string","maxLength":64}},"required":["rule_id"],"additionalProperties":false}""",
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("删除一条广告拦截规则。", "Remove an ad-blocking rule."),
        ),
        ToolDefinition(
            "adguard_set_rule",
            localizedText("启用或停用单条规则", "Enable or disable a rule"),
            localizedText("按 rule_id 启用或停用一条规则，不删除配置。", "Enable or disable a rule by rule_id without deleting it."),
            """{"type":"object","properties":{"rule_id":{"type":"string","maxLength":64},"enabled":{"type":"boolean"}},"required":["rule_id","enabled"],"additionalProperties":false}""",
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("启用或停用一条规则。", "Enable or disable a rule."),
        ),
    )

    override suspend fun availability(): ToolAvailability = ToolAvailability(
        if (controller.accessibilityConnected()) ToolAvailabilityState.AVAILABLE else ToolAvailabilityState.DEGRADED,
        if (controller.accessibilityConnected()) "" else localizedText("无障碍服务未连接，规则配置可用但拦截不会生效", "The accessibility service is disconnected; rules can be configured but nothing will be blocked."),
    )

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "adguard_status" -> status()
            "adguard_set" -> set(args)
            "adguard_add_rule" -> addRule(args)
            "adguard_remove_rule" -> removeRule(args)
            "adguard_set_rule" -> setRule(args)
            else -> error(localizedText("广告守卫不支持 ${call.toolId}", "Ad guard does not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "adguard_status" -> localizedText("读取广告守卫状态", "Read ad guard status")
            "adguard_set" -> localizedText("修改广告守卫开关", "Change ad guard switches")
            "adguard_add_rule" -> localizedText("新增规则：", "Add rule: ") +
                (args["name"]?.jsonPrimitive?.contentOrNull ?: "")
            "adguard_remove_rule" -> localizedText("删除规则", "Remove a rule")
            "adguard_set_rule" -> localizedText("启用/停用规则", "Enable/disable a rule")
            else -> null
        }
    }.getOrNull()

    private fun status(): ToolResult {
        val snapshot = controller.state.value
        return ToolResult(buildJsonObject {
            put("enabled", snapshot.enabled)
            put("show_toast", snapshot.showToast)
            put("accessibility_connected", controller.accessibilityConnected())
            put("rule_count", snapshot.rules.size)
            put("total_blocked", snapshot.totalBlocked)
            putJsonArray("rules") {
                snapshot.rules.forEach { rule ->
                    add(buildJsonObject {
                        put("id", rule.id)
                        put("name", rule.name)
                        put("enabled", rule.enabled)
                        put("action", rule.action.name.lowercase())
                        rule.packageScope?.let { put("package_name", it) }
                        putJsonArray("match_texts") { rule.matchTexts.forEach { add(it) } }
                        putJsonArray("context_texts") { rule.contextTexts.forEach { add(it) } }
                        rule.classPattern?.let { put("class_pattern", it) }
                    })
                }
            }
            putJsonArray("recent_events") {
                snapshot.events.take(10).forEach { event ->
                    add(buildJsonObject {
                        put("rule", event.ruleName)
                        event.packageName?.let { put("package_name", it) }
                        put("detail", event.detail)
                        put("at", event.atEpochMillis)
                    })
                }
            }
        }.toString(), localizedText("已读取广告守卫状态", "Ad guard status read"))
    }

    private suspend fun set(args: JsonObject): ToolResult {
        args["enabled"]?.jsonPrimitive?.booleanOrNull?.let { controller.setEnabled(it) }
        args["show_toast"]?.jsonPrimitive?.booleanOrNull?.let { controller.setShowToast(it) }
        val snapshot = controller.state.value
        return ToolResult(buildJsonObject {
            put("enabled", snapshot.enabled)
            put("show_toast", snapshot.showToast)
            put("accessibility_connected", controller.accessibilityConnected())
        }.toString(), localizedText("广告守卫已${if (snapshot.enabled) "开启" else "关闭"}",
            "Ad guard ${if (snapshot.enabled) "enabled" else "disabled"}"))
    }

    private suspend fun addRule(args: JsonObject): ToolResult {
        val action = when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "auto_back" -> AdGuardAction.AUTO_BACK
            else -> AdGuardAction.CLICK_TEXT
        }
        val rule = AdGuardRule(
            id = UUID.randomUUID().toString().take(8),
            name = args["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error(localizedText("缺少参数 name", "Missing parameter: name")),
            packageScope = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            action = action,
            matchTexts = args.stringList("match_texts"),
            contextTexts = args.stringList("context_texts"),
            classPattern = args["class_pattern"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
        )
        controller.addRule(rule)
        return ToolResult(buildJsonObject {
            put("rule_id", rule.id)
            put("rules_total", controller.state.value.rules.size)
            put("guard_enabled", controller.state.value.enabled)
            put("accessibility_connected", controller.accessibilityConnected())
        }.toString(), localizedText("已添加规则「${rule.name}」", "Rule \"${rule.name}\" added"))
    }

    private suspend fun removeRule(args: JsonObject): ToolResult {
        val id = args["rule_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 rule_id", "Missing parameter: rule_id"))
        controller.removeRule(id)
        return ToolResult("""{"removed":true,"rule_id":"$id"}""", localizedText("已删除规则", "Rule removed"))
    }

    private suspend fun setRule(args: JsonObject): ToolResult {
        val id = args["rule_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 rule_id", "Missing parameter: rule_id"))
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: error(localizedText("缺少参数 enabled", "Missing parameter: enabled"))
        controller.setRuleEnabled(id, enabled)
        return ToolResult("""{"rule_id":"$id","enabled":$enabled}""",
            localizedText("规则已${if (enabled) "启用" else "停用"}", "Rule ${if (enabled) "enabled" else "disabled"}"))
    }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
}
