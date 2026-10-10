package xyz.chouxuewei.mobile_agent.substitution

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

/**
 * 假帧源：由调用方指定每格像素与边界。
 * sample/crop/snapshot 的 Android 依赖在本地测试不调用——
 * contains 是纯边界判定，sample 返回调用方注入的像素。
 */
private class FakeAccess(
    private val pixels: Map<Int, IntArray>,
    private val bounds: IntArray = intArrayOf(0, 0, 1920, 1080),
    override val width: Int = 1920,
    override val height: Int = 1080,
) : FrameAccess {
    override fun contains(l: Int, t: Int, r: Int, b: Int): Boolean =
        l >= bounds[0] && t >= bounds[1] && r <= bounds[2] && b <= bounds[3] && r > l && b > t

    override fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray =
        if (!contains(l, t, r, b)) IntArray(0)
        else pixels[(l / 100) * 100] // 采样取格子内缩区——按 100px 格桶定位所属豆槽
            ?: IntArray(grid * grid) { 0xFF14141A.toInt() } // 默认暗背景

    override fun crop(l: Int, t: Int, r: Int, b: Int): Bitmap? = null
    override fun snapshot(targetWidth: Int): Bitmap = throw UnsupportedOperationException()
}

private fun solidArgb(rgb: Int, n: Int = 49) = IntArray(n) { rgb }

class DotRowCountingTest {

    private fun cells(count: Int = 4, x0: Int = 100, w: Int = 100): List<IntArray> =
        List(count) { i -> intArrayOf(x0 + w * i, 100, x0 + w * (i + 1), 140) }

    private val lit = solidArgb(0xFFF0AA32.toInt()) // 金色亮豆
    private val dark = solidArgb(0xFF14141A.toInt()) // 暗色空槽

    @Test
    fun `contiguous lit cells anchored left count correctly`() {
        val cs = cells()
        val px = mapOf(100 to lit, 200 to lit, 300 to lit, 400 to dark)
        val n = DotRowCounting.count(
            FakeAccess(px), cs, DotClassifier(), TimerSide.SELF, 7, 0.6f,
        )
        assertEquals(3, n)
    }

    @Test
    fun `contiguous lit cells anchored right count correctly`() {
        val cs = cells()
        // 右锚豆型：E L L L（实战分边翻转后出现）
        val px = mapOf(100 to dark, 200 to lit, 300 to lit, 400 to lit)
        val n = DotRowCounting.count(
            FakeAccess(px), cs, DotClassifier(), TimerSide.ENEMY, 7, 0.6f,
        )
        assertEquals(3, n)
    }

    @Test
    fun `hole shape is non canonical and returns null`() {
        val cs = cells()
        val px = mapOf(100 to lit, 200 to dark, 300 to lit, 400 to lit)
        assertNull(DotRowCounting.count(
            FakeAccess(px), cs, DotClassifier(), TimerSide.SELF, 7, 0.6f,
        ))
    }

    @Test
    fun `all dark cells count as zero not invalid`() {
        val cs = cells()
        val px = mapOf(100 to dark, 200 to dark, 300 to dark, 400 to dark)
        // 有效 HUD 下 0 豆是合法观察——不得当无效帧丢
        assertEquals(0, DotRowCounting.count(
            FakeAccess(px), cs, DotClassifier(), TimerSide.SELF, 7, 0.6f,
        ))
    }

    @Test
    fun `cell outside coverage makes whole side invalid`() {
        // 游戏窗口只占屏幕左半（无障碍通道），豆槽格子落在窗口外 → 整侧 null
        val access = FakeAccess(emptyMap(), bounds = intArrayOf(0, 0, 960, 1080))
        assertNull(DotRowCounting.count(
            access, cells(x0 = 700), DotClassifier(), TimerSide.SELF, 7, 0.6f,
        ))
    }
}
