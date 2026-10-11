package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun solid(r: Int, g: Int, b: Int): IntArray =
    IntArray(16) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

// 爆闪/遮挡夹具：全部颜色都落在点亮盒与熄灭盒之外（棕/绿杂色）
private fun noisy(): IntArray = IntArray(16) { i ->
    if (i % 2 == 0) (0xFF shl 24) or 0x963C50 else (0xFF shl 24) or 0x3C963C
}

class SubstitutionCoreTest {

    // ---- 逐像素颜色盒分类器 ----

    @Test
    fun `lit gold bead pixel classified LIT for self`() {
        // 亮橙金豆心纯色——满四颗时整排变金
        val r = DotClassifier().classify(solid(245, 170, 50), TimerSide.SELF)
        assertEquals(DotState.LIT, r.state)
        assertTrue(r.litRatio > 0.9f)
    }

    @Test
    fun `gold and cyan lit pixels both count as lit on either side`() {
        // 点亮色不分侧：非满=青蓝、满四=金色
        assertEquals(DotState.LIT, DotClassifier().classify(solid(245, 170, 50), TimerSide.ENEMY).state)
        assertEquals(DotState.LIT, DotClassifier().classify(solid(80, 180, 230), TimerSide.SELF).state)
    }

    @Test
    fun `lit cyan bead pixel classified LIT for enemy`() {
        // 实测：双方点亮豆同为亮青白
        val r = DotClassifier().classify(solid(120, 220, 245), TimerSide.ENEMY)
        assertEquals(DotState.LIT, r.state)
    }

    @Test
    fun `empty slot classified EMPTY`() {
        val r = DotClassifier().classify(solid(20, 20, 26), TimerSide.SELF)
        assertEquals(DotState.EMPTY, r.state)
    }

    @Test
    fun `near black pixels are skipped instead of voting EMPTY`() {
        // 黑屏转场像素（max 通道 ≤ blackFloor=10）不投熄灭票——整格判 UNKNOWN 而非"全灭豆槽"
        val r = DotClassifier().classify(solid(5, 5, 5), TimerSide.SELF)
        assertEquals(DotState.UNKNOWN, r.state)
        assertEquals(0f, r.dimRatio, 0.0001f)
    }

    @Test
    fun `lit bead mixed with dark surround still LIT by pixel ratio`() {
        // 30/49 亮豆像素（金）+ 19 暗背景：亮占比占优仍判 LIT——均值池化会稀释失败
        val pixels = IntArray(49) { i -> if (i < 30) 0xFFF0AA32.toInt() else 0xFF14141A.toInt() }
        val r = DotClassifier().classify(pixels, TimerSide.SELF)
        assertEquals(DotState.LIT, r.state)
    }

    @Test
    fun `dim-dominant borderline cell classified EMPTY not LIT`() {
        // 真机回归：lit 0.12/dim 0.47 的边界豆曾先撞亮阈值被误判 LIT，
        // 导致豆数 3↔2 狂抖——必须 dim 占优才 EMPTY，亮暗须分胜负
        val pixels = IntArray(49) { i -> if (i < 6) 0xFFF0AA32.toInt() else 0xFF14141A.toInt() }
        val r = DotClassifier().classify(pixels, TimerSide.SELF)
        assertEquals(DotState.EMPTY, r.state)
    }

    @Test
    fun `flashy or occluded sample classified UNKNOWN`() {
        val r = DotClassifier().classify(noisy(), TimerSide.SELF)
        assertEquals(DotState.UNKNOWN, r.state)
    }

    @Test
    fun `color conversion matches known values`() {
        val (h, s, v) = DotSample.rgbToHsv(1f, 0f, 0f)
        assertEquals(0f, h); assertEquals(1f, s); assertEquals(1f, v)
        val grey = DotSample.rgbToHsv(0.5f, 0.5f, 0.5f)
        assertEquals(0f, grey[1], 0.001f)
    }

    // ---- 布局映射 ----

    @Test
    fun `layout maps normalized rects into content region with letterbox`() {
        val layout = TimerLayout(
            contentRect = NormalizedRect(0.1f, 0f, 0.9f, 1f),
            selfDots = NormalizedRect(0f, 0f, 0.5f, 0.25f),
            dotsPerSide = 4,
        )
        // 帧 2000x1000，内容区去掉左右黑边 → 1800x1000 偏移 x=200
        // 帧 2000x1000，内容区去左右黑边 → 1600x1000 偏移 x=200；豆槽宽 0.5*1600=800，每格 200
        val cells = layout.dotCells(TimerSide.SELF, 2000, 1000)
        assertEquals(4, cells.size)
        assertEquals(200, cells[0][0])
        assertEquals(400, cells[1][0])
        assertEquals(250, cells[0][3])
    }

    @Test
    fun `each side maps its own rect`() {
        val layout = TimerLayout(
            selfDots = NormalizedRect(0f, 0f, 0.2f, 0.1f),
            enemyDots = NormalizedRect(0.8f, 0f, 1f, 0.1f),
            dotsPerSide = 4,
        )
        val self = layout.dotCells(TimerSide.SELF, 1000, 1000)
        val enemy = layout.dotCells(TimerSide.ENEMY, 1000, 1000)
        assertTrue(self[0][0] < enemy[0][0])
    }

