package xyz.chouxuewei.mobile_agent.attachments

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.AttachmentRef
import xyz.chouxuewei.mobile_agent.core.ChatAttachmentLoader
import xyz.chouxuewei.mobile_agent.core.ChatImage
import xyz.chouxuewei.mobile_agent.core.ConversationStore
import xyz.chouxuewei.mobile_agent.core.StorageCleanupResult
import xyz.chouxuewei.mobile_agent.core.userFacingMessage

data class IncomingShare(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val attachments: List<AttachmentRef> = emptyList(),
)

/**
 * 统一处理系统选择器、外部分享和模型图片输入。
 * 外部分享的临时 URI 会立即复制进受控目录；模型使用的缩放图片只放缓存目录。
 */
class AttachmentManager(
    context: Context,
    private val conversations: ConversationStore,
) : ChatAttachmentLoader {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val authority = "${appContext.packageName}.files"
    private val importedRoot = File(appContext.filesDir, "shared-attachments").apply { mkdirs() }.canonicalFile
    private val imageCacheRoot = File(appContext.cacheDir, "chat-images").apply { mkdirs() }.canonicalFile
    private val mutableIncoming = MutableStateFlow<List<IncomingShare>>(emptyList())
    val incoming: StateFlow<List<IncomingShare>> = mutableIncoming
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private val fileGate = Mutex()
    private val leasedUris = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun receive(intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        runCatching {
            withContext(Dispatchers.IO) {
                fileGate.withLock {
                    importShare(intent).also { share ->
                        mutableIncoming.update { current -> current + share }
                    }
                }
            }
        }
            .onFailure { failure -> mutableError.value = userFacingMessage(failure, "无法接收分享内容，请重试") }
    }

    fun clearError() { mutableError.value = null }

    fun takeForDraft(id: String): IncomingShare? {
        var removed: IncomingShare? = null
        mutableIncoming.update { values ->
            removed = values.firstOrNull { it.id == id }
            values.filterNot { it.id == id }
        }
        removed?.attachments?.mapTo(leasedUris, AttachmentRef::uri)
        return removed
    }

    fun dismiss(id: String): IncomingShare? {
        var removed: IncomingShare? = null
        mutableIncoming.update { values ->
            removed = values.firstOrNull { it.id == id }
            values.filterNot { it.id == id }
        }
        return removed
    }

    fun release(attachments: List<AttachmentRef>) {
        attachments.forEach { leasedUris.remove(it.uri) }
    }

    fun restore(share: IncomingShare) {
        release(share.attachments)
        mutableIncoming.update { values -> if (values.any { it.id == share.id }) values else listOf(share) + values }
    }

    suspend fun fromPicker(uri: Uri): AttachmentRef = withContext(Dispatchers.IO) {
        fileGate.withLock {
            require(uri.scheme == "content") { "无法读取这个来源，请通过系统文件选择器重新选择" }
            val metadata = metadata(uri)
            val mimeType = metadata.mimeType
            if (mimeType.startsWith("image/")) require((metadata.size ?: 0L) <= MAX_IMAGE_INPUT_BYTES) {
                "图片不能超过 ${MAX_IMAGE_INPUT_BYTES / 1024 / 1024} MB"
            }
            val attachment = try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                AttachmentRef(uri.toString(), metadata.name, mimeType, metadata.size)
            } catch (_: SecurityException) {
                // 少数文档提供者不支持持久授权；复制后才能保证重启 App 仍可继续使用。
                copyIntoOwnedDirectory(uri, metadata, UUID.randomUUID().toString())
            }
            leasedUris += attachment.uri
            attachment
        }
    }

    override suspend fun loadImage(attachment: AttachmentRef): ChatImage = withContext(Dispatchers.IO) {
        fileGate.withLock {
            require(attachment.isImage) { "这个附件不是可发送的图片" }
            val cacheFile = File(imageCacheRoot, cacheKey(attachment) + ".jpg")
            val bytes = if (cacheFile.isFile && cacheFile.length() in 1..MAX_MODEL_IMAGE_BYTES) {
                cacheFile.readBytes()
            } else {
                val original = readLimited(Uri.parse(attachment.uri), MAX_IMAGE_INPUT_BYTES)
                val encoded = encodeForModel(original)
                val temporary = File(imageCacheRoot, ".${cacheFile.name}.tmp-${UUID.randomUUID()}")
                temporary.outputStream().use { output -> output.write(encoded) }
                if (!temporary.renameTo(cacheFile)) {
                    temporary.delete()
                    error("图片处理失败，请重试")
                }
                encoded
            }
            cacheFile.setLastModified(System.currentTimeMillis())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式，请更换图片后重试" }
            ChatImage(attachment.name, "image/jpeg", bytes, bounds.outWidth, bounds.outHeight)
        }
    }

    suspend fun cleanup(): StorageCleanupResult = withContext(Dispatchers.IO) {
        fileGate.withLock {
            val leased = synchronized(leasedUris) { leasedUris.toSet() }
            val liveUris = conversations.referencedAttachmentUris() + leased +
                mutableIncoming.value.flatMap { it.attachments }.map(AttachmentRef::uri)
            var filesDeleted = 0
            var bytesFreed = 0L
            var permissionsReleased = 0
            importedRoot.walkBottomUp().filter(File::isFile).forEach { file ->
                val uri = runCatching { FileProvider.getUriForFile(appContext, authority, file).toString() }.getOrNull()
                if (file.name.contains(".tmp-") || uri == null || uri !in liveUris) {
                    val size = file.length()
                    if (file.delete()) {
                        filesDeleted++
                        bytesFreed += size
                    }
                }
            }
            importedRoot.walkBottomUp().filter { it.isDirectory && it != importedRoot }
                .forEach { directory -> directory.delete() }
            resolver.persistedUriPermissions.filter { permission -> permission.uri.toString() !in liveUris }
                .forEach { permission ->
                    if (runCatching {
                        resolver.releasePersistableUriPermission(
                            permission.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }.isSuccess) permissionsReleased++
                }
            val cacheResult = pruneImageCache()
            StorageCleanupResult(
                filesDeleted + cacheResult.filesDeleted,
                bytesFreed + cacheResult.bytesFreed,
                permissionsReleased,
            )
        }
    }

    private fun importShare(intent: Intent): IncomingShare {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(MAX_SHARED_TEXT_CHARS)
        val uris = sharedUris(intent).distinctBy(Uri::toString)
        require(uris.size <= MAX_SHARED_FILES) { "一次最多分享 $MAX_SHARED_FILES 个文件" }
        val shareId = UUID.randomUUID().toString()
        val imported = mutableListOf<AttachmentRef>()
        return try {
            var total = 0L
            uris.forEach { uri ->
                require(uri.scheme == "content") { "无法安全读取这项分享内容" }
                val metadata = metadata(uri, intent.type)
                require(metadata.mimeType.startsWith("image/") || metadata.mimeType.startsWith("text/")) {
                    "目前只能分享图片或文字到 Mobile Agent"
                }
                val item = copyIntoOwnedDirectory(uri, metadata, shareId)
                total += item.sizeBytes ?: 0L
                require(total <= MAX_SHARED_TOTAL_BYTES) { "分享内容过大，请减少文件后重试" }
                imported += item
            }
            require(text.isNotBlank() || imported.isNotEmpty()) { "没有可添加的分享内容" }
            IncomingShare(shareId, text, imported)
        } catch (failure: Exception) {
            File(importedRoot, shareId).deleteRecursively()
            throw failure
        }
    }

    private fun copyIntoOwnedDirectory(uri: Uri, metadata: Metadata, groupId: String): AttachmentRef {
        val limit = if (metadata.mimeType.startsWith("image/")) MAX_IMAGE_INPUT_BYTES else MAX_TEXT_INPUT_BYTES
        metadata.size?.let { require(it <= limit) { "“${metadata.name}”过大，请选择较小的文件" } }
        val directory = File(importedRoot, groupId).apply { mkdirs() }
        val destination = uniqueFile(directory, safeName(metadata.name, metadata.mimeType))
        val temporary = File(directory, ".${destination.name}.tmp-${UUID.randomUUID()}")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    require(size <= limit) { "“${metadata.name}”过大，请选择较小的文件" }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        } ?: error("无法读取“${metadata.name}”")
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("无法保存“${metadata.name}”，请重试")
        }
        val published = FileProvider.getUriForFile(appContext, authority, destination)
        return AttachmentRef(published.toString(), destination.name, metadata.mimeType, size)
    }

    private fun sharedUris(intent: Intent): List<Uri> = buildList {
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let(::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                ?.mapNotNull { it as? Uri }?.let(::addAll)
        }
    }

    private fun metadata(uri: Uri, fallbackMimeType: String? = null): Metadata {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let(cursor::getString)
                size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
            }
        }
        val resolvedMime = resolver.getType(uri)?.lowercase()?.takeUnless { it == "application/octet-stream" }
        val fallbackMime = fallbackMimeType?.lowercase()?.takeUnless { it == "*/*" || it == "application/octet-stream" }
        val extension = (name ?: uri.lastPathSegment).orEmpty().substringAfterLast('.', "")
        val mime = resolvedMime
            ?: fallbackMime
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
        return Metadata(name?.takeIf(String::isNotBlank) ?: "附件", mime, size)
    }

    private fun readLimited(uri: Uri, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "图片不能超过 ${limit / 1024 / 1024} MB" }
                output.write(buffer, 0, read)
            }
        } ?: error("无法读取这张图片，请重新选择")
        return output.toByteArray()
    }

    private fun encodeForModel(source: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式，请更换图片后重试" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) { "图片尺寸过大，请压缩后重试" }
        var sample = 1
        while (bounds.outWidth / sample > MAX_MODEL_EDGE * 2 || bounds.outHeight / sample > MAX_MODEL_EDGE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("无法读取这张图片，请更换图片后重试")
        val oriented = applyExifOrientation(decoded, source)
        val scale = minOf(1f, MAX_MODEL_EDGE.toFloat() / maxOf(oriented.width, oriented.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else oriented
        val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        val output = ByteArrayOutputStream()
        check(flattened.compress(Bitmap.CompressFormat.JPEG, 86, output)) { "图片处理失败，请重试" }
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        flattened.recycle()
        val result = output.toByteArray()
        require(result.size <= MAX_MODEL_IMAGE_BYTES) { "图片处理后仍然过大，请换一张较小的图片" }
        return result
    }

    private fun cacheKey(attachment: AttachmentRef): String {
        val source = "${attachment.uri}\u0000${attachment.sizeBytes ?: -1}\u0000$MAX_MODEL_EDGE"
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun applyExifOrientation(bitmap: Bitmap, source: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun pruneImageCache(): StorageCleanupResult {
        val now = System.currentTimeMillis()
        var filesDeleted = 0
        var bytesFreed = 0L
        fun delete(file: File) {
            val size = file.length()
            if (file.delete()) {
                filesDeleted++
                bytesFreed += size
            }
        }
        imageCacheRoot.listFiles()?.filter(File::isFile)?.filter {
            it.name.contains(".tmp-") || now - it.lastModified() > IMAGE_CACHE_MAX_AGE_MILLIS
        }?.forEach(::delete)
        val files = imageCacheRoot.listFiles()?.filter(File::isFile)?.sortedBy(File::lastModified).orEmpty()
        var total = files.sumOf(File::length)
        files.forEach { file ->
            if (total <= IMAGE_CACHE_MAX_BYTES) return@forEach
            val length = file.length()
            val before = filesDeleted
            delete(file)
            if (filesDeleted > before) total -= length
        }
        return StorageCleanupResult(filesDeleted, bytesFreed)
    }

    private fun safeName(value: String, mimeType: String): String {
        val cleaned = value.filterNot { it == '/' || it == '\\' || it.code < 32 }.trim().take(100)
            .takeUnless { it == "." || it == ".." }.orEmpty()
        if (cleaned.isNotBlank() && '.' in cleaned) return cleaned
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty()
        val base = cleaned.ifBlank { "附件" }
        return if (extension.isBlank()) base else "$base.$extension"
    }

    private fun uniqueFile(directory: File, name: String): File {
        val direct = File(directory, name)
        if (!direct.exists()) return direct
        val stem = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 2
        while (true) {
            val candidate = File(directory, "$stem ($index)$suffix")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private data class Metadata(val name: String, val mimeType: String, val size: Long?)

    private companion object {
        const val MAX_SHARED_FILES = 10
        const val MAX_SHARED_TEXT_CHARS = 50_000
        const val MAX_IMAGE_INPUT_BYTES = 25L * 1024 * 1024
        const val MAX_TEXT_INPUT_BYTES = 5L * 1024 * 1024
        const val MAX_SHARED_TOTAL_BYTES = 60L * 1024 * 1024
        const val MAX_MODEL_IMAGE_BYTES = 5L * 1024 * 1024
        const val MAX_IMAGE_PIXELS = 100_000_000L
        const val MAX_MODEL_EDGE = 2_048
        const val IMAGE_CACHE_MAX_BYTES = 64L * 1024 * 1024
        const val IMAGE_CACHE_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}
