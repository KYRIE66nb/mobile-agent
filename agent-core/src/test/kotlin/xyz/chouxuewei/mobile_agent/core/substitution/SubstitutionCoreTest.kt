package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `lit bead mixed with dark surround still LIT by pixel ratio`() {
        // 12/49 亮豆像素（金）+ 其余暗背景：占比表决仍判 LIT——均值池化会稀释失败
        val pixels = IntArray(49) { i -> if (i < 12) 0xFFF0AA32.toInt() else 0xFF14141A.toInt() }
        val r = DotClassifier().classify(pixels, TimerSide.SELF)
        assertEquals(DotState.LIT, r.state)
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
        // 恢复后重建基线：计时保留（悬置候选在重置时按确认处理），无撤单事件
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 4, t) }
        assertTrue(events.none { it is TimerEvent.CandidateCancelled })
        assertTrue(e.timers(t).second.active)
        assertFalse(e.timers(t).second.pending)
    }

    @Test
    fun `prolonged invalid frames keep baseline so next drop still detects`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 非规范豆型/无效帧（KO 画面、转场、特效遮挡）不重置基线——
        // 稳定计数跨过噪声期继续持有（开源实现：非规范豆型不产任何变更）。
        var sawReset = false
        repeat(26) { t += 100; if (e.onFrame(4, null, t).any { it is TimerEvent.BaselineReset }) sawReset = true }
        assertFalse(sawReset)
        // 噪声期结束回到原计数：无事件
        repeat(3) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
        // 关键：之后真替身照常检测——噪声没丢基线
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertEquals(4, c.fromCount); assertEquals(3, c.toCount)
    }

    @Test
    fun `drop during noise window still detects when valid frames return`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 替身发生在满屏特效中：2.5s 全是非规范帧，特效散去时已剩 3 颗
        repeat(26) { t += 100; e.onFrame(4, null, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        // 基线未丢 → 4→3 差值正常产候选（等价于原盲窗补发，但无需特判）
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate })
        assertTrue(e.timers(t).second.active)
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
        // 升到 3 后驻留 2.6s（>1.2s 抖动窗）再掉回 2——涨豆后的真替身，要计
        repeat(26) { t += 100; e.onFrame(4, 3, t) }
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
        // 遮挡快速消失（<600ms）：计数回弹到 4 → 撤销计时
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 4, t) }
        assertTrue(events.any { it is TimerEvent.CandidateCancelled })
        assertFalse(e.timers(t).second.active)
    }

    @Test
    fun `slow rebound after drop is real regain and keeps the timer`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        // 真替身 4→3 → 悬置候选
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        assertTrue(e.timers(t).second.pending)
        // 800ms 后豆回复 4（>600ms 慢回弹 = 真实涨豆）→ 确认而非撤销
        repeat(8) { t += 100; e.onFrame(4, 3, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 4, t) }
        assertTrue(events.any { it is TimerEvent.CandidateConfirmed })
        assertTrue(events.none { it is TimerEvent.CandidateCancelled })
        assertTrue(e.timers(t).second.active)
        assertFalse(e.timers(t).second.pending)
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

    // ---- 盲窗重建检测（真机复盘：挨揍特效 → 帧无效 → 基线重置 → 替身被吞） ----

    @Test
    fun `blind window net drop by one still emits substitution candidate`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        // 满屏特效 → 连续 >2s 无效帧 → 基线作废
        repeat(26) { t += 100; e.onFrame(4, null, t) }
        // 特效散去豆数 4→3：盲窗内发生了替身——补发候选（仍走悬置验证）
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertEquals(4, c.fromCount); assertEquals(3, c.toCount)
        assertTrue(e.timers(t).second.pending)
    }

    @Test
    fun `manual reset re-arms detection so next substitution still fires`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 悬置候选 4→3
        assertTrue(e.timers(t).second.pending)
        // 服务手动重置路径：先清计时再重建基线
        e.cancelTimer(TimerSide.ENEMY)
        e.resetBaseline("manual")
        assertFalse(e.timers(t).second.active)
        // 重建基线 4 → 下一个真替身照常计
        repeat(3) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate })
        assertTrue(e.timers(t).second.active)
    }

    @Test
    fun `consecutive substitutions on the same side each fire`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 第一次替身 4→3
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate })
        // 验证窗过后确认
        repeat(20) { t += 100; e.onFrame(4, 3, t) }
        assertTrue(e.timers(t).second.active && !e.timers(t).second.pending)
        // 涨豆回 4（等验证+涨豆动画时间），然后第二次替身 4→3
        repeat(30) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertEquals(4, c.fromCount); assertEquals(3, c.toCount)
        // 豆继续掉到 2（第三颗消耗）也能再计一次
        repeat(20) { t += 100; e.onFrame(4, 3, t) }
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate })
    }

    @Test
    fun `bead gain during blind window produces no candidate`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 3, t) }
        repeat(26) { t += 100; e.onFrame(4, null, t) }
        // 盲窗内涨豆 3→4：不产任何事件
        repeat(5) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
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
