package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

class FileToolProvider(
    context: Context,
    private val conversations: ConversationStore,
    private val artifacts: ArtifactStore,
) : ToolProvider {
    private val appContext = context.applicationContext
    private val artifactPublisher = GeneratedArtifactPublisher(appContext, artifacts)
    override val id = "files"
    override val title = "文件"
    override val description = "读取你添加的文本文件，并保存 AI 生成的文件。"

    override val definitions = listOf(
        ToolDefinition(
            "file_list", "查看对话文件",
            "列出当前会话中由用户添加的附件和 AI 生成的产物，不会遍历手机其他文件。不知道准确的 uri、artifact_id 或产物名称时先调用此工具。",
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ, "files",
            approvalDescription = "查看这段对话中的附件和已生成文件。",
        ),
        ToolDefinition(
            "file_read", "读取文件",
            "使用 uri、artifact_id 或产物名称三者之一读取当前会话中的文本文件。附件使用 file_list 返回的 uri，AI 产物优先使用 artifact_id；返回 truncated=true 时用 next_offset 继续读取。",
            """{"type":"object","properties":{"artifact_id":{"type":"string","description":"file_list 返回的 AI 产物 ID"},"uri":{"type":"string","description":"file_list 返回的已授权附件 URI"},"name":{"type":"string","description":"AI 生成产物的准确名称，不能用于附件"},"offset":{"type":"integer","minimum":0,"default":0},"max_chars":{"type":"integer","minimum":1,"maximum":8000,"default":8000}},"oneOf":[{"required":["artifact_id"]},{"required":["uri"]},{"required":["name"]}],"additionalProperties":false}""",
            ToolSideEffect.READ, "files",
            approvalDescription = "读取你添加或由 AI 生成的文本文件。",
        ),
        ToolDefinition(
            "file_write", "生成文本文件",
            "仅在用户需要文件或任务确实需要可保存产物时，在 App 工作区生成完整的 UTF-8 文本文件；不要写入占位内容。同名文件会自动使用新名称，不覆盖旧产物。文件名支持安全 Unicode，但不能包含路径分隔符或控制字符。",
            """{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":120,"description":"包含扩展名的文件名，不能包含 /、\\ 或控制字符"},"content":{"type":"string","maxLength":500000,"description":"要保存的完整文件正文"}},"required":["name","content"],"additionalProperties":false}""",
            ToolSideEffect.LOCAL_WRITE, "files",
            approvalDescription = "在 Mobile Agent 中保存一个文本文件。",
        ),
        ToolDefinition(
            "file_share", "分享文件",
            "使用 file_list 返回的 artifact_id 或附件 uri 打开 Android 系统分享面板。只能分享当前对话中已有且仍可访问的文件，最终接收方由用户选择或确认。",
            """{"type":"object","properties":{"artifact_id":{"type":"string"},"uri":{"type":"string"},"title":{"type":"string","maxLength":200}},"oneOf":[{"required":["artifact_id"]},{"required":["uri"]}],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE, "files",
            approvalDescription = "打开系统分享面板并分享对话中的文件。",
        ),
    )

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        when (call.toolId) {
            "file_list" -> list(context)
            "file_read" -> read(call.arguments(), context)
            "file_write" -> write(call.arguments(), context)
            "file_share" -> share(call.arguments(), context)
            else -> error("文件工具不支持 ${call.toolId}")
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "file_write" -> args["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.take(120)?.let { "文件名：$it" }
            "file_read" -> args["name"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.take(120)?.let { "文件名：$it" } ?: "读取对话中的文件"
            "file_list" -> "查看当前对话中的文件"
            "file_share" -> "分享当前对话中的文件"
            else -> null
        }
    }.getOrNull()

    private suspend fun attachments(conversationId: String): List<AttachmentRef> {
        val conversation = conversations.conversation(conversationId)
        return ((conversation?.attachments ?: emptyList()) + conversations.messages(conversationId).flatMap { it.attachments })
            .distinctBy { it.uri }
    }

    private suspend fun list(context: ToolExecutionContext): ToolResult {
        val refs = attachments(context.conversationId)
        val generated = artifacts.artifacts(context.conversationId)
        val content = buildJsonObject {
            putJsonArray("attachments") { refs.forEach { ref -> add(buildJsonObject {
                put("name", ref.name); put("uri", ref.uri); put("mime_type", ref.mimeType)
            }) } }
            putJsonArray("artifacts") { generated.forEach { artifact -> add(buildJsonObject {
                put("artifact_id", artifact.id)
                put("name", artifact.name)
                put("mime_type", artifact.mimeType)
                put("size", artifact.sizeBytes)
                put("uri", artifact.contentUri)
            }) } }
        }.toString()
        return ToolResult(content, "找到 ${refs.size} 个附件和 ${generated.size} 个已生成文件")
    }

    private suspend fun read(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val artifactId = args["artifact_id"]?.jsonPrimitive?.contentOrNull?.trim()
        val uriValue = args["uri"]?.jsonPrimitive?.contentOrNull?.trim()
        val nameValue = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val maxChars = (args["max_chars"]?.jsonPrimitive?.intOrNull ?: 8_000).coerceIn(1, 8_000)
        require(listOf(artifactId, uriValue, nameValue).count { !it.isNullOrBlank() } == 1) {
            "artifact_id、uri 和 name 必须且只能提供一个"
        }

        val source = when {
            !artifactId.isNullOrBlank() -> generatedSource(context.conversationId, artifactId)
            !uriValue.isNullOrBlank() -> attachmentSource(context.conversationId, uriValue)
            else -> {
                val match = artifacts.artifacts(context.conversationId).lastOrNull { it.name == nameValue }
                    ?: error("生成文件不存在")
                generatedSource(context.conversationId, match.id)
            }
        }
        require(isTextMime(source.mimeType)) { "当前只读取文本、Markdown、JSON 或 XML 文件" }
        val chunk = withContext(Dispatchers.IO) { source.open().use { it.readChunk(offset, maxChars) } }
        val result = buildJsonObject {
            put("source_ref", source.reference)
            put("name", source.name)
            put("offset", offset)
            put("returned_chars", chunk.text.length)
            put("content", chunk.text)
            put("truncated", chunk.hasMore)
            if (chunk.hasMore) put("next_offset", offset + chunk.text.length)
        }.toString()
        return ToolResult(result, "已读取 ${source.name} 的 ${chunk.text.length} 个字符")
    }

    private suspend fun write(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val requestedName = args["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        val content = args["content"]?.jsonPrimitive?.content ?: error("缺少文件内容")
        require(content.length <= 500_000) { "单个文本文件不能超过 500000 个字符" }
        val artifact = artifactPublisher.publish(
            context = context,
            requestedName = requestedName,
            mimeType = mimeType(requestedName),
        ) { output ->
            output.writer(Charsets.UTF_8).buffered().apply {
                write(content)
                flush()
            }
        }
        // 文件正文已经保存在产物中，模型只接收稳定引用，避免把大段内容再次塞回上下文。
        return ToolResult(
            buildJsonObject {
                put("artifact_id", artifact.id)
                put("name", artifact.name)
                put("mime_type", artifact.mimeType)
                put("size", artifact.sizeBytes)
                put("uri", artifact.contentUri)
                put("content_stored", true)
            }.toString(),
            "已生成 ${artifact.name}",
        )
    }

    private suspend fun share(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val artifactId = args["artifact_id"]?.jsonPrimitive?.contentOrNull?.trim()
        val uriValue = args["uri"]?.jsonPrimitive?.contentOrNull?.trim()
        require(listOf(artifactId, uriValue).count { !it.isNullOrBlank() } == 1) {
            "artifact_id 和 uri 必须且只能提供一个"
        }
        val (name, mimeType, contentUri) = if (!artifactId.isNullOrBlank()) {
            val artifact = artifacts.artifact(artifactId)
                ?.takeIf { it.conversationId == context.conversationId && it.status == ArtifactStatus.AVAILABLE }
                ?: error("文件不存在或不属于当前对话")
            val file = File(artifact.storagePath).canonicalFile
            require(file.isFile && artifactPublisher.isManagedFile(file)) { "文件不可用，请重新生成" }
            Triple(artifact.name, artifact.mimeType, artifact.contentUri)
        } else {
            val attachment = attachments(context.conversationId).firstOrNull { it.uri == uriValue }
                ?: error("这个附件不属于当前对话")
            Triple(attachment.name, attachment.mimeType ?: "application/octet-stream", attachment.uri)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(contentUri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(appContext.packageManager.resolveActivity(send, 0) != null) { "没有应用可以接收这个文件" }
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.take(200).orEmpty().ifBlank { "分享 $name" }
        appContext.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult(buildJsonObject {
            put("opened", true)
            put("name", name)
            put("mime_type", mimeType)
        }.toString(), "已打开 $name 的系统分享面板")
    }

    private suspend fun generatedSource(conversationId: String, artifactId: String): TextSource {
        val artifact = artifacts.artifact(artifactId)
            ?.takeIf { it.conversationId == conversationId && it.status == ArtifactStatus.AVAILABLE }
            ?: error("文件不存在或不属于当前对话")
        val file = File(artifact.storagePath).canonicalFile
        require(file.isFile && artifactPublisher.isManagedFile(file)) { "文件不可用，请重新生成" }
        return TextSource("artifact:${artifact.id}", artifact.name, artifact.mimeType) { file.bufferedReader(Charsets.UTF_8) }
    }

    private suspend fun attachmentSource(conversationId: String, uriValue: String): TextSource {
        val attachment = attachments(conversationId).firstOrNull { it.uri == uriValue }
            ?: error("这个附件不属于当前对话")
        return TextSource("attachment:$uriValue", attachment.name, attachment.mimeType) {
            appContext.contentResolver.openInputStream(Uri.parse(uriValue))?.bufferedReader(Charsets.UTF_8)
                ?: error("无法打开附件")
        }
    }

    private fun isTextMime(value: String?) = value == null || value.startsWith("text/") ||
        value in setOf("application/json", "application/xml", "application/xhtml+xml", "image/svg+xml")

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "text/plain"
        }
    }

    private data class TextSource(
        val reference: String,
        val name: String,
        val mimeType: String?,
        val open: () -> Reader,
    )

    private data class TextChunk(val text: String, val hasMore: Boolean)

    private fun Reader.readChunk(offset: Int, limit: Int): TextChunk {
        var remaining = offset.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) return TextChunk("", false)
                remaining--
            } else remaining -= skipped
        }
        val result = StringBuilder(minOf(limit, 8_192))
        val buffer = CharArray(4_096)
        while (result.length < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - result.length))
            if (count < 0) return TextChunk(result.toString(), false)
            result.append(buffer, 0, count)
        }
        return TextChunk(result.toString(), read() >= 0)
    }
}
