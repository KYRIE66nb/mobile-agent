package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun solid(r: Int, g: Int, b: Int): IntArray =
    IntArray(16) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

private fun noisy(): IntArray = IntArray(16) { i ->
    if (i % 2 == 0) (0xFF shl 24) or 0xFFFFFF else (0xFF shl 24)
}

class SubstitutionCoreTest {

    // ---- DotSample / 分类器 ----

    @Test
    fun `lit dot classified LIT`() {
        // 高饱和亮金（豆点亮的典型色）
        val sample = DotSample.of(solid(240, 200, 40))
        val r = DotClassifier().classify(sample)
        assertEquals(DotState.LIT, r.state)
        assertTrue(r.confidence > 0.3f)
    }

    @Test
    fun `empty slot classified EMPTY`() {
        val r = DotClassifier().classify(DotSample.of(solid(20, 20, 26)))
        assertEquals(DotState.EMPTY, r.state)
    }

    @Test
    fun `flashy or occluded sample classified UNKNOWN`() {
        val r = DotClassifier().classify(DotSample.of(noisy()))
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

    // ---- 稳定计数与判定 ----

    private fun engine(
        cooldown: Long = 15_000,
        confirmFrames: Int = 3,
        now: () -> Long,
    ) = SubstitutionEngine(
        TimerConfig(tuning = DetectionTuning(confirmFrames = confirmFrames), cooldownMs = cooldown),
        clock = now,
    )

    @Test
    fun `first stable count only builds baseline, no candidate`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 140; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
        // 基线建立后再确认一轮仍无事件
        assertTrue(e.onFrame(4, 4, t + 140).isEmpty())
    }

