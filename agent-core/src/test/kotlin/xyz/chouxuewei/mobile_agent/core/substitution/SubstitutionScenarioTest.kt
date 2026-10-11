package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 替身计时行为规格：冷却先验 + 首次目击锚点 + 逐格记忆 + 短窗证伪回滚。
 * 每条用例对应 §7.2 判定表的一行或一条不变式。
 */
class SubstitutionScenarioTest {
    private val L = DotState.LIT
    private val E = DotState.EMPTY
    private val U = DotState.UNKNOWN

    /** 我方豆形：左锚 [L×n, E×(4−n)]。 */
    private fun c(n: Int): List<DotState> = List(4) { if (it < n) L else E }
    /** 敌方豆形：右锚 [E×(4−n), L×n]——喂 c(n) 会成洞形被 BeadRowTracker 判不可读。 */
    private fun e(n: Int): List<DotState> = List(4) { if (it >= 4 - n) L else E }
    private fun cells(vararg s: DotState): List<DotState> = s.toList()

    private fun engine(
        cooldownMs: Long = 15_000,
        tuning: DetectionTuning = DetectionTuning(),
        carried: Map<TimerSide, SideTimer> = emptyMap(),
    ) = SubstitutionEngine(
        TimerConfig(cooldownMs = cooldownMs, tuning = tuning),
        clock = { 0L },   // 帧时间全部由测试喂入，clock 只用于 timers()/事件戳兜底
        carried = carried,
    )

    private fun feed(
        eng: SubstitutionEngine,
        t: Long,
        self: List<DotState>?,
        enemy: List<DotState>?,
        battle: Boolean = true,
    ) = eng.onCells(self, enemy, t, battle)

    private fun ev(r: SubstitutionEngine.FrameResult): List<TimerEvent> = r.events
    private fun timerOf(eng: SubstitutionEngine, side: TimerSide, now: Long = 0) =
        (if (side == TimerSide.SELF) eng.timers(now).first else eng.timers(now).second)
            .takeIf { it.active }

    /**
     * 让 side 完成一次 4→3 掉落确认（锚点=firstSeenAt）。
     * 注意：调用前须保证旧计数最近刚被读到（gap ≤ uncertainGapMs 才有确定锚点），
     * 且帧时刻必须严格递增（stale 帧会被引擎丢弃）。
     */
    private fun substitute(eng: SubstitutionEngine, side: TimerSide, firstSeenAt: Long) {
        val s = if (side == TimerSide.SELF) c(3) to null else null to e(3)
        feed(eng, firstSeenAt, s.first, s.second)
        feed(eng, firstSeenAt + 140, s.first, s.second)
    }

    private fun baseline4(eng: SubstitutionEngine) {
        feed(eng, 0, c(4), e(4))
        feed(eng, 140, c(4), e(4))
    }

    // ---------- A. CountConfirmer（7） ----------

    @Test fun `two readable sightings confirm even with unreadable frames in between`() {
        val cf = CountConfirmer(2)
        assertNull(cf.update(3, 0))
        assertNull(cf.update(null, 140))
        val t = cf.update(3, 280)
        assertNotNull(t); assertEquals(3, t!!.to)
    }

    @Test fun `sparse sightings still confirm`() {
        val cf = CountConfirmer(2)
        assertNull(cf.update(3, 0))
        assertNotNull(cf.update(3, 5_000))
    }

    @Test fun `changing counts do not confirm`() {
        val cf = CountConfirmer(2)
        assertNull(cf.update(2, 0)); assertNull(cf.update(3, 140))
        assertNull(cf.update(2, 280)); assertNull(cf.update(3, 420))
        assertNotNull(cf.update(3, 560))
    }

