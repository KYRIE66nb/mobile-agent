package xyz.chouxuewei.mobile_agent.tools

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
    override val title = "剪贴板"
    override val description = "读取、写入或清空系统剪贴板。Android 可能只允许前台应用读取。"
    override val definitions = listOf(
        ToolDefinition(
            "clipboard_read",
            "读取剪贴板",
            "读取当前剪贴板中的纯文本以及 MIME 类型。仅在用户请求确实需要时调用；系统拒绝后台读取时应说明限制。",
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = "读取当前系统剪贴板。",
        ),
        ToolDefinition(
            "clipboard_write",
            "写入剪贴板",
            "把给定文本写入系统剪贴板，会覆盖原内容。",
            """{"type":"object","properties":{"text":{"type":"string","maxLength":20000},"label":{"type":"string","maxLength":100}},"required":["text"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "覆盖当前系统剪贴板内容。",
        ),
        ToolDefinition(
            "clipboard_clear",
            "清空剪贴板",
            "清空当前系统剪贴板。",
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.DESTRUCTIVE,
            id,
            approvalDescription = "清空当前系统剪贴板内容。",
        ),
    )

    override suspend fun availability() = if (clipboard == null) {
        ToolAvailability(ToolAvailabilityState.UNSUPPORTED, "当前设备没有系统剪贴板服务")
    } else {
        ToolAvailability(ToolAvailabilityState.AVAILABLE)
    }

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val manager = checkNotNull(clipboard) { "当前设备没有系统剪贴板服务" }
        val args = call.arguments()
        when (call.toolId) {
            "clipboard_read" -> {
                val clip = manager.primaryClip
                val text = clip?.let { value ->
                    (0 until value.itemCount).joinToString("\n") { index ->
                        value.getItemAt(index).coerceToText(appContext).toString()
                    }
                }.orEmpty()
                require(text.length <= 20_000) { "剪贴板文本超过 20000 字符，请先缩短内容" }
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
                }.toString(), if (text.isBlank()) "剪贴板中没有可读取的文本" else "已读取剪贴板文本")
            }
            "clipboard_write" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull ?: error("缺少参数 text")
                require(text.length <= 20_000) { "写入内容不能超过 20000 字符" }
                val label = args["label"]?.jsonPrimitive?.contentOrNull?.take(100).orEmpty().ifBlank { "Mobile Agent" }
                manager.setPrimaryClip(ClipData.newPlainText(label, text))
                ToolResult("""{"written":true,"length":${text.length}}""", "已写入系统剪贴板")
            }
            "clipboard_clear" -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    manager.clearPrimaryClip()
                } else {
                    @Suppress("DEPRECATION")
                    manager.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                ToolResult("""{"cleared":true}""", "已清空系统剪贴板")
            }
            else -> error("剪贴板工具不支持 ${call.toolId}")
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = when (call.toolId) {
        "clipboard_read" -> "读取剪贴板文本"
        "clipboard_write" -> "写入剪贴板（${call.argumentsJson.length.coerceAtMost(20_000)} 字符以内）"
        "clipboard_clear" -> "清空剪贴板"
        else -> null
    }
}
