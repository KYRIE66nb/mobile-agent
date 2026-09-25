package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.userFacingMessage

internal val TOOL_JSON = Json { ignoreUnknownKeys = true }

private val LOCALIZED_SCHEMA_TEXT = Regex(
    """localizedText\(("(?:\\.|[^"\\])*")\s*,\s*("(?:\\.|[^"\\])*")\)""",
)

/**
 * 工具参数结构最终必须是合法 JSON，不能把 Kotlin 的 localizedText 调用原样塞进字符串。
 * 这里先选择系统语言对应的说明，再编码成 JSON 字符串并校验完整结构。
 */
internal fun localizedJsonSchema(schema: String): String {
    val localized = LOCALIZED_SCHEMA_TEXT.replace(schema) { match ->
        val chinese = TOOL_JSON.decodeFromString<String>(match.groupValues[1])
        val english = TOOL_JSON.decodeFromString<String>(match.groupValues[2])
        TOOL_JSON.encodeToString(localizedText(chinese, english))
    }
    check("localizedText(" !in localized) { "Unresolved localized schema text" }
    TOOL_JSON.parseToJsonElement(localized).jsonObject
    return localized
}

internal fun RequestedToolCall.arguments(): JsonObject =
    runCatching { TOOL_JSON.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }
        .getOrElse { throw IllegalArgumentException(localizedText("工具参数不是有效 JSON 对象", "Tool arguments are not a valid JSON object.")) }

internal inline fun toolResult(block: () -> ToolResult): ToolResult = try {
    block()
} catch (cancelled: CancellationException) {
    // 停止按钮依赖取消异常向运行时传播；工具不能把它误报为普通执行失败。
    throw cancelled
} catch (error: Exception) {
    val message = error.message ?: localizedText("工具执行失败", "Tool execution failed")
    ToolResult(
        content = JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive(message))).toString(),
        summary = toolErrorSummary(message),
        isError = true,
    )
}

/** 工具内容保留原始错误供模型调整参数，卡片只展示用户可以理解和处理的说明。 */
internal fun toolErrorSummary(message: String): String = when {
    message.contains("observation", ignoreCase = true) || message.contains("重新观察") ||
        message.contains("观察已过期") || message.contains("识别结果已过期") ->
        localizedText("手机界面已经变化，需要重新识别后才能继续操作", "The phone screen changed. Inspect it again before continuing.")
    message.contains("session", ignoreCase = true) || message.contains("会话标识") ||
        message.contains("会话已关闭") -> localizedText("本次手机操作已结束，需要重新开始", "This phone operation ended and must be started again.")
    message.contains("package", ignoreCase = true) || message.contains("包名") ||
        message.contains("启动入口") -> localizedText("无法识别目标应用，请重新获取应用列表", "The target app was not recognized. Get the app list again.")
    message.contains("Root") -> localizedText("请先在通用设置中授予 Root 权限", "Grant Root access in General settings first.")
    message.contains("通知使用权") || message.contains("通知访问设置") ||
        message.contains("notification access", ignoreCase = true) ->
        localizedText("请先在系统设置中授予通知使用权", "Grant notification access in system settings first.")
    message.contains("修改系统设置") || message.contains("write_settings") ->
        localizedText("请先在系统设置中允许 Mobile Agent 修改系统设置", "Allow Mobile Agent to modify system settings first.")
    message.contains("剪贴板") || message.contains("clipboard", ignoreCase = true) ->
        localizedText("系统暂时不允许读取或修改剪贴板，请回到 App 前台后重试", "Clipboard access is temporarily unavailable. Return to the app foreground and try again.")
    message.contains("DNS", ignoreCase = true) -> localizedText("无法连接该网站，请更换来源或稍后重试", "Could not connect to this website. Use another source or try again later.")
    message.contains("局域网") || message.contains("保留网段") || message.contains("保留地址") ||
        message.contains("private network", ignoreCase = true) || message.contains("reserved", ignoreCase = true) ->
        localizedText("出于安全原因，无法访问本机或局域网地址", "Local or private network addresses cannot be accessed for security reasons.")
    message.contains("网页没有可读取的正文") || message.contains("不是可读取的文本") ||
        message.contains("no readable", ignoreCase = true) ->
        localizedText("没有找到可读取的网页正文，请更换来源", "No readable webpage text was found. Use another source.")
    message.contains("重定向") || message.contains("redirect", ignoreCase = true) ->
        localizedText("网页跳转次数过多，请更换来源", "The webpage redirected too many times. Use another source.")
    message.contains("artifact", ignoreCase = true) || message.contains("URI") ||
        message.contains("产物") -> localizedText("文件不可用，请重新选择或生成", "The file is unavailable. Choose or generate it again.")
    message.contains("消息 ID") || message.contains("工具调用 ID") ||
        message.contains("message ID", ignoreCase = true) || message.contains("tool call ID", ignoreCase = true) ->
        localizedText("工具参数不完整，无法读取历史消息", "Tool arguments are incomplete, so history messages cannot be read.")
    message.contains("缺少参数") || message.contains("必须且只能") ||
        message.contains("missing parameter", ignoreCase = true) ||
        message.contains("provide exactly one", ignoreCase = true) ||
        message.contains("不是有效 JSON") || message.contains("valid JSON", ignoreCase = true) ->
        localizedText("工具参数不完整，无法执行", "Tool arguments are incomplete and cannot be executed.")
    else -> userFacingMessage(message)
}