    @Test fun `confirm produces a transition with first and last sight`() {
        val cf = CountConfirmer(2)
        cf.update(4, 0); cf.update(4, 140)
        cf.update(3, 1000)
        val t = cf.update(3, 1140)!!
        assertEquals(4, t.from); assertEquals(3, t.to)
        assertEquals(1000L, t.firstSeenAtMs); assertEquals(140L, t.lastOldSeenAtMs)
        assertEquals(860L, t.gapMs)
    }

    @Test fun `stale frame is dropped`() {
        val cf = CountConfirmer(2)
        cf.update(3, 50)
        assertNull(cf.update(4, 10))            // 乱序：不推进也不污染候选
        cf.update(4, 60)
        val t = cf.update(4, 80)!!
        assertEquals(60L, t.firstSeenAtMs)
    }

    @Test fun `unmatched candidate is replaced by a longer stable reading`() {
        val cf = CountConfirmer(2)
        cf.update(4, 0); cf.update(4, 140)
        cf.update(2, 500)                       // 候选 2，证据不足
        cf.update(3, 640)                       // 换候选 3
        val t = cf.update(3, 780)!!
        assertEquals(3, t.to); assertEquals(640L, t.firstSeenAtMs)
    }

    @Test fun `reset clears confirmed baseline`() {
        val cf = CountConfirmer(2)
        cf.update(4, 0); cf.update(4, 140); cf.reset()
        assertNull(cf.update(2, 500))                 // 首见候选，证据不足
        val t = cf.update(2, 640)!!                   // 二次目击确认 = 新基线（from=null）
        assertNull(t.from); assertEquals(2, t.to); assertEquals(500L, t.firstSeenAtMs)
    }

    // ---------- B. 起算与锚点（5） ----------

    @Test fun `first stable reading establishes baseline without a timer`() {
        val eng = engine()
        baseline4(eng)
        assertTrue(ev(feed(eng, 280, c(4), e(4))).isEmpty())
        assertNull(timerOf(eng, TimerSide.SELF)); assertNull(timerOf(eng, TimerSide.ENEMY))
    }

