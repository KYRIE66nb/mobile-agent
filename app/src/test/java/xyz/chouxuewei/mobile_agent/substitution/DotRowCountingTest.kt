package xyz.chouxuewei.mobile_agent.substitution

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

private class FakeAccess(
    private val pixels: Map<Int, IntArray>,
    private val bounds: IntArray = intArrayOf(0, 0, 1920, 1080),
    override val width: Int = 1920,
    override val height: Int = 1080,
) : FrameAccess {
    val sampledBuckets = mutableListOf<Int>()
    override fun contains(l: Int, t: Int, r: Int, b: Int): Boolean =
        l >= bounds[0] && t >= bounds[1] && r <= bounds[2] && b <= bounds[3] && r > l && b > t
    override fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray {
        if (!contains(l, t, r, b)) return IntArray(0)
        sampledBuckets += (l / 100) * 100
        return pixels[(l / 100) * 100] ?: IntArray(grid * grid) { 0xFF14141A.toInt() }
    }
    override fun crop(l: Int, t: Int, r: Int, b: Int): Bitmap? = null
    override fun snapshot(targetWidth: Int): Bitmap = throw UnsupportedOperationException()
}

class DotRowCountingTest {
    private fun cells(count: Int = 4, x0: Int = 100, w: Int = 100): List<IntArray> =
        List(count) { i -> intArrayOf(x0 + w * i, 100, x0 + w * (i + 1), 140) }

    private val lit = IntArray(49) { 0xFFF0AA32.toInt() }
    private val dark = IntArray(49) { 0xFF14141A.toInt() }
    private val L = DotState.LIT
    private val E = DotState.EMPTY
    private val U = DotState.UNKNOWN

    @Test fun `cells are read in physical left to right order`() {
        val px = mapOf(100 to lit, 200 to lit, 300 to lit, 400 to dark)
        assertEquals(listOf(L, L, L, E),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `right anchored row passes through verbatim`() {
        val px = mapOf(100 to dark, 200 to dark, 300 to lit, 400 to lit)
        assertEquals(listOf(E, E, L, L),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.ENEMY, 7, 0.6f))
    }

    @Test fun `hole shape is returned verbatim for the row tracker to judge`() {
        val px = mapOf(100 to lit, 200 to dark, 300 to lit, 400 to lit)
        assertEquals(listOf(L, E, L, L),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `all dark reads all empty`() {
        val px = mapOf(100 to dark, 200 to dark, 300 to dark, 400 to dark)
        assertEquals(listOf(E, E, E, E),
            DotRowCounting.readCells(FakeAccess(px), cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `cell outside coverage makes whole side invalid`() {
        val access = FakeAccess(emptyMap(), bounds = intArrayOf(0, 0, 960, 1080))
        assertNull(DotRowCounting.readCells(access, cells(x0 = 700), DotClassifier(), TimerSide.SELF, 7, 0.6f))
    }

    @Test fun `masked cell reads unknown without sampling`() {
        val access = FakeAccess(mapOf(100 to lit, 200 to lit, 300 to lit, 400 to dark))
        val got = DotRowCounting.readCells(
            access, cells(), DotClassifier(), TimerSide.SELF, 7, 0.6f,
            masked = { c -> c[0] == 200 },
        )
        assertEquals(listOf(L, U, L, E), got)
        assertTrue(200 !in access.sampledBuckets)
    }
}
