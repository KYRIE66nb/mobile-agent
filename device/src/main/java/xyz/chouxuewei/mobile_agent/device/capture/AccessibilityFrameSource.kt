package xyz.chouxuewei.mobile_agent.device.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.device.accessibility.AccessibilityWindowTarget
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService
import xyz.chouxuewei.mobile_agent.device.accessibility.AccessibilityScreenshotException

/**
 * 无障碍截屏帧源：与 MediaProjection 完全独立的两条通路，系统录屏抢占
 * 投影令牌时不受影响。用 takeScreenshotOfWindow 只截游戏窗口——悬浮层
 * （计时胶囊、录屏控制条）不会进入像素，反而比投影更干净。
 *
 * 帧率受系统截屏限速（约 3~10fps），足够豆槽检测但远低于投影——
 * 只做降级通道，不做主通道。
 */
class AccessibilityFrameSource(
    private val svc: AgentAccessibilityService,
    private val displayId: Int,
    private val gamePackage: String,
    private val screenW: Int,
    private val screenH: Int,
    private val onFrame: (FrameAccess) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            var failures = 0
            while (isActive) {
                val target = findGameWindow()
                if (target == null) { delay(400); continue }
                try {
                    val capture = svc.captureWindow(target)
                    val access = BitmapFrameAccess(capture.bitmap, capture.target.bounds, screenW, screenH)
                    try {
                        onFrame(access)
                    } catch (t: Throwable) {
                        android.util.Log.e(TAG, "frame callback threw, frame dropped", t)
                    } finally {
                        capture.bitmap.recycle()
                    }
                    failures = 0
                    delay(50)
                } catch (e: AccessibilityScreenshotException) {
                    // 截屏失败（窗口不可见/限速/后台）是常态，重试即可——
                    // 帧流饥饿的升级裁决归服务侧看门狗，这里绝不自裁会话
                    failures++
                    delay(if (e.isRateLimited) 320 else 600)
                } catch (t: Throwable) {
                    failures++
                    if (failures % 30 == 1) {
                        android.util.Log.e(TAG, "screenshot failed x$failures", t)
                    }
                    delay(600)
                }
            }
        }
    }

    fun stop() = scope.cancel()

    private fun findGameWindow(): AccessibilityWindowTarget? {
        val windows = svc.windowsOnAllDisplays.get(displayId).orEmpty()
        return try {
            windows.firstNotNullOfOrNull { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@firstNotNullOfOrNull null
                val root = window.root ?: return@firstNotNullOfOrNull null
                val pkg = try { root.packageName?.toString() } finally { root.recycle() }
                if (pkg != gamePackage) return@firstNotNullOfOrNull null
                val bounds = Rect().also(window::getBoundsInScreen)
                if (bounds.isEmpty) null else AccessibilityWindowTarget(window.id, bounds, pkg)
            }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private class BitmapFrameAccess(
        private val bitmap: Bitmap,
        private val bounds: Rect,
        private val screenW: Int,
        private val screenH: Int,
    ) : FrameAccess {
        override val width = screenW
        override val height = screenH

        override fun contains(l: Int, t: Int, r: Int, b: Int): Boolean =
            l >= bounds.left && t >= bounds.top && r <= bounds.right && b <= bounds.bottom &&
                r > l && b > t

        override fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray {
            // 越界 ROI 不得夹边当有效豆槽——返回空数组，分类器判 UNKNOWN 丢帧
            if (!contains(l, t, r, b)) return IntArray(0)
            val left = l - bounds.left
            val top = t - bounds.top
            val w = r - l
            val h = b - t
            val gx = maxOf(1, minOf(grid, w))
            val gy = maxOf(1, minOf(grid, h))
            val out = IntArray(gx * gy)
            var i = 0
            for (y in 0 until gy) {
                val row = top + y * h / gy
                for (x in 0 until gx) {
                    out[i++] = bitmap.getPixel(left + x * w / gx, row)
                }
            }
            return out
        }

        override fun crop(l: Int, t: Int, r: Int, b: Int): Bitmap? {
            if (!contains(l, t, r, b)) return null
            return Bitmap.createBitmap(bitmap, l - bounds.left, t - bounds.top, r - l, b - t)
        }

        override fun snapshot(targetWidth: Int): Bitmap {
            val outW = targetWidth.coerceAtMost(bitmap.width)
            val outH = (bitmap.height.toLong() * outW / bitmap.width).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, outW, outH, true)
        }
    }

    private companion object { const val TAG = "A11yFrameSource" }
}
