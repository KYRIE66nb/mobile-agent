package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.tools.notifications.AgentNotificationListenerService

class NotificationToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext

    override val id = "notifications"
    override val title = "通知"
    override val description = "读取、打开、执行操作或清除系统通知；验证码和账户安全通知会隐藏内容并禁止自动操作。"
    override val definitions = listOf(
        ToolDefinition(
            "notifications_list",
            "读取通知",
            "读取当前通知及可用操作。敏感通知只返回隐藏标记，不返回验证码等内容。后续操作必须原样使用本次返回的 key 和 action_index。",
            """{"type":"object","properties":{"package_name":{"type":"string","maxLength":255},"limit":{"type":"integer","minimum":1,"maximum":100,"default":50}},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = "读取当前系统通知。",
        ),
        ToolDefinition(
            "notifications_open",
            "打开通知",
            "打开 notifications_list 返回的指定通知。",
            """{"type":"object","properties":{"key":{"type":"string","maxLength":1000}},"required":["key"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "打开一条系统通知。",
        ),
        ToolDefinition(
            "notifications_action",
            "执行通知按钮",
            "执行 notifications_list 返回的通知按钮。验证码或账户安全通知不允许自动执行。",
            """{"type":"object","properties":{"key":{"type":"string","maxLength":1000},"action_index":{"type":"integer","minimum":0,"maximum":20}},"required":["key","action_index"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "执行一条通知中的操作按钮。",
        ),
        ToolDefinition(
            "notifications_dismiss",
            "清除通知",
            "清除 notifications_list 返回且允许清除的通知。验证码或账户安全通知不允许自动清除。",
            """{"type":"object","properties":{"key":{"type":"string","maxLength":1000}},"required":["key"],"additionalProperties":false}""",
            ToolSideEffect.DESTRUCTIVE,
            id,
            approvalDescription = "清除一条系统通知。",
        ),
        ToolDefinition(
            "notifications_open_settings",
            "打开通知访问设置",
            "打开系统的通知使用权设置页，必须由用户亲自启用或关闭。",
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "打开系统通知使用权设置页。",
        ),
    )

    override suspend fun availability(): ToolAvailability =
        if (AgentNotificationListenerService.connected == null) {
            ToolAvailability(ToolAvailabilityState.DEGRADED, "尚未授予通知使用权；可以先打开系统设置")
        } else {
            ToolAvailability(ToolAvailabilityState.AVAILABLE)
        }

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        if (call.toolId == "notifications_open_settings") {
            appContext.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@toolResult ToolResult("""{"opened":true}""", "已打开通知使用权设置")
        }
        val service = checkNotNull(AgentNotificationListenerService.connected) {
            "尚未授予通知使用权，请先打开通知访问设置"
        }
        when (call.toolId) {
            "notifications_list" -> {
                val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 100)
                val matches = service.snapshots().filter { packageName == null || it.packageName == packageName }
                val returned = matches.take(limit)
                ToolResult(buildJsonObject {
                    put("total", matches.size)
                    put("returned", returned.size)
                    put("truncated", returned.size < matches.size)
                    putJsonArray("notifications") { returned.forEach { item -> add(buildJsonObject {
                        put("key", item.key)
                        put("package_name", item.packageName)
                        put("app_name", item.appName)
                        put("post_time", item.postTime)
                        put("category", item.category)
                        put("title", item.title)
                        put("text", item.text)
                        put("sub_text", item.subText)
                        put("ongoing", item.ongoing)
                        put("clearable", item.clearable)
                        put("sensitive", item.sensitive)
                        putJsonArray("actions") { item.actions.forEachIndexed { index, title -> add(buildJsonObject {
                            put("action_index", index)
                            put("title", title)
                        }) } }
                    }) } }
                }.toString(), "已读取 ${returned.size} 条系统通知")
            }
            "notifications_open" -> {
                service.open(required(args, "key"))
                ToolResult("""{"opened":true}""", "已打开通知")
            }
            "notifications_action" -> {
                service.invokeAction(required(args, "key"), int(args, "action_index"))
                ToolResult("""{"performed":true}""", "已执行通知操作")
            }
            "notifications_dismiss" -> {
                service.dismiss(required(args, "key"))
                ToolResult("""{"dismissed":true}""", "已清除通知")
            }
            else -> error("通知工具不支持 ${call.toolId}")
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = when (call.toolId) {
        "notifications_list" -> "读取当前通知"
        "notifications_open" -> "打开指定通知"
        "notifications_action" -> "执行指定通知按钮"
        "notifications_dismiss" -> "清除指定通知"
        "notifications_open_settings" -> "打开通知访问设置"
        else -> null
    }

    private fun required(args: JsonObject, name: String) =
        args[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: error("缺少参数 $name")
    private fun int(args: JsonObject, name: String) =
        args[name]?.jsonPrimitive?.intOrNull ?: error("缺少参数 $name")
}