    // ---- HUD 门控 ----

    @Test
    fun `hud gate unverified when no anchors configured`() {
        assertEquals(SceneVerdict.UNVERIFIED, HudGate(emptyList()).verdict(emptyList()))
    }

    @Test
    fun `scene evaluation requires anchors and defers to hud gate`() {
        // 锚点未标定 → 恒 UNVERIFIED（双零弱启发式已删，不做任何否决；
        // 转场整排消失由引擎 collapse 规则兜底）
        assertEquals(SceneVerdict.UNVERIFIED, evaluateScene(emptyList(), emptyList()))
        // 锚点已标定：命中 → BATTLE；未命中 → NOT_BATTLE
        val blood = RgbBox(0, 120, 0, 80, 255, 80)
        val anchors = listOf(HudAnchor(NormalizedRect(0f, 0f, 0.4f, 0.05f), blood, 0.5f))
        val green = IntArray(16) { 0xFF28C84B.toInt() }
        assertEquals(SceneVerdict.BATTLE, evaluateScene(anchors, listOf(green)))
        val dark = IntArray(16) { 0xFF101014.toInt() }
        assertEquals(SceneVerdict.NOT_BATTLE, evaluateScene(anchors, listOf(dark)))
    }

    @Test
    fun `hud gate requires anchor hits independent of dot color`() {
        // 血条绿色盒——与豆点亮色完全不同的证据源
        val blood = RgbBox(0, 120, 0, 80, 255, 80)
        val anchors = listOf(
            HudAnchor(NormalizedRect(0f, 0f, 0.4f, 0.05f), blood, 0.5f),
            HudAnchor(NormalizedRect(0.6f, 0f, 1f, 0.05f), blood, 0.5f),
        )
        val gate = HudGate(anchors)
        val green = IntArray(16) { 0xFF28C84B.toInt() }
        val dark = IntArray(16) { 0xFF101014.toInt() }
        assertEquals(SceneVerdict.BATTLE, gate.verdict(listOf(green, green)))
        assertEquals(SceneVerdict.NOT_BATTLE, gate.verdict(listOf(green, dark)))
        assertEquals(SceneVerdict.NOT_BATTLE, gate.verdict(listOf(dark, dark)))
    }

    @Test
    fun `deriveAnchorBox covers uniform color with tolerance`() {
        // 均匀色块：P10=P90=原色 → 盒 = 原色 ± tolerance 外扩且仍包含原色
        val px = IntArray(64) { 0xFF28C84B.toInt() }
        val box = deriveAnchorBox(px)
        assertTrue(box.contains(0xFF28C84B.toInt()))
        assertEquals(0x28 - 30, box.minR); assertEquals(0x28 + 30, box.maxR)
        assertEquals(0xC8 - 30, box.minG); assertEquals(0xC8 + 30, box.maxG)
        assertEquals(0x4B - 30, box.minB); assertEquals(0x4B + 30, box.maxB)
    }
}

class TimerStateMachineTest {

    @Test
    fun `happy path app open to monitoring`() {
        val sm = TimerStateMachine()
        assertEquals(TimerServiceState.DISABLED, sm.current)
        assertEquals(TimerServiceState.NEEDS_PERMISSION, sm.transition(LaunchEvent.AppOpened))
        assertEquals(TimerServiceState.NEEDS_CALIBRATION, sm.transition(LaunchEvent.PermissionGranted))
        assertEquals(TimerServiceState.WAITING_FOR_GAME, sm.transition(LaunchEvent.Calibrated))
        assertEquals(TimerServiceState.MONITORING, sm.transition(LaunchEvent.GameForeground))
        assertEquals(TimerServiceState.WAITING_FOR_GAME, sm.transition(LaunchEvent.GameLeft))
    }

    @Test
    fun `denied permission waits without looping`() {
        val sm = TimerStateMachine()
        sm.transition(LaunchEvent.AppOpened)
        assertEquals(TimerServiceState.NEEDS_PERMISSION, sm.transition(LaunchEvent.PermissionDenied))
        assertEquals(TimerServiceState.NEEDS_PERMISSION, sm.current)
    }

    @Test
    fun `stop stays disabled until explicit reopen`() {
        val sm = TimerStateMachine()
        listOf(LaunchEvent.AppOpened, LaunchEvent.PermissionGranted,
            LaunchEvent.Calibrated, LaunchEvent.GameForeground).forEach { sm.transition(it) }
        assertEquals(TimerServiceState.DISABLED, sm.transition(LaunchEvent.Stop))
        // 停止后 GameForeground 不会复活
        assertEquals(TimerServiceState.DISABLED, sm.current)
        assertEquals(TimerServiceState.NEEDS_PERMISSION, sm.transition(LaunchEvent.AppOpened))
    }

    @Test
    fun `session lost during monitoring requires new consent`() {
        val sm = TimerStateMachine()
        listOf(LaunchEvent.AppOpened, LaunchEvent.PermissionGranted,
            LaunchEvent.Calibrated, LaunchEvent.GameForeground).forEach { sm.transition(it) }
        assertEquals(TimerServiceState.NEEDS_PERMISSION, sm.transition(LaunchEvent.SessionLost))
    }
}
