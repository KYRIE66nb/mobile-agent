package xyz.chouxuewei.mobile_agent.device.capture.projection

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主屏 MediaProjection 采集会话：ImageReader 单线程、只留最新帧。
 *
 * 契约：onFrame 回调在采集线程同步执行，回调内通过 [FrameAccess.sample] 读取
 * ROI 像素；回调返回后 Image 立即关闭——帧所有权不跨线程。
 * 调用方负责：先注册 MediaProjection stop 回调、持有 FGS 存活。
 */
class ProjectionCapture {

    interface FrameAccess {
        val width: Int
        val height: Int

        /** 在 [l,t,r,b] 像素矩形内按 grid×grid 网格取样，返回 ARGB 数组。 */
        fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray

        /** 整帧缩略图（仅校准预览用，内存对象不落盘）。 */
        fun snapshot(targetWidth: Int): android.graphics.Bitmap
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private val running = AtomicBoolean(false)

    /** 最新帧槽：慢消费时丢旧帧，不排队。 */
    @Volatile private var pending: Image? = null

    private var onFrame: ((FrameAccess) -> Unit)? = null
    private var onClosed: ((String) -> Unit)? = null

    fun start(
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        onFrame: (FrameAccess) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        check(!running.getAndSet(true)) { "capture already running" }
        this.projection = projection
        this.onFrame = onFrame
        this.onClosed = onClosed
        val t = HandlerThread("substitution-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        reader = newReader(width, height, h)
        display = projection.createVirtualDisplay(
            "substitution-timer", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, h,
        )
    }

    private fun newReader(width: Int, height: Int, h: Handler): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ ir ->
                val next = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                pending?.close() // 丢旧帧不排队
                pending = next
                drain()
            }, h)
        }

    private fun drain() {
        val image = pending ?: return
        pending = null
        val callback = onFrame ?: run { image.close(); return }
        val access = object : FrameAccess {
            override val width = image.width
            override val height = image.height

            override fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val left = l.coerceIn(0, image.width - 1)
                val right = r.coerceIn(left + 1, image.width)
                val top = t.coerceIn(0, image.height - 1)
                val bottom = b.coerceIn(top + 1, image.height)
                val w = right - left
                val hgt = bottom - top
                val gx = maxOf(1, minOf(grid, w))
                val gy = maxOf(1, minOf(grid, hgt))
                val out = IntArray(gx * gy)
                var i = 0
                for (y in 0 until gy) {
                    val rowOffset = (top + y * hgt / gy) * rowStride
                    for (x in 0 until gx) {
                        val o = rowOffset + (left + x * w / gx) * pixelStride
                        out[i++] = (0xFF shl 24) or
                            ((buffer.get(o).toInt() and 0xFF) shl 16) or
                            ((buffer.get(o + 1).toInt() and 0xFF) shl 8) or
                            (buffer.get(o + 2).toInt() and 0xFF)
                    }
                }
                return out
            }

            override fun snapshot(targetWidth: Int): android.graphics.Bitmap {
                val scale = targetWidth.toFloat() / image.width
                val outW = targetWidth.coerceAtMost(image.width)
                val outH = (image.height * scale).toInt().coerceAtLeast(1)
                val pixels = IntArray(outW * outH)
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                for (y in 0 until outH) {
                    val rowOffset = (y * image.height / outH) * rowStride
                    for (x in 0 until outW) {
                        val o = rowOffset + (x * image.width / outW) * pixelStride
                        pixels[y * outW + x] = (0xFF shl 24) or
                            ((buffer.get(o).toInt() and 0xFF) shl 16) or
                            ((buffer.get(o + 1).toInt() and 0xFF) shl 8) or
                            (buffer.get(o + 2).toInt() and 0xFF)
                    }
                }
                return android.graphics.Bitmap.createBitmap(pixels, outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }
        try {
            callback(access)
        } finally {
            image.close()
        }
        if (!running.get()) onClosed?.invoke("stopped")
    }

    /** 旋转/共享尺寸变化：重建 reader+surface 并 resize VirtualDisplay，不重复 createVirtualDisplay。 */
    fun resize(width: Int, height: Int, dpi: Int) {
        val vd = display ?: return
        val h = handler ?: return
        h.post {
            pending?.close(); pending = null
            reader?.close()
            reader = newReader(width, height, h)
            vd.surface = reader!!.surface
            vd.resize(width, height, dpi)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val h = handler
        if (h != null) {
            h.post {
                pending?.close(); pending = null
                display?.release(); display = null
                reader?.close(); reader = null
                projection?.stop(); projection = null
                onFrame = null; onClosed = null
                thread?.quitSafely(); thread = null; handler = null
            }
        } else {
            projection?.stop(); projection = null
        }
    }
}
