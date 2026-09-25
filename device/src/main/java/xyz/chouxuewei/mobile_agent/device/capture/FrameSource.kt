package xyz.chouxuewei.mobile_agent.device.capture

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import xyz.chouxuewei.mobile_agent.device.root.DisplayAdapter

/** Surface 在普通进程持有并通过 Binder 传给 Root；大图不经过 Binder。 */
internal class FrameSource : AutoCloseable {
    data class EncodedFrame(val revision: Long, val bytes: ByteArray)

    private val thread = HandlerThread("virtual-display-frames").apply { start() }
    private val reader = ImageReader.newInstance(
        DisplayAdapter.WIDTH, DisplayAdapter.HEIGHT, PixelFormat.RGBA_8888, 3
    )
    private var lastFrame = 0L
    @Volatile private var closed = false
    @Volatile private var latest: Bitmap? = null
    private var revision = 0L
    val surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            if (closed) return@setOnImageAvailableListener
            source.acquireLatestImage()?.use { frame ->
                val now = SystemClock.elapsedRealtime()
                // 仍持续释放每一帧，仅把预览转换限制为每秒两次，避免堆积图像。
                if (now - lastFrame < 500) return@use
                lastFrame = now
                val plane = frame.planes[0]
                val rowWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(rowWidth, frame.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, frame.width, frame.height)
                if (bitmap !== padded) padded.recycle()
                if (!closed) synchronized(this) {
                    latest?.recycle()
                    latest = bitmap
                    revision++
                } else bitmap.recycle()
            }
        }, Handler(thread.looper))
    }

    /**
     * 压缩发生在普通 App 进程，大图不会经过 Binder。
     * 界面截图是不透明画面，JPEG 在保留可读性的同时能显著减少发给模型的字节数。
     */
    fun latestJpeg(quality: Int): EncodedFrame? = synchronized(this) {
        latest?.let { bitmap ->
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { localizedText("虚拟屏画面编码失败", "Virtual display frame encoding failed.") }
                EncodedFrame(revision, output.toByteArray())
            }
        }
    }

    /** 本地轮询只需要变更标识，不为每个批量步骤重复编码截图。 */
    fun latestRevision(): Long = synchronized(this) { if (latest == null) 0L else revision }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(this) { latest?.recycle(); latest = null }
        reader.setOnImageAvailableListener(null, null)
        Handler(thread.looper).post { reader.close(); thread.quitSafely() }
    }
}
