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
    fun `active cooldown second drop flagged conflict but timer untouched`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        // 悬置候选期内的再次掉豆会被视为级联消耗——先等验证窗结束确认
        repeat(30) { t += 100; e.onFrame(4, 3, t) }
        val acceptedEnd = e.timers(t).second.endAtMs
        assertFalse(e.timers(t).second.pending)
        // 已确认计时仍在冷却前半段 → 第二次掉豆标记 conflict，但不得覆盖正式计时
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertTrue(c.conflict)
        assertEquals(acceptedEnd, e.timers(t).second.endAtMs)
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
    fun `pause keeps accepted countdown, resume rebuilds baseline`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        // 先让候选完成验证成为已接受计时
        repeat(16) { t += 100; e.onFrame(4, 3, t) }
        val endAt = e.timers(t).second.endAtMs
        assertTrue(e.timers(t).second.active && !e.timers(t).second.pending)
        e.pause()
        repeat(5) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
        // 暂停期间倒计时仍按绝对截止计算
        assertEquals(endAt - t, e.timers(t).second.remaining(t))
        e.resume()
        // 恢复后重建基线：已接受计时保留，无撤单事件
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 4, t) }
        assertTrue(events.none { it is TimerEvent.CandidateCancelled })
        assertTrue(e.timers(t).second.active)
        assertEquals(endAt, e.timers(t).second.endAtMs)
    }

    @Test
    fun `prolonged invalid frames reset baseline and rebuild cleanly`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 无效帧超过 invalidBaselineTimeoutMs(默认2s) → 旧基线作废
        var sawReset = false
        repeat(26) { t += 100; if (e.onFrame(4, null, t).any { it is TimerEvent.BaselineReset }) sawReset = true }
        assertTrue(sawReset)
        // 无效期结束回到原计数：静默重建基线，无事件
        repeat(3) { t += 100; assertTrue(e.onFrame(4, 4, t).isEmpty()) }
        // 新基线建立后真实替身照常检测
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertEquals(4, c.fromCount); assertEquals(3, c.toCount)
    }

    @Test
    fun `drop inside blind window is ambiguous not a precise cooldown`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 替身可能发生在满屏特效中：>2s 全是非规范帧，旧基线已作废
        repeat(26) { t += 100; e.onFrame(4, null, t) }
        // 特效散去豆数 4→3：盲窗内疑似消耗只报歧义（触发时刻不可知），
        // 不得补发"恢复画面时刻起算"的精确满额冷却
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        assertTrue(events.any { it is TimerEvent.AmbiguousDrop })
        assertTrue(events.none { it is TimerEvent.SubstitutionCandidate })
        assertFalse(e.timers(t).second.active)
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

    // ---- 盲窗处理：短盲窗保留上下文，长盲窗使旧基线失效 ----

    @Test
    fun `short blind window keeps baseline and later drop still detects`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        // 短暂特效/遮挡 1s（< invalidBaselineTimeoutMs）→ 基线保留
        repeat(10) { t += 100; e.onFrame(4, null, t) }
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

    // ---- 候选与正式计时分离（真机复盘：新候选覆盖并连坐清空正式计时） ----

    @Test
    fun `rejected candidate preserves existing cooldown`() {
        val e = SubstitutionEngine(TimerConfig(), clock = { 0L })

        fun feed(count: Int, start: Long) {
            repeat(3) { i ->
                e.onFrame(4, count, start + i * 100L)
            }
        }

        feed(4, 100)
        feed(3, 400)
        e.onFrame(4, 3, 2200)
        val originalEnd = e.timers(2200).second.endAtMs

        feed(2, 3000)
        feed(3, 3300)

        val actual = e.timers(3500).second
        assertTrue(actual.active)
        assertEquals(originalEnd, actual.endAtMs)
    }

    @Test
    fun `drop during second half of cooldown does not overwrite timer`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 候选 4→3
        repeat(16) { t += 100; e.onFrame(4, 3, t) } // 验证窗后确认 → endAt=15400
        val endAt = e.timers(t).second.endAtMs
        assertTrue(e.timers(t).second.active)
        // 帧持续推进到冷却后半段（remaining < 50%）再掉豆
        repeat(68) { t += 100; e.onFrame(4, 3, t) } // t≈9000
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        assertTrue(events.any { (it as? TimerEvent.SubstitutionCandidate)?.conflict == true })
        // 后半段的冲突掉豆同样不得重启/延长正式计时
        assertEquals(endAt, e.timers(t).second.endAtMs)
        // 冲突候选持续存在也不替代原计时
        repeat(20) { t += 100; e.onFrame(4, 2, t) }
        assertEquals(endAt, e.timers(t).second.endAtMs)
        assertTrue(e.timers(t).second.remaining(t) > 0)
    }

    @Test
    fun `candidate accepts next substitution after cooldown expiry`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) }
        repeat(16) { t += 100; e.onFrame(4, 3, t) } // 确认 → endAt=15400
        // 帧持续到达并跨过冷却到期（remaining=0 但 active 标志仍为 true——不能凭标志判定冷却中）
        repeat(140) { t += 100; e.onFrame(4, 3, t) } // t≈16200 > 15400
        var events: List<TimerEvent> = emptyList()
        val dropAt = t + 100 // 掉豆首见帧
        repeat(3) { t += 100; events = e.onFrame(4, 2, t) }
        val c = events.filterIsInstance<TimerEvent.SubstitutionCandidate>().single()
        assertFalse(c.conflict) // 已到期 → 不是冲突候选
        // 验证期保持低位 → 确认后成为新的正式计时
        repeat(16) { t += 100; e.onFrame(4, 2, t) }
        val timer = e.timers(t).second
        assertTrue(timer.active)
        assertFalse(timer.pending)
        assertEquals(dropAt + 15_000, timer.endAtMs)
    }

    // ---- 证据门控：没有新的有效观察不能靠等待确认候选 ----

    @Test
    fun `candidate never confirms on unknown frames alone`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 悬置候选，验证窗 1.5s
        assertTrue(e.timers(t).second.pending)
        // 之后全是 UNKNOWN：纯时间流逝不得把候选变成已确认
        var confirmed = false
        var cancelled = false
        repeat(30) { t += 100
            for (ev in e.onFrame(4, null, t)) {
                if (ev is TimerEvent.CandidateConfirmed) confirmed = true
                if (ev is TimerEvent.CandidateCancelled) cancelled = true
            }
        }
        assertFalse(confirmed)
        // 长盲窗后候选被撤销（不可验证）而非升级，不留正式计时
        assertTrue(cancelled)
        assertFalse(e.timers(t).second.active)
    }

    @Test
    fun `resume does not promote pending candidate`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 悬置候选
        assertTrue(e.timers(t).second.pending)
        e.pause()
        t += 500
        e.resume()
        // 恢复不得把未验证候选隐式升级为已接受计时
        assertFalse(e.diagnostics().any { it.contains("CandidateConfirmed") })
        val timer = e.timers(t).second
        assertFalse(timer.pending)
        assertFalse(timer.active)
    }

    @Test
    fun `cancelled then reset leaves no residual state`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(20) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 悬置候选
        assertTrue(e.timers(t).second.pending)
        e.cancelTimer(TimerSide.ENEMY)
        // 手动撤销必须同时清掉候选——不能只清正式计时让候选"复活"
        assertFalse(e.timers(t).second.pending)
        assertFalse(e.timers(t).second.active)
        e.resetBaseline("manual")
        // 重建基线后新替身照常计（dedupe/候选无残留）
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate })
        assertTrue(e.timers(t).second.pending)
    }

    // ---- 观察窗口的时间连续性 ----

    @Test
    fun `sparse observations do not form a stable window`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) } // 基线 4
        // 三次相隔 5s 的相同读数不是"连续稳定"——不得产候选
        var fired = false
        repeat(3) { t += 5_000; if (e.onFrame(4, 3, t).any { it is TimerEvent.SubstitutionCandidate }) fired = true }
        assertFalse(fired)
    }

    @Test
    fun `stale and replayed frames are ignored`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) } // 基线 4，lastFrame=300
        // 同时间戳重放三帧不得凑成连续稳定
        repeat(3) { assertTrue(e.onFrame(4, 3, 400).isEmpty()) }
        // 倒序旧帧不得触发
        assertTrue(e.onFrame(4, 2, 200).isEmpty())
        assertFalse(e.timers(500).second.active)
        // 之后正常帧仍可用
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t) }
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate })
    }

    // ---- 场景门控：非战斗画面不参与替身判定 ----

    @Test
    fun `non battle scene frames produce no candidates`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        // 加载/菜单画面里豆槽"读数"跳变不得产候选
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(4, 3, t, battle = false) }
        assertTrue(events.isEmpty())
        assertFalse(e.timers(t).second.active)
    }

    @Test
    fun `non battle scene does not confirm pending candidate`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        repeat(3) { t += 100; e.onFrame(4, 3, t) } // 悬置候选
        var confirmed = false
        repeat(30) { t += 100; if (e.onFrame(4, 3, t, battle = false).any { it is TimerEvent.CandidateConfirmed }) confirmed = true }
        assertFalse(confirmed)
        assertFalse(e.timers(t).second.active)
    }

    // ---- HUD 门控 ----

    @Test
    fun `hud gate unverified when no anchors configured`() {
        assertEquals(SceneVerdict.UNVERIFIED, HudGate(emptyList()).verdict(emptyList()))
    }

    @Test
    fun `scene evaluation falls back to weak signal only without anchors`() {
        // 锚点未标定：双零→非战斗兜底；单侧有效→未验证（不是"豆数算得出=HUD 有效"）
        assertEquals(SceneVerdict.NOT_BATTLE, evaluateScene(emptyList(), emptyList(), 0, 0))
        assertEquals(SceneVerdict.UNVERIFIED, evaluateScene(emptyList(), emptyList(), 4, null))
        // 锚点已标定：真实 HUD 证据下双零是合法观察，不再被门控吞掉
        val blood = RgbBox(0, 120, 0, 80, 255, 80)
        val anchors = listOf(HudAnchor(NormalizedRect(0f, 0f, 0.4f, 0.05f), blood, 0.5f))
        val green = IntArray(16) { 0xFF28C84B.toInt() }
        assertEquals(SceneVerdict.BATTLE, evaluateScene(anchors, listOf(green), 0, 0))
        val dark = IntArray(16) { 0xFF101014.toInt() }
        assertEquals(SceneVerdict.NOT_BATTLE, evaluateScene(anchors, listOf(dark), 4, 4))
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

    // ---- 诊断回放闭环：记录→读取→回放→结果比较 ----

    @Test
    fun `recorded observations replay to identical events`() {
        // 构造一段观察序列（合成样本）：基线→替身→确认→盲窗→恢复
        val records = buildList {
            var t = 0L; var seq = 0
            fun rec(self: Int?, enemy: Int?, dt: Long, battle: Boolean = true): ObservationRecord {
                t += dt
                return ObservationRecord(
                    seq = seq++, atMs = t, clock = "test", channel = "replay",
                    geoW = 1920, geoH = 1080, scene = "BATTLE", battle = battle,
                    selfCount = self, enemyCount = enemy,
                )
            }
            repeat(3) { add(rec(4, 4, 100)) }
            repeat(3) { add(rec(4, 3, 100)) }
            repeat(20) { add(rec(4, 3, 100)) }
            repeat(30) { add(rec(null, null, 100)) }  // 长盲窗
            repeat(3) { add(rec(4, 2, 100)) }          // 盲窗恢复净掉豆 → 歧义
            repeat(3) { add(rec(4, 3, 100, battle = false)) } // 非战斗画面
        }
        // 序列化→读取闭环：解码后与原始记录一致
        val parsed = records.map(DiagJson::encode).map(DiagJson::decode)
        assertEquals(records, parsed)
        // 回放比较：同一批观察与实时送入产出相同事件序列
        val live = SubstitutionEngine(TimerConfig(), clock = { 0L })
        val expected = records.map { live.onFrame(it.selfCount, it.enemyCount, it.atMs, it.battle) }
        val replayed = SubstitutionEngine(TimerConfig(), clock = { 0L }).replay(parsed)
        assertEquals(expected, replayed)
        val flat = replayed.flatten()
        assertTrue(flat.any { it is TimerEvent.SubstitutionCandidate })
        assertTrue(flat.any { it is TimerEvent.CandidateConfirmed })
        assertTrue(flat.any { it is TimerEvent.AmbiguousDrop })
        assertTrue(flat.none { it is TimerEvent.CandidateCancelled && it.reason == "window_end" })
    }

    // ---- 双侧独立 ----

    @Test
    fun `unknown frames on one side never block the other`() {
        var t = 0L
        val e = engine(now = { t })
        repeat(3) { t += 100; e.onFrame(4, 4, t) }
        var events: List<TimerEvent> = emptyList()
        repeat(3) { t += 100; events = e.onFrame(null, 3, t) }
        assertTrue(events.any { it is TimerEvent.SubstitutionCandidate && it.side == TimerSide.ENEMY })
        assertTrue(e.timers(t).second.pending)
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