    @Test
    fun `stable drop by one produces candidate and starts cooldown`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertEquals(TimerSide.ENEMY, c.side)
        assertEquals(4, c.fromCount); assertEquals(3, c.toCount)
        val (_, enemyTimer) = e.timers(t)
        assertTrue(enemyTimer.active)
        assertTrue(enemyTimer.suspected)
        assertEquals(15_000 - 0, enemyTimer.remaining(c.atMs))
    }

    @Test
    fun `same event not duplicated while count stays lower`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        repeat(5) { t += 100; assertTrue(e.onFrame(4, 3, t).isEmpty()) }
    }

    @Test
    fun `count increase never triggers`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(3, 4, t) }
        repeat(3) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
    }

    @Test
    fun `multi-dot drop is ambiguous and does not start timer`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        assertTrue(events.single() is TimerEvent.AmbiguousDrop)
        assertFalse(e.timers(t).second.active)
    }

    @Test
    fun `unstable flicker frames do not confirm`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 交替 4/3/null —— 永不连续 3 帧
        var fired = false
        repeat(9) { i ->
            t += 100
            val ev = e.onFrame(4, if (i % 3 == 0) null else if (i % 2 == 0) 3 else 4, t)
            if (ev.any { it is TimerEvent.SubstitutionCandidate }) fired = true
        }
        assertFalse(fired)
    }

    @Test
    fun `both sides trigger independently`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(3, 3, t) }
        val sides = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().map { it.side }
        assertEquals(setOf(TimerSide.SELF, TimerSide.ENEMY), sides.toSet())
    }

    @Test
    fun `active cooldown second drop flagged conflict`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        // 悬置候选期内的再次掉豆会被视为级联消耗——先等验证窗(2500ms)结束确认
        repeat(30) { t += 100; e.onFrame(4, 3, t) }
        assertFalse(e.timers(t).second.pending)
        // 已确认计时仍在冷却前半段 → 第二次掉豆标记 conflict
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertTrue(c.conflict)
    }

    @Test
    fun `dedupe window suppresses immediate retrigger`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        // 豆恢复再立刻减少：恢复回弹会撤销悬置候选；再次掉落属高位短驻抖动（WobbleIgnored）
        repeat(3) { t += 50; e.onFrame(4, 4, t) }
        var second: List<TimerEvent> = emptyList()
        repeat(3) { t += 50; second = e.onFrame(4, 3, t) }
        assertTrue(second.none { it is TimerEvent.SubstitutionCandidate })
    }

    @Test
    fun `cooldown math is monotonic-clock based`() {
        var t = 10_000L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 掉豆首见在 t=10400，确认于 10600——endAt 锚定首见时刻
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        val timer = e.timers(t).second
        assertEquals(15_000 - 200, timer.remaining(t))
        t += 9_000
        assertEquals(5_800, timer.remaining(t))
        t += 7_000
        assertEquals(0, timer.remaining(t))
    }

    @Test
    fun `pause keeps countdown, resume rebuilds baseline`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        e.pause()
        val anchorAt = t - 200 // 掉豆首见帧（确认帧前 200ms）
        repeat(5) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
        // 暂停期间倒计时仍按绝对截止计算
        assertEquals(15_000 - (t - anchorAt), e.timers(t).second.remaining(t))
        e.resume()
        // 恢复后首组稳定只重建基线：4→4 无事件
        repeat(3) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
    }

    @Test
    fun `invalid frames beyond timeout reset baseline`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 2.5s 无效帧 → 基线作废；之后回到 4 也只是新基线
        var sawReset = false
        repeat(26) { t += 100; if (e.onFrame(4, null, t).any { it is TimerEvent.BaselineReset }) sawReset = true }
        assertTrue(sawReset)
        repeat(3) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
    }

    @Test
    fun `irregular frame intervals still dedupe by time not count`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 37; e.onFrame(4, 4, t) }
        t += 900 // 掉帧间隙
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 37; events = e.onFrame(4, 3, t) }
        assertEquals(1, events.filterIsInstance<TimerEvent.SubstitutionCandidate>().size)
    }

    // ---- 误报抑制（真机复盘：闪光误点亮/遮挡回弹/级联消耗） ----

    @Test
    fun `brief elevation then return to prior stable is wobble not substitution`() {
        var t = 0L
        val e = engine(now = { t })
        // 2 颗豆长期稳定（>minStableBeforeDrop 阈值建立"长期稳定"印象）
        repeat(20) { t += 100; e.onFrame(4, 2, t) }
        // 闪光让某格误读为点亮：2→3 确认但只驻留 ~300ms
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        assertTrue(events.any { it is TimerEvent.WobbleIgnored })
        assertTrue(events.none { it is TimerEvent.SubstitutionCandidate })
        assertFalse(e.timers(t).second.active)
    }

    @Test
    fun `long held count then drop is still a real candidate`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 2, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        // 升到 3 后驻留 1.5s（>1.2s 阈值）再掉回 2——真替身，不是抖动
        repeat(15) { t += 100; e.onFrame(4, 3, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        assertEquals(1, events.filterIsInstance<TimerEvent.SubstitutionCandidate>().size)
    }

    @Test
    fun `occlusion drop that bounces back cancels pending timer`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        // 遮挡造成 4→3 确认 → 悬置计时已启动
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        assertTrue(e.timers(t).second.active)
        assertTrue(e.timers(t).second.pending)
        // 遮挡消失：计数回弹到 4（确认 3 帧）→ 撤销计时
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 4, t) }
        assertTrue(events.any { it is TimerEvent.CandidateCancelled })
        assertFalse(e.timers(t).second.active)
    }

    @Test
    fun `sustained drop confirms after verify window`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        assertTrue(e.timers(t).second.pending)
        // 保持 2.6s（>2500ms 验证窗）无回弹 → 确认
        var confirmed = false
        repeat(26) { t += 100; if (e.onFrame(4, 3, t).any { it is TimerEvent.CandidateConfirmed }) confirmed = true }
        assertTrue(confirmed)
        assertFalse(e.timers(t).second.pending)
        assertTrue(e.timers(t).second.active)
    }

    @Test
    fun `drop during pending verify is cascade not a second substitution`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 悬置候选 4→3
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) } // 悬置期又掉 → 级联
        assertTrue(events.any { it is TimerEvent.CandidateCancelled })
        assertTrue(events.any { it is TimerEvent.AmbiguousDrop })
        assertFalse(e.timers(t).second.active)
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
