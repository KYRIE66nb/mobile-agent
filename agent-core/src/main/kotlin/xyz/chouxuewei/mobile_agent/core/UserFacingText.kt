package xyz.chouxuewei.mobile_agent.core

/**
 * 把可能来自网络、模型或工具实现的异常转换为用户能理解的说明。
 * 原始异常仍可由调用方写入诊断日志，聊天界面只展示影响和可执行的下一步。
 */
fun userFacingMessage(message: String?, fallback: String = "操作未完成，请重试"): String {
    val value = message?.trim().orEmpty()
    if (value.isBlank()) return fallback
    return when {
        Regex("(?i)HTTP\\s*(401|403)").containsMatchIn(value) ->
            "API 密钥无效或没有访问权限，请检查模型设置"
        Regex("(?i)HTTP\\s*404").containsMatchIn(value) ->
            "没有找到该服务或模型，请检查服务地址和模型 ID"
        Regex("(?i)HTTP\\s*408").containsMatchIn(value) || value.contains("超时") ->
            "请求超时，请稍后重试"
        Regex("(?i)HTTP\\s*413").containsMatchIn(value) ->
            "本次发送的内容过大，请减少附件或缩短输入后重试"
        Regex("(?i)HTTP\\s*429").containsMatchIn(value) ->
            "请求过于频繁或账户额度不足，请稍后重试或检查账户额度"
        Regex("(?i)HTTP\\s*5\\d\\d").containsMatchIn(value) ->
            "模型服务暂时不可用，请稍后重试"
        Regex("(?i)HTTP\\s*4\\d\\d").containsMatchIn(value) ->
            "模型服务拒绝了本次请求，请检查模型与参数设置"
        value.contains("Unable to resolve host", ignoreCase = true) ||
            value.contains("UnknownHost", ignoreCase = true) || value.contains("DNS", ignoreCase = true) ->
            "无法连接服务，请检查网络和服务地址"
        value.contains("Failed to connect", ignoreCase = true) ||
            value.contains("Connection reset", ignoreCase = true) ||
            value.contains("unexpected end of stream", ignoreCase = true) ->
            "连接意外中断，请检查网络后重试"
        value.contains("SSL", ignoreCase = true) || value.contains("certificate", ignoreCase = true) ->
            "无法建立安全连接，请检查服务地址或证书配置"
        value.contains("API Key 为空") || value.contains("尚未配置模型 API Key") ->
            "尚未配置 API 密钥，请前往模型设置完成配置"
        value.contains("Keystore") || value.contains("无法解密") || value.contains("数据不完整") ->
            "无法读取已保存的 API 密钥，请在模型设置中重新填写"
        value.contains("JSON", ignoreCase = true) || value.contains("choices[") ||
            value.contains("finish_reason", ignoreCase = true) || value.contains("reasoning_chars") ->
            "模型响应格式不兼容，请检查服务配置后重试"
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

fun userFacingMessage(error: Throwable, fallback: String = "操作未完成，请重试"): String =
    userFacingMessage(error.message, fallback)
