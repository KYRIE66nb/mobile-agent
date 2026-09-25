package xyz.chouxuewei.mobile_agent.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.userFacingMessage

internal val TOOL_JSON = Json { ignoreUnknownKeys = true }

internal fun RequestedToolCall.arguments(): JsonObject =
    runCatching { TOOL_JSON.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }
        .getOrElse { throw IllegalArgumentException("工具参数不是有效 JSON 对象") }

internal inline fun toolResult(block: () -> ToolResult): ToolResult = try {
    block()
} catch (cancelled: CancellationException) {
    // 停止按钮依赖取消异常向运行时传播；工具不能把它误报为普通执行失败。
    throw cancelled
} catch (error: Exception) {
    val message = error.message ?: "工具执行失败"
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
        "手机界面已经变化，需要重新识别后才能继续操作"
    message.contains("session", ignoreCase = true) || message.contains("会话标识") ||
        message.contains("会话已关闭") -> "本次手机操作已结束，需要重新开始"
    message.contains("package", ignoreCase = true) || message.contains("包名") ||
        message.contains("启动入口") -> "无法识别目标应用，请重新获取应用列表"
    message.contains("Root") -> "请先在通用设置中授予 Root 权限"
    message.contains("通知使用权") || message.contains("通知访问设置") ->
        "请先在系统设置中授予通知使用权"
    message.contains("修改系统设置") || message.contains("write_settings") ->
        "请先在系统设置中允许 Mobile Agent 修改系统设置"
    message.contains("剪贴板") -> "系统暂时不允许读取或修改剪贴板，请回到 App 前台后重试"
    message.contains("DNS", ignoreCase = true) -> "无法连接该网站，请更换来源或稍后重试"
    message.contains("局域网") || message.contains("保留网段") || message.contains("保留地址") ->
        "出于安全原因，无法访问本机或局域网地址"
    message.contains("网页没有可读取的正文") || message.contains("不是可读取的文本") ->
        "没有找到可读取的网页正文，请更换来源"
    message.contains("重定向") -> "网页跳转次数过多，请更换来源"
    message.contains("artifact", ignoreCase = true) || message.contains("URI") ||
        message.contains("产物") -> "文件不可用，请重新选择或生成"
    message.contains("消息 ID") || message.contains("工具调用 ID") ->
        "工具参数不完整，无法读取历史消息"
    message.contains("缺少参数") || message.contains("必须且只能") ||
        message.contains("不是有效 JSON") -> "工具参数不完整，无法执行"
    else -> userFacingMessage(message)
}
