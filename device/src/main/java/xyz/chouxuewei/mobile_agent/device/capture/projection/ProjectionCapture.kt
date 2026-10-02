package xyz.chouxuewei.mobile_agent.device.capture.projection

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

/**
 * 主屏 MediaProjection 采集会话：ImageReader 单线程、只留最新帧。
 *
 * 契约：onFrame 回调在采集线程同步执行，回调内通过 [FrameAccess.sample] 读取
 * ROI 像素；回调返回后 Image 立即关闭——帧所有权不跨线程。
 * 调用方负责：先注册 MediaProjection stop 回调、持有 FGS 存活。
 */
class ProjectionCapture {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private val running = AtomicBoolean(false)

    /** 最新帧槽：慢消费时丢旧帧，不排队。 */
    @Volatile private var pending: Image? = null

    private var onFrame: ((FrameAccess) -> Unit)? = null
    private var onClosed: ((String) -> Unit)? = null

    private companion object { const val TAG = "ProjectionCapture" }

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
        this.width = width; this.height = height; this.dpi = dpi
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
        } catch (t: Throwable) {
            // 帧回调异常不能杀采集线程——否则 ImageReader 缓冲区塞满后 VirtualDisplay
            // 停产且无任何报错，表现为"监视中但永不计数"。吞掉记日志，下一帧照常。
            android.util.Log.e(TAG, "frame callback threw, frame dropped", t)
        } finally {
            image.close()
        }
        if (!running.get()) onClosed?.invoke("stopped")
    }

    /** VirtualDisplay 被系统夺走（如第三方录屏抢投影）时原地重建：复用同一
     *  MediaProjection 令牌，reader+VD 全部换新；重建失败走 onClosed。 */
    fun restart() {
        if (!running.get()) return
        val p = projection ?: return
        val h = handler ?: return
        h.post {
            pending?.close(); pending = null
            display?.release(); display = null
            reader?.close(); reader = null
            try {
                reader = newReader(width, height, h)
                display = p.createVirtualDisplay(
                    "substitution-timer", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, h,
                )
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "capture restart failed", t)
                onClosed?.invoke("restart_failed:${t.message}")
            }
        }
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
