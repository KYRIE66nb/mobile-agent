package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.tools.notifications.AgentNotificationListenerService

class NotificationToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext

    override val id = "notifications"
    override val title get() = localizedText("通知", "Notifications")
    override val description get() = localizedText("读取、打开、执行操作或清除系统通知；验证码和账户安全通知会隐藏内容并禁止自动操作。", "Read, open, act on, or dismiss system notifications. Verification-code and account-security notifications hide content and cannot be automated.")
    override val definitions get() = listOf(
        ToolDefinition(
            "notifications_list",
            localizedText("读取通知", "Read notifications"),
            localizedText("读取当前通知及可用操作。敏感通知只返回隐藏标记，不返回验证码等内容。后续操作必须原样使用本次返回的 key 和 action_index。", "Read current notifications and available actions. Sensitive notifications return only a hidden marker, never verification codes. Later actions must copy the returned key and action_index exactly."),
            """{"type":"object","properties":{"package_name":{"type":"string","maxLength":255},"limit":{"type":"integer","minimum":1,"maximum":100,"default":50}},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取当前系统通知。", "Read current system notifications."),
        ),
        ToolDefinition(
            "notifications_open",
            localizedText("打开通知", "Open notification"),
            localizedText("打开 notifications_list 返回的指定通知。", "Open a notification returned by notifications_list."),
            """{"type":"object","properties":{"key":{"type":"string","maxLength":1000}},"required":["key"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("打开一条系统通知。", "Open a system notification."),
        ),
        ToolDefinition(
            "notifications_action",
            localizedText("执行通知按钮", "Run notification action"),
            localizedText("执行 notifications_list 返回的通知按钮。验证码或账户安全通知不允许自动执行。", "Execute a notification button returned by notifications_list. Verification-code and account-security notifications cannot be automated."),
            """{"type":"object","properties":{"key":{"type":"string","maxLength":1000},"action_index":{"type":"integer","minimum":0,"maximum":20}},"required":["key","action_index"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("执行一条通知中的操作按钮。", "Run an action button in a notification."),
        ),
        ToolDefinition(
            "notifications_dismiss",
            localizedText("清除通知", "Dismiss notification"),
            localizedText("清除 notifications_list 返回且允许清除的通知。验证码或账户安全通知不允许自动清除。", "Dismiss a notification returned by notifications_list when allowed. Verification-code and account-security notifications cannot be dismissed automatically."),
            """{"type":"object","properties":{"key":{"type":"string","maxLength":1000}},"required":["key"],"additionalProperties":false}""",
            ToolSideEffect.DESTRUCTIVE,
            id,
            approvalDescription = localizedText("清除一条系统通知。", "Dismiss a system notification."),
        ),
        ToolDefinition(
            "notifications_open_settings",
            localizedText("打开通知访问设置", "Open notification access settings"),
            localizedText("打开系统的通知使用权设置页，必须由用户亲自启用或关闭。", "Open system notification access settings. The user must enable or disable access personally."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("打开系统通知使用权设置页。", "Open system notification access settings."),
        ),
    )

    override suspend fun availability(): ToolAvailability =
        if (AgentNotificationListenerService.connected == null) {
            ToolAvailability(ToolAvailabilityState.DEGRADED, localizedText("尚未授予通知使用权；可以先打开系统设置", "Notification access is not granted; open system settings first."))
        } else {
            ToolAvailability(ToolAvailabilityState.AVAILABLE)
        }

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        if (call.toolId == "notifications_open_settings") {
            appContext.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@toolResult ToolResult("""{"opened":true}""", localizedText("已打开通知使用权设置", "Notification access settings opened"))
        }
        val service = checkNotNull(AgentNotificationListenerService.connected) {
            localizedText("尚未授予通知使用权，请先打开通知访问设置", "Notification access is not granted. Open notification access settings first.")
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
                }.toString(), localizedText("已读取 ${returned.size} 条系统通知", "Read ${returned.size} system notifications"))
            }
            "notifications_open" -> {
                service.open(required(args, "key"))
                ToolResult("""{"opened":true}""", localizedText("已打开通知", "Notification opened"))
            }
            "notifications_action" -> {
                service.invokeAction(required(args, "key"), int(args, "action_index"))
                ToolResult("""{"performed":true}""", localizedText("已执行通知操作", "Notification action executed"))
            }
            "notifications_dismiss" -> {
                service.dismiss(required(args, "key"))
                ToolResult("""{"dismissed":true}""", localizedText("已清除通知", "Notification dismissed"))
            }
            else -> error(localizedText("通知工具不支持 ${call.toolId}", "Notification tools do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = when (call.toolId) {
        "notifications_list" -> localizedText("读取当前通知", "Read current notifications")
        "notifications_open" -> localizedText("打开指定通知", "Open specified notification")
        "notifications_action" -> localizedText("执行指定通知按钮", "Run specified notification action")
        "notifications_dismiss" -> localizedText("清除指定通知", "Dismiss specified notification")
        "notifications_open_settings" -> localizedText("打开通知访问设置", "Open notification access settings")
        else -> null
    }

    private fun required(args: JsonObject, name: String) =
        args[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: error(localizedText("缺少参数 $name", "Missing parameter: $name"))
    private fun int(args: JsonObject, name: String) =
        args[name]?.jsonPrimitive?.intOrNull ?: error(localizedText("缺少参数 $name", "Missing parameter: $name"))
}
