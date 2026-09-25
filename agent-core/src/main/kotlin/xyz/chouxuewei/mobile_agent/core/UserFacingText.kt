package xyz.chouxuewei.mobile_agent.core

/**
 * 把可能来自网络、模型或工具实现的异常转换为用户能理解的说明。
 * 原始异常仍可由调用方写入诊断日志，聊天界面只展示影响和可执行的下一步。
 */
fun userFacingMessage(message: String?, fallback: String = localizedText("操作未完成，请重试", "The operation was not completed. Please try again.")): String {
    val value = message?.trim().orEmpty()
    if (value.isBlank()) return fallback
    return when {
        Regex("(?i)HTTP\\s*(401|403)").containsMatchIn(value) ->
            localizedText("API 密钥无效或没有访问权限，请检查模型设置", "The API key is invalid or lacks access. Check Model settings.")
        Regex("(?i)HTTP\\s*404").containsMatchIn(value) ->
            localizedText("没有找到该服务或模型，请检查服务地址和模型 ID", "The service or model was not found. Check the service URL and model ID.")
        Regex("(?i)HTTP\\s*408").containsMatchIn(value) || value.contains("超时") ->
            localizedText("请求超时，请稍后重试", "The request timed out. Please try again later.")
        Regex("(?i)HTTP\\s*413").containsMatchIn(value) ->
            localizedText("本次发送的内容过大，请减少附件或缩短输入后重试", "This message is too large. Remove attachments or shorten the input and try again.")
        Regex("(?i)HTTP\\s*429").containsMatchIn(value) ->
            localizedText("请求过于频繁或账户额度不足，请稍后重试或检查账户额度", "Too many requests or insufficient account quota. Try again later or check your quota.")
        Regex("(?i)HTTP\\s*5\\d\\d").containsMatchIn(value) ->
            localizedText("模型服务暂时不可用，请稍后重试", "The model service is temporarily unavailable. Please try again later.")
        Regex("(?i)HTTP\\s*4\\d\\d").containsMatchIn(value) ->
            localizedText("模型服务拒绝了本次请求，请检查模型与参数设置", "The model service rejected this request. Check the model and parameter settings.")
        value.contains("Unable to resolve host", ignoreCase = true) ||
            value.contains("UnknownHost", ignoreCase = true) || value.contains("DNS", ignoreCase = true) ->
            localizedText("无法连接服务，请检查网络和服务地址", "Could not connect to the service. Check your network and service URL.")
        value.contains("Failed to connect", ignoreCase = true) ||
            value.contains("Connection reset", ignoreCase = true) ||
            value.contains("unexpected end of stream", ignoreCase = true) ->
            localizedText("连接意外中断，请检查网络后重试", "The connection was interrupted. Check your network and try again.")
        value.contains("SSL", ignoreCase = true) || value.contains("certificate", ignoreCase = true) ->
            localizedText("无法建立安全连接，请检查服务地址或证书配置", "A secure connection could not be established. Check the service URL or certificate configuration.")
        value.contains("API Key 为空") || value.contains("尚未配置模型 API Key") ->
            localizedText("尚未配置 API 密钥，请前往模型设置完成配置", "No API key is configured. Complete it in Model settings.")
        value.contains("Keystore") || value.contains("无法解密") || value.contains("数据不完整") ->
            localizedText("无法读取已保存的 API 密钥，请在模型设置中重新填写", "The saved API key could not be read. Enter it again in Model settings.")
        value.contains("JSON", ignoreCase = true) || value.contains("choices[") ||
            value.contains("finish_reason", ignoreCase = true) || value.contains("reasoning_chars") ->
            localizedText("模型响应格式不兼容，请检查服务配置后重试", "The model response format is incompatible. Check the service configuration and try again.")
        value.contains("URI", ignoreCase = true) || value.contains("artifact_id") ||
            value.contains("tool_call_id") || value.contains("observation_id") ||
            value.contains("session_id") || value.contains("Binder") || value.contains("Surface") ||
            value.contains("displayId", ignoreCase = true) -> fallback
        value.contains("会话标识") || value.contains("会话已关闭") || value.contains("观察已过期") ||
            value.contains("识别结果已过期") ->
            fallback
        value.contains("Exception", ignoreCase = true) || value.contains("java.") ||
            value.contains("kotlin.") || value.contains("okhttp", ignoreCase = true) -> fallback
        else -> value
    }
}

fun userFacingMessage(error: Throwable, fallback: String = localizedText("操作未完成，请重试", "The operation was not completed. Please try again.")): String =
    userFacingMessage(error.message, fallback)
