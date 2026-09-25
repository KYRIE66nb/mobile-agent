package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolAvailability
import xyz.chouxuewei.mobile_agent.core.ToolAvailabilityState
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect

/** 剪贴板单独成组，用户可在不关闭其他系统工具的情况下独立禁用。 */
class ClipboardToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    override val id = "clipboard"
    override val title get() = localizedText("剪贴板", "Clipboard")
    override val description get() = localizedText("读取、写入或清空系统剪贴板。Android 可能只允许前台应用读取。", "Read, write, or clear the system clipboard. Android may allow reads only while the app is in the foreground.")
    override val definitions get() = listOf(
        ToolDefinition(
            "clipboard_read",
            localizedText("读取剪贴板", "Read clipboard"),
            localizedText("读取当前剪贴板中的纯文本以及 MIME 类型。仅在用户请求确实需要时调用；系统拒绝后台读取时应说明限制。", "Read plain text and MIME types from the current clipboard. Call only when required by the user request, and explain the limitation if background access is denied."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取当前系统剪贴板。", "Read the current system clipboard."),
        ),
        ToolDefinition(
            "clipboard_write",
            localizedText("写入剪贴板", "Write clipboard"),
            localizedText("把给定文本写入系统剪贴板，会覆盖原内容。", "Write text to the system clipboard, replacing its current content."),
            """{"type":"object","properties":{"text":{"type":"string","maxLength":20000},"label":{"type":"string","maxLength":100}},"required":["text"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("覆盖当前系统剪贴板内容。", "Replace the current system clipboard content."),
        ),
        ToolDefinition(
            "clipboard_clear",
            localizedText("清空剪贴板", "Clear clipboard"),
            localizedText("清空当前系统剪贴板。", "Clear the current system clipboard."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.DESTRUCTIVE,
            id,
            approvalDescription = localizedText("清空当前系统剪贴板内容。", "Clear the current system clipboard content."),
        ),
    )

    override suspend fun availability() = if (clipboard == null) {
        ToolAvailability(ToolAvailabilityState.UNSUPPORTED, localizedText("当前设备没有系统剪贴板服务", "The system clipboard service is unavailable on this device."))
    } else {
        ToolAvailability(ToolAvailabilityState.AVAILABLE)
    }

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val manager = checkNotNull(clipboard) { localizedText("当前设备没有系统剪贴板服务", "The system clipboard service is unavailable on this device.") }
        val args = call.arguments()
        when (call.toolId) {
            "clipboard_read" -> {
                val clip = manager.primaryClip
                val text = clip?.let { value ->
                    (0 until value.itemCount).joinToString("\n") { index ->
                        value.getItemAt(index).coerceToText(appContext).toString()
                    }
                }.orEmpty()
                require(text.length <= 20_000) { localizedText("剪贴板文本超过 20000 字符，请先缩短内容", "Clipboard text exceeds 20000 characters. Shorten it first.") }
                ToolResult(buildJsonObject {
                    put("has_content", clip != null && clip.itemCount > 0)
                    put("text", text)
                    put("item_count", clip?.itemCount ?: 0)
                    put("mime_types", buildJsonArray {
                        clip?.description?.let { description ->
                            for (index in 0 until description.mimeTypeCount) {
                                add(JsonPrimitive(description.getMimeType(index)))
                            }
                        }
                    })
                }.toString(), if (text.isBlank()) localizedText("剪贴板中没有可读取的文本", "The clipboard contains no readable text.") else localizedText("已读取剪贴板文本", "Clipboard text read"))
            }
            "clipboard_write" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull ?: error(localizedText("缺少参数 text", "Missing parameter: text"))
                require(text.length <= 20_000) { localizedText("写入内容不能超过 20000 字符", "The content cannot exceed 20000 characters.") }
                val label = args["label"]?.jsonPrimitive?.contentOrNull?.take(100).orEmpty().ifBlank { "Mobile Agent" }
                manager.setPrimaryClip(ClipData.newPlainText(label, text))
                ToolResult("""{"written":true,"length":${text.length}}""", localizedText("已写入系统剪贴板", "Written to system clipboard"))
            }
            "clipboard_clear" -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    manager.clearPrimaryClip()
                } else {
                    @Suppress("DEPRECATION")
                    manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                ToolResult("""{"cleared":true}""", localizedText("已清空系统剪贴板", "System clipboard cleared"))
            }
            else -> error(localizedText("剪贴板工具不支持 ${call.toolId}", "Clipboard tools do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = when (call.toolId) {
        "clipboard_read" -> localizedText("读取剪贴板文本", "Read clipboard text")
        "clipboard_write" -> localizedText("写入剪贴板（${call.argumentsJson.length.coerceAtMost(20_000)} 字符以内）", "Write clipboard (up to ${call.argumentsJson.length.coerceAtMost(20_000)} characters)")
        "clipboard_clear" -> localizedText("清空剪贴板", "Clear clipboard")
        else -> null
    }
}
