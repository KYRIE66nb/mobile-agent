package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext

/**
 * 统一发布工具生成的文件：先写临时文件并同步，再原子改名和登记产物。
 * 文件工具与图像渲染工具共用这一条路径，避免出现文件已显示但内容尚未写完的情况。
 */
internal class GeneratedArtifactPublisher(
    context: Context,
    private val artifacts: ArtifactStore,
) {
    private val appContext = context.applicationContext
    private val generatedRoot =
        File(appContext.filesDir, "generated-tools").apply { mkdirs() }.canonicalFile

    suspend fun publish(
        context: ToolExecutionContext,
        requestedName: String,
        mimeType: String,
        write: suspend (OutputStream) -> Unit,
    ): Artifact {
        validateName(requestedName)
        val file = withContext(Dispatchers.IO) {
            val directory = generatedDir(context.conversationId)
            val target = availableFile(directory, requestedName)
            val temporary = File(directory, ".${UUID.randomUUID()}.pending")
            try {
                FileOutputStream(temporary).use { output ->
                    write(output)
                    output.flush()
                    output.fd.sync()
                }
                require(temporary.length() > 0L) { localizedText("生成的文件为空", "The generated file is empty.") }
                check(temporary.renameTo(target)) { localizedText("文件发布失败", "Failed to publish the file.") }
                target
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
        return try {
            val artifact = Artifact(
                id = UUID.randomUUID().toString(),
                conversationId = context.conversationId,
                runId = context.runId,
                replyMessageId = context.replyMessageId,
                sourceToolCallId = context.toolCallRecordId,
                name = file.name,
                mimeType = mimeType,
                sizeBytes = file.length(),
                contentUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.files",
                    file,
                ).toString(),
                storagePath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
            )
            artifacts.saveArtifact(artifact)
            artifact
        } catch (error: Exception) {
            withContext(Dispatchers.IO) { file.delete() }
            throw error
        }
    }

    fun isManagedFile(file: File): Boolean =
        file.path.startsWith(generatedRoot.path + File.separator)

    private fun generatedDir(conversationId: String): File {
        val directory = File(generatedRoot, conversationId).canonicalFile
        require(directory.path.startsWith(generatedRoot.path + File.separator)) { localizedText("会话目录无效", "Invalid conversation directory.") }
        directory.mkdirs()
        return directory
    }

    private fun availableFile(directory: File, requestedName: String): File {
        val initial = File(directory, requestedName).canonicalFile
        require(initial.parentFile == directory) { localizedText("文件路径超出工作区", "The file path is outside the workspace.") }
        if (!initial.exists()) return initial
        val dot = requestedName.lastIndexOf('.').takeIf { it > 0 } ?: requestedName.length
        val base = requestedName.substring(0, dot)
        val extension = requestedName.substring(dot)
        var index = 2
        while (true) {
            val candidate = File(directory, "$base ($index)$extension").canonicalFile
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun validateName(name: String) {
        require(name.length in 1..120 && name !in setOf(".", "..") && name.none {
            it == '/' || it == '\\' || Character.isISOControl(it)
        }) {
            localizedText("文件名无效：不能使用路径分隔符、控制字符或超过 120 个字符", "Invalid filename: path separators and control characters are not allowed, and the name cannot exceed 120 characters.")
        }
    }
}
