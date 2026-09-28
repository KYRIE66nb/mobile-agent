package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.NotificationMatch
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect
import xyz.chouxuewei.mobile_agent.core.TriggerController
import xyz.chouxuewei.mobile_agent.core.TriggerEngine
import xyz.chouxuewei.mobile_agent.core.TriggerKind
import xyz.chouxuewei.mobile_agent.core.TriggerRepeat
import xyz.chouxuewei.mobile_agent.core.TriggerSpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 触发器管理：模型帮用户创建定时/通知/周期任务。
 * tool_scope 是任务唯一防线——默认只读范围，外部副作用工具必须用户明确要求才加入；
 * 创建/更新走审批通道，scope 摘要会如实展示给用户。
 */
class TriggerToolProvider(
    private val controller: TriggerController,
    private val knownToolIds: () -> Set<String>,
) : ToolProvider {
    override val id = "trigger"
    override val title get() = localizedText("定时任务", "Scheduled tasks")
    override val description get() = localizedText(
        "创建定时、通知匹配、周期巡检任务；任务在后台无人值守执行，只能在授权的工具范围内行动，结果以通知交付。",
        "Create scheduled, notification-matched, or interval tasks; they run unattended in the background within an authorized tool scope and deliver results via notifications.",
    )
    override val definitions get() = listOf(
        ToolDefinition(
            "trigger_list",
            localizedText("列出定时任务", "List scheduled tasks"),
            localizedText("列出全部触发器：ID、名称、类型、启用状态、下次触发时间、上次执行结果、工具范围。", "List all triggers: ID, name, kind, enabled state, next fire time, last result, tool scope."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取定时任务列表。", "List scheduled tasks."),
        ),
        ToolDefinition(
            "trigger_save",
            localizedText("保存定时任务", "Save a scheduled task"),
            localizedText(
                "创建或更新触发器（传 id 更新）。kind=schedule：一次性给 fire_in_minutes 或 fire_at_epoch_ms；重复给 repeat(daily/weekdays/weekly)+hour+minute(+weekdays，1=周日…7=周六)。kind=notification：给 match_package/match_title/match_text 正则，至少一个。kind=interval：给 interval_minutes(≥15)。instruction 是无人值守执行的任务指令，要写清目标与产出；tool_scope 省略时用默认只读范围，涉及发消息/清理/设备操作等外部动作必须用户明确要求才加入。时间、通知目标、范围不明确时先问用户，不要猜。",
                "Create or update a trigger (pass id to update). kind=schedule: one-shot needs fire_in_minutes or fire_at_epoch_ms; repeating needs repeat(daily/weekdays/weekly)+hour+minute(+weekdays, 1=Sunday…7=Saturday). kind=notification: at least one of match_package/match_title/match_text regexes. kind=interval: interval_minutes (>=15). instruction is the unattended task directive — state the goal and expected output clearly; omit tool_scope for the default read-only scope, and only add external-action tools (messaging/cleanup/device) when the user explicitly asks. Ask the user for missing time, notification target, or scope instead of guessing.",
            ),
            localizedJsonSchema("""{"type":"object","properties":{"id":{"type":"string","maxLength":64,"description":localizedText("更新时传入已有 ID；新建留空", "Existing ID to update; omit to create")},"name":{"type":"string","maxLength":40},"kind":{"type":"string","enum":["schedule","notification","interval"]},"instruction":{"type":"string","maxLength":2000,"description":localizedText("无人值守执行的任务指令", "The unattended task directive")},"fire_in_minutes":{"type":"integer","minimum":1,"maximum":43200,"description":localizedText("一次性：N 分钟后触发", "One-shot: fire in N minutes")},"fire_at_epoch_ms":{"type":"integer","description":localizedText("一次性：具体时间戳（毫秒）", "One-shot: absolute epoch milliseconds")},"repeat":{"type":"string","enum":["none","daily","weekdays","weekly"]},"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"weekdays":{"type":"array","items":{"type":"integer","minimum":1,"maximum":7},"maxItems":7,"description":localizedText("weekly 专用，1=周日…7=周六", "For weekly, 1=Sunday…7=Saturday")},"match_package":{"type":"string","maxLength":200},"match_title":{"type":"string","maxLength":200},"match_text":{"type":"string","maxLength":200},"interval_minutes":{"type":"integer","minimum":15,"maximum":43200},"tool_scope":{"type":"array","items":{"type":"string","maxLength":64},"maxItems":40,"description":localizedText("允许使用的工具 ID；省略为默认只读范围", "Allowed tool IDs; omit for the default read-only scope")},"cooldown_minutes":{"type":"integer","minimum":0,"maximum":1440,"default":5},"max_runs_per_day":{"type":"integer","minimum":1,"maximum":200,"default":12},"notify_user":{"type":"boolean","default":true},"enabled":{"type":"boolean","default":true}},"required":["name","kind","instruction"],"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("保存一个自动执行的定时任务；请核对它的工具范围。", "Save an automated task; review its tool scope."),
        ),
        ToolDefinition(
            "trigger_delete",
            localizedText("删除定时任务", "Delete a scheduled task"),
            localizedText("按 id 删除触发器并取消其排期。", "Delete a trigger by id and cancel its schedule."),
            """{"type":"object","properties":{"id":{"type":"string","maxLength":64}},"required":["id"],"additionalProperties":false}""",
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("删除一个定时任务。", "Delete a scheduled task."),
        ),
        ToolDefinition(
            "trigger_run_now",
            localizedText("立即执行定时任务", "Run a scheduled task now"),
            localizedText("按 id 立即执行一次触发器，与定时触发使用相同的工具范围与安全约束；最多等待 5 分钟并返回模型产出摘要。", "Run a trigger by id immediately under the same tool scope and safety constraints; waits up to 5 minutes and returns the model output summary."),
            """{"type":"object","properties":{"id":{"type":"string","maxLength":64}},"required":["id"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("立即执行一个定时任务。", "Run a scheduled task now."),
        ),
    )

    private val engine = TriggerEngine()
    /** SimpleDateFormat 非线程安全，只读工具可并行执行——每次调用现建。 */
    private fun formatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "trigger_list" -> list()
            "trigger_save" -> save(args)
            "trigger_delete" -> delete(args)
            "trigger_run_now" -> runNow(args)
            else -> error(localizedText("定时任务不支持 ${call.toolId}", "Triggers do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "trigger_save" -> localizedText(
                "保存任务「${args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()}」",
                "Save task \"${args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()}\"",
            ) + (args["tool_scope"] as? JsonArray)?.let { scope ->
                localizedText("，工具范围：", "; tool scope: ") +
                    scope.joinToString(",") { it.jsonPrimitive.contentOrNull.orEmpty() }
            }.orEmpty()
            "trigger_run_now" -> localizedText("立即执行任务", "Run task now")
            "trigger_delete" -> localizedText("删除定时任务", "Delete scheduled task")
            else -> null
        }
    }.getOrNull()

    private suspend fun list(): ToolResult {
        val specs = controller.specs.value
        return ToolResult(buildJsonObject {
            put("count", specs.size)
            putJsonArray("triggers") {
                specs.forEach { spec ->
                    add(buildJsonObject {
                        put("id", spec.id)
                        put("name", spec.name)
                        put("kind", spec.kind.name.lowercase())
                        put("enabled", spec.enabled)
                        engine.nextFireAt(spec)?.let { put("next_fire", formatTime(it)) }
                        spec.lastStatus?.let { put("last_status", it) }
                        spec.lastRunAt?.let { put("last_run", formatTime(it)) }
                        put("consecutive_failures", spec.consecutiveFailures)
                        put("cooldown_minutes", spec.cooldownMinutes)
                        put("max_runs_per_day", spec.maxRunsPerDay)
                        putJsonArray("tool_scope") { spec.toolScope.forEach { add(it) } }
                    })
                }
            }
        }.toString(), localizedText("已列出 ${specs.size} 个定时任务", "Listed ${specs.size} scheduled tasks"))
    }

    private suspend fun save(args: kotlinx.serialization.json.JsonObject): ToolResult {
        val kind = when (args["kind"]?.jsonPrimitive?.contentOrNull) {
            "schedule" -> TriggerKind.SCHEDULE
            "notification" -> TriggerKind.NOTIFICATION
            "interval" -> TriggerKind.INTERVAL
            else -> error(localizedText("kind 必须是 schedule/notification/interval", "kind must be schedule/notification/interval"))
        }
        val repeatArg = args["repeat"]?.jsonPrimitive?.contentOrNull
        val repeat = when (repeatArg) {
            "daily" -> TriggerRepeat.DAILY
            "weekdays" -> TriggerRepeat.WEEKDAYS
            "weekly" -> TriggerRepeat.WEEKLY
            "none" -> TriggerRepeat.NONE
            null -> null
            else -> error(localizedText("repeat 必须是 none/daily/weekdays/weekly", "repeat must be none/daily/weekdays/weekly"))
        }
        val hour = args["hour"]?.jsonPrimitive?.intOrNull
        val minute = args["minute"]?.jsonPrimitive?.intOrNull
        val now = System.currentTimeMillis()
        val fireAt = args["fire_in_minutes"]?.jsonPrimitive?.longOrNull?.let { now + it * 60_000L }
            ?: args["fire_at_epoch_ms"]?.jsonPrimitive?.longOrNull
        val hasMatchArg = listOf("match_package", "match_title", "match_text")
            .any { !args[it]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
        val match = if (kind == TriggerKind.NOTIFICATION && hasMatchArg) NotificationMatch(
            packagePattern = args["match_package"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            titlePattern = args["match_title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            textPattern = args["match_text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
        ) else null
        val scope = (args["tool_scope"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }?.toSet()
            ?: TriggerSpec.DEFAULT_SCOPE
        val unknown = scope - knownToolIds()
        require(unknown.isEmpty()) {
            localizedText("工具范围包含不存在的工具：${unknown.joinToString()}", "Tool scope contains unknown tools: ${unknown.joinToString()}")
        }
        val forbidden = scope.intersect(TriggerSpec.FORBIDDEN_SCOPE_TOOLS)
        require(forbidden.isEmpty()) {
            localizedText(
                "以下工具不允许进入触发器范围（会造成权限扩张或无人值守死锁）：${forbidden.joinToString()}",
                "These tools cannot enter a trigger scope (privilege expansion or unattended deadlock): ${forbidden.joinToString()}",
            )
        }
        val existing = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?.let { controller.spec(it) }
        val spec = TriggerSpec(
            id = existing?.id ?: newId(),
            name = args["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error(localizedText("缺少参数 name", "Missing parameter: name")),
            enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: existing?.enabled ?: true,
            kind = kind,
            scheduleAtEpochMs = fireAt ?: existing?.scheduleAtEpochMs,
            hour = hour ?: existing?.hour,
            minute = minute ?: existing?.minute,
            repeat = repeat ?: existing?.repeat ?: TriggerRepeat.NONE,
            weekdays = (args["weekdays"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet()
                ?: existing?.weekdays.orEmpty(),
            notification = match ?: existing?.notification,
            intervalMinutes = args["interval_minutes"]?.jsonPrimitive?.intOrNull
                ?: existing?.intervalMinutes ?: TriggerSpec.MIN_INTERVAL_MINUTES,
            instruction = args["instruction"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: existing?.instruction ?: error(localizedText("缺少参数 instruction", "Missing parameter: instruction")),
            toolScope = scope.takeIf { args.containsKey("tool_scope") } ?: existing?.toolScope ?: scope,
            cooldownMinutes = args["cooldown_minutes"]?.jsonPrimitive?.intOrNull ?: existing?.cooldownMinutes ?: 5,
            maxRunsPerDay = args["max_runs_per_day"]?.jsonPrimitive?.intOrNull ?: existing?.maxRunsPerDay ?: 12,
            notifyUser = args["notify_user"]?.jsonPrimitive?.booleanOrNull ?: existing?.notifyUser ?: true,
            conversationId = existing?.conversationId,
        )
        val saved = controller.upsert(spec)
        return ToolResult(buildJsonObject {
            put("id", saved.id)
            put("updated", existing != null)
            engine.nextFireAt(saved)?.let { put("next_fire", formatTime(it)) }
            put("scope_size", saved.toolScope.size)
        }.toString(), localizedText("已保存任务「${saved.name}」", "Task \"${saved.name}\" saved"))
    }

    private suspend fun delete(args: kotlinx.serialization.json.JsonObject): ToolResult {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 id", "Missing parameter: id"))
        controller.remove(id)
        return ToolResult("""{"deleted":true,"id":"$id"}""", localizedText("已删除定时任务", "Scheduled task deleted"))
    }

    private suspend fun runNow(args: kotlinx.serialization.json.JsonObject): ToolResult {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 id", "Missing parameter: id"))
        val summary = controller.runNow(id)
        return ToolResult(buildJsonObject {
            put("id", id)
            put("summary", summary.take(500))
        }.toString(), localizedText("任务已执行", "Task executed"))
    }

    private fun newId(): String = "trg_" + System.currentTimeMillis().toString(36) +
        "_" + (Calendar.getInstance().get(Calendar.MILLISECOND) % 997).toString(36)
}