    @Test fun `drop by one anchors at first sight and starts cooldown`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)               // 旧计数贴近掉落：gap=100 → 锚点确定
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 1140, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(1000L, start.anchorAtMs); assertEquals(16_000L, start.endAtMs)
        assertFalse(start.uncertain)
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `anchor remains the first sighting even when confirmation lands later`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1900, c(4), null)
        eng.onCells(c(3), null, 2000)
        val es = ev(eng.onCells(c(3), null, 6000))   // 确认帧在 6s 后，锚点仍是首见
        assertEquals(2000L, es.filterIsInstance<TimerEvent.SubstitutionStarted>().single().anchorAtMs)
    }

    @Test fun `interleaved unreadable frames do not delay the anchor`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 200, c(3), null)
        feed(eng, 340, null, null)                   // 不可读不重置证据
        val es = ev(feed(eng, 480, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(200L, start.anchorAtMs); assertFalse(start.uncertain)
    }

    @Test fun `sparse observations still anchor at the first sighting`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 600, c(3), null)
        val es = ev(feed(eng, 4000, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(600L, start.anchorAtMs); assertFalse(start.uncertain) // gap=460≤700
    }

    // ---------- C. 增益与防抖（6） ----------

    @Test fun `gains never start timers`() {
        val eng = engine()
        feed(eng, 0, c(3), null); feed(eng, 140, c(3), null)
        feed(eng, 1000, c(4), null)
        val es = ev(feed(eng, 1140, c(4), null))
        assertTrue(es.any { it is TimerEvent.GainConfirmed })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `gain flicker drop is ignored`() {
        val eng = engine()
        feed(eng, 0, c(3), null); feed(eng, 140, c(3), null)
        feed(eng, 1000, c(4), null); feed(eng, 1140, c(4), null)   // 涨豆 anchor=1000
        feed(eng, 1200, c(3), null)
        val es = ev(feed(eng, 1340, c(3), null))                    // 掉豆 anchor=1200，差 200≤350
        assertTrue(es.any { it is TimerEvent.GainFlickerIgnored })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `slow drop after a gain still starts a timer`() {
        val eng = engine()
        feed(eng, 0, c(3), null); feed(eng, 140, c(3), null)
        feed(eng, 1000, c(4), null); feed(eng, 1140, c(4), null)
        feed(eng, 1400, c(3), null)
        val es = ev(feed(eng, 1540, c(3), null))                    // 差 400>350 → 真替身
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
    }

    @Test fun `rebound restores the previously running timer`() {
        // 短冷却造出"前任计时"：A(200,2200) 余量<1s 时 B(1400,3400) 起算，B 回弹应恢复 A
        val eng = engine(cooldownMs = 2000, tuning = DetectionTuning(cooldownGraceMs = 1000))
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                        // A：anchor 200（gap=60），end 2200
        feed(eng, 1300, c(3), null)                                  // 刷新旧计数目击
        feed(eng, 1400, c(2), null); feed(eng, 1540, c(2), null)    // B：anchor 1400，end 3400，prev=A
        feed(eng, 1600, c(3), null)
        val es = ev(feed(eng, 1740, c(3), null))                    // 1600-1400=200≤500 回弹
        assertTrue(es.any { it is TimerEvent.TimerRetracted })
        assertEquals(2200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)  // 恢复 A
    }

    @Test fun `rebound window counts from the drop anchor`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)                       // A：anchor 1000，end 16000
        feed(eng, 1500, c(4), null)
        val es = ev(feed(eng, 1640, c(4), null))                    // 1500-1000=500≤500 → 回弹
        assertTrue(es.any { it is TimerEvent.TimerRetracted })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `return past the rebound window does not retract`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)
        feed(eng, 1600, c(4), null)
        val es = ev(feed(eng, 1740, c(4), null))                    // 1600-1000=600>500 → 真涨豆
        assertTrue(es.any { it is TimerEvent.GainConfirmed })
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    // ---------- D. 多豆与连锁（6） ----------

    @Test fun `multi bean drain produces no timer`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(2), null)
        val es = ev(feed(eng, 1140, c(2), null))
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `multi drop during cooldown keeps the running timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                        // A：4→3@200
        feed(eng, 500, c(1), null)
        val es = ev(feed(eng, 640, c(1), null))                     // 3→1 多豆（非连扣步进）
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertTrue(es.none { it is TimerEvent.TimerRetracted })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `chain drop inside cascade window retracts only timers started in the chain`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)                       // A：anchor 1000，end 16000
        feed(eng, 1300, c(2), null)
        val es = ev(feed(eng, 1440, c(2), null))                    // 3→2@1300：链内第二步 −1
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertTrue(es.any { it is TimerEvent.TimerRetracted })      // A 被回滚（链内计时）
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `chain drop after a real substitution keeps that timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                        // T0：4→3@200，end 15200
        feed(eng, 1000, c(2), null); feed(eng, 1140, c(2), null)    // 冷却内 → CooldownDrop + 记录
        feed(eng, 1300, c(1), null)
        val es = ev(feed(eng, 1440, c(1), null))                    // 2→1 连扣 → MultiDrop
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs) // T0 锚点在回滚窗外，保留
    }

    @Test fun `drop past cascade window is conflict not multi`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)
        feed(eng, 1401, c(2), null)
        val es = ev(feed(eng, 1541, c(2), null))                    // 401ms>400 → 非连扣
        assertTrue(es.any { it is TimerEvent.CooldownDrop })
        assertTrue(es.none { it is TimerEvent.MultiDrop })
        assertEquals(16_401L, timerOf(eng, TimerSide.SELF)!!.altEndAtMs)
    }

    @Test fun `drop of two or more never counts as substitution`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(0), null)
        val es = ev(feed(eng, 1140, c(0), null))                    // 4→0 一次清空
        assertTrue(es.any { it is TimerEvent.MultiDrop })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    // ---------- E. 冷却先验（5） ----------

    @Test fun `drop inside cooldown records alternative end but keeps timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, c(2), null)
        val es = ev(feed(eng, 1140, c(2), null))
        assertTrue(es.any { it is TimerEvent.CooldownDrop })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.altEndAtMs)
        assertTrue(timerOf(eng, TimerSide.SELF)!!.conflict)
    }

    @Test fun `cooldown drop does not start a second timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, c(2), null); feed(eng, 1140, c(2), null)
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs) // 原计时不让位
    }

    @Test fun `alternative end equals the second drop anchor plus cooldown`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 800, c(2), null); feed(eng, 940, c(2), null)
        assertEquals(15_800L, timerOf(eng, TimerSide.SELF)!!.altEndAtMs)
    }

    @Test fun `drop at exact grace boundary starts a new timer`() {
        val eng = engine()                                           // cd 15000、grace 1000
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                         // A：end 15200
        feed(eng, 14_000, c(3), null)                                // 刷新旧计数目击
        feed(eng, 14_060, c(2), null)                                // 首见 14060，gap=60
        val es = ev(feed(eng, 14_200, c(2), null))                   // now=14200，A.remaining=1000=grace → 起算
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
        assertEquals(29_060L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `repeated real substitutions each start their own timer`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)                         // A：end 15200
        feed(eng, 15_300, c(3), null)                                // A 已到期；刷新旧计数
        feed(eng, 15_400, c(2), null)
        val es = ev(feed(eng, 15_540, c(2), null))                   // remaining≤0 → 新替身 B
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
        val t = timerOf(eng, TimerSide.SELF)!!
        assertEquals(30_400L, t.endAtMs)
        assertEquals(15_200L, t.prevTimer!!.endAtMs)                 // 前任链入
    }

    // ---------- F. 不可读与歧义（7） ----------

    @Test fun `ambiguous gap produces ambiguous drop without a timer`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 5000, c(3), null)
        val es = ev(feed(eng, 5140, c(3), null))                    // gap=4860>2500
        assertTrue(es.any { it is TimerEvent.AmbiguousDrop })
        assertNull(timerOf(eng, TimerSide.SELF))
    }

    @Test fun `post gap drop is marked uncertain`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 2000, c(3), null))                    // gap=860∈(700,2500]
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertTrue(start.uncertain)
    }

    @Test fun `uncertain anchor uses midpoint`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 2000, c(3), null))
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(570L, start.anchorAtMs)                         // (140+1000)/2
    }

    @Test fun `long unreadable run resets baseline but keeps timers`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, null, null)
        val es = ev(feed(eng, 9001, null, null))                    // 双侧不可读 >8000
        assertTrue(es.any { it is TimerEvent.BaselineReset })
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs) // 计时存活
    }

    @Test fun `sustained non battle clears timers and baseline`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        feed(eng, 1000, c(4), e(4), battle = false)
        val es = ev(feed(eng, 9001, c(4), e(4), battle = false))     // 非战斗持续 >8000
        assertTrue(es.any { it is TimerEvent.BaselineReset })
        assertNull(timerOf(eng, TimerSide.SELF))                     // match_end 清计时
    }

    @Test fun `timers keep counting during unreadable silence`() {
        val eng = engine()
        baseline4(eng)
        substitute(eng, TimerSide.SELF, 200)
        repeat(3) { feed(eng, 1000 + it * 1000L, null, null) }       // 3s 不可读 <8000
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(15_200L - 4_000, timerOf(eng, TimerSide.SELF)!!.remaining(4000))
    }

    @Test fun `frame gaps do not break an already confirmed count`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 3000, c(4), null)                                  // 3s 后读到同值=仍是基线
        feed(eng, 3100, c(3), null)
        val es = ev(feed(eng, 3240, c(3), null))                     // gap=100 → 正常起算
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(3100L, start.anchorAtMs); assertFalse(start.uncertain)
    }

    // ---------- G. 双侧与塌缩（4） ----------

    @Test fun `both sides run independent timers`() {
        val eng = engine()
        baseline4(eng)
        feed(eng, 900, c(4), null)
        substitute(eng, TimerSide.SELF, 1000)                        // 我方@1000 end 16000
        feed(eng, 2900, null, e(4))                                  // 敌方旧计数刷新
        substitute(eng, TimerSide.ENEMY, 3000)                       // 敌方@3000 end 18000
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(18_000L, timerOf(eng, TimerSide.ENEMY)!!.endAtMs)
    }

    @Test fun `both drop is collapse not double substitution`() {
        val eng = engine()
        feed(eng, 0, c(1), e(1)); feed(eng, 140, c(1), e(1))          // 双侧基线各 1
        feed(eng, 900, c(1), e(1))                                    // 双侧刷新目击（锚点确定）
        feed(eng, 1000, c(0), null)                                   // 我方 1→0 首见 1000
        feed(eng, 1100, null, e(0))                                   // 敌方 1→0 首见 1100
        feed(eng, 1140, c(0), null)                                   // 我方确认：起算 + 记 lastZeroDrop=1000
        val es = ev(feed(eng, 1240, null, e(0)))                      // 敌方确认：|1100−1000|≤300 → collapse
        assertTrue(es.any { it is TimerEvent.TimerRetracted })        // 我方归零计时被回滚
        assertTrue(es.any { it is TimerEvent.AmbiguousDrop })
        assertNull(timerOf(eng, TimerSide.SELF)); assertNull(timerOf(eng, TimerSide.ENEMY))
    }

    @Test fun `collapse window edge keeps timers`() {
        val eng = engine()
        feed(eng, 0, c(1), e(1)); feed(eng, 140, c(1), e(1))
        feed(eng, 900, c(1), e(1))
        feed(eng, 1000, c(0), null); feed(eng, 1140, c(0), null)      // 我方 1→0@1000 起算 end 16000
        feed(eng, 1401, null, e(0))
        val es = ev(feed(eng, 1541, null, e(0)))                      // 敌方首见 1401：差 401>300 → 非塌缩
        assertTrue(es.none { it is TimerEvent.TimerRetracted })
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
        assertEquals(16_401L, timerOf(eng, TimerSide.ENEMY)!!.endAtMs) // 敌方 −1 正常起算
    }

    @Test fun `drop to zero alone is allowed and times normally`() {
        val eng = engine()
        feed(eng, 0, c(1), e(4)); feed(eng, 140, c(1), e(4))
        feed(eng, 900, c(1), e(4))                                    // 刷新目击
        feed(eng, 1000, c(0), null)
        val es = ev(feed(eng, 1140, c(0), null))
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
        assertEquals(16_000L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    // ---------- H. 回放与诊断（5） ----------

    @Test fun `replay of observation records reproduces events`() {
        val recs = listOf(
            rec(0, c(4), e(4)), rec(140, c(4), e(4)),
            rec(900, c(4), e(4)),
            rec(1000, c(3), e(4)), rec(1140, c(3), e(4)),
        )
        val events = engine().replayCells(recs).flatten()
        assertTrue(events.any { it is TimerEvent.SubstitutionStarted })
    }

    @Test fun `cell replay reproduces events`() {
        val recs = listOf(
            rec(0, c(4), null), rec(140, c(4), null),
            rec(200, c(3), null), rec(340, c(3), null),
        )
        val started = engine().replayCells(recs).flatten()
            .filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(200L, started.anchorAtMs)
    }

    @Test fun `marks do not affect events`() {
        val base = listOf(rec(0, c(4), null), rec(140, c(4), null),
            rec(200, c(3), null), rec(340, c(3), null))
        val marked = base.map { it.copy(mark = "missed") }
        assertEquals(engine().replayCells(base), engine().replayCells(marked))
    }

    @Test fun `unknown cells do not shift anchor`() {
        val eng = engine()
        feed(eng, 0, c(4), null); feed(eng, 140, c(4), null)
        feed(eng, 300, cells(L, L, L, U), null)                       // 记忆 L@140 → 仍 4
        feed(eng, 500, cells(L, L, L, E), null)                       // 首见 3
        val es = ev(feed(eng, 640, cells(L, L, L, U), null))          // 记忆 E@500 → 仍 3 → 确认
        val start = es.filterIsInstance<TimerEvent.SubstitutionStarted>().single()
        assertEquals(500L, start.anchorAtMs)
    }

    @Test fun `hole rows never reach the confirmer`() {
        val eng = engine()
        feed(eng, 0, c(4), null); feed(eng, 140, c(4), null)
        repeat(4) { feed(eng, 300 + it * 140L, cells(L, E, L, L), null) }
        assertNull(timerOf(eng, TimerSide.SELF))
        feed(eng, 1200, c(3), null)
        val es = ev(feed(eng, 1340, c(3), null))
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })    // 洞形期没污染基线
    }

    // ---------- I. 暂停、撤销与移植（5） ----------

    @Test fun `pause ignores frames and resume re-baselines`() {
        val eng = engine()
        baseline4(eng); eng.pause()
        assertTrue(ev(feed(eng, 1000, c(3), null)).isEmpty())
        eng.resume()
        feed(eng, 2000, c(3), null)
        val es = ev(feed(eng, 2140, c(3), null))
        assertTrue(es.none { it is TimerEvent.SubstitutionStarted })   // 新基线而非掉落
    }

    @Test fun `resume preserves running timers`() {
        val eng = engine()
        baseline4(eng); substitute(eng, TimerSide.SELF, 200)
        eng.pause(); eng.resume()
        assertEquals(15_200L, timerOf(eng, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `cancel clears only that side`() {
        val eng = engine()
        baseline4(eng)
        // 帧时刻全局单调：敌方先锚定 300（其确认帧 440 会吞掉我方 300 内的首见，
        // 故我方改锚 500——断言只依赖敌方 end=300+15000 不变）
        substitute(eng, TimerSide.ENEMY, 300); substitute(eng, TimerSide.SELF, 500)
        eng.cancelTimer(TimerSide.SELF)
        assertNull(timerOf(eng, TimerSide.SELF))
        assertEquals(15_300L, timerOf(eng, TimerSide.ENEMY)!!.endAtMs)
    }

    @Test fun `carried timers survive engine rebuild`() {
        val eng = engine()
        baseline4(eng); substitute(eng, TimerSide.SELF, 200)
        val eng2 = engine(carried = eng.carriedTimers())
        assertEquals(15_200L, timerOf(eng2, TimerSide.SELF)!!.endAtMs)
    }

    @Test fun `stale frames are dropped at engine level`() {
        val eng = engine()
        baseline4(eng)
        assertTrue(ev(feed(eng, 50, c(3), null)).isEmpty())            // 比已收帧更早
        feed(eng, 1000, c(3), null)
        val es = ev(feed(eng, 1140, c(3), null))
        assertTrue(es.any { it is TimerEvent.SubstitutionStarted })
    }

    // ---------- helpers ----------

    private fun rec(atMs: Long, self: List<DotState>?, enemy: List<DotState>?): ObservationRecord =
        ObservationRecord(
            seq = atMs.toInt(), atMs = atMs, clock = "test", channel = "replay",
            geoW = 1920, geoH = 1080, scene = "BATTLE", battle = true,
            selfCount = self?.count { it == DotState.LIT },
            enemyCount = enemy?.count { it == DotState.LIT },
            selfCells = self?.map { it.name.first().toString() },
            enemyCells = enemy?.map { it.name.first().toString() },
        )
}
