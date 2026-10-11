package xyz.chouxuewei.mobile_agent.core.substitution

import kotlin.math.abs

/**
 * 新计数确认器：把"行计数序列"压成"稳定计数转移"。
 * 不可读帧（null）不推进也不重置证据；稀疏帧同理；候选计数被其它计数取代时重新累计。
 */
internal class CountConfirmer(private val confirmFrames: Int) {
    /** 已确认计数最近一次被读到的帧时刻——转移间隔的旧侧端点。 */
    var confirmedSeenAtMs = Long.MIN_VALUE; private set
    /** 当前已确认的稳定计数；null = 尚无基线。 */
    var confirmed: Int? = null; private set
    private var candidate: Int? = null
    private var candidateSeenAtMs = Long.MIN_VALUE
    private var candidateStreak = 0
    private var lastFrameAtMs = Long.MIN_VALUE

    data class Transition(
        val from: Int?,
        val to: Int,
        /** 新计数首次被读到的帧时刻——常规锚点。 */
        val firstSeenAtMs: Long,
        /** 旧计数最后一次被读到的帧时刻——间隔端点。 */
        val lastOldSeenAtMs: Long,
        /** 转移可能发生的总时间窗 = firstSeen - lastOldSeen。 */
        val gapMs: Long,
    )

    fun update(count: Int?, atMs: Long): Transition? {
        if (atMs <= lastFrameAtMs) return null
        lastFrameAtMs = atMs
        if (count == null) return null
        if (count == confirmed) {
            confirmedSeenAtMs = atMs
            candidate = null; candidateStreak = 0
            return null
        }
        if (count != candidate) { candidate = count; candidateSeenAtMs = atMs; candidateStreak = 0 }
        candidateStreak++
        if (candidateStreak < confirmFrames) return null
        val t = Transition(
            from = confirmed, to = count,
            firstSeenAtMs = candidateSeenAtMs,
            lastOldSeenAtMs = confirmedSeenAtMs,
            gapMs = candidateSeenAtMs - confirmedSeenAtMs,
        )
        confirmed = count; confirmedSeenAtMs = atMs
        candidate = null; candidateStreak = 0
        return t
    }

    fun reset() {
        confirmed = null; confirmedSeenAtMs = Long.MIN_VALUE
        candidate = null; candidateStreak = 0; lastFrameAtMs = Long.MIN_VALUE
    }
}

sealed class TimerEvent {
    /** 计数首次稳定确认（建基线）或转移确认。 */
    data class CountConfirmed(val side: TimerSide, val count: Int) : TimerEvent()

    /** 涨豆确认：只更新基线，永不计时。 */
    data class GainConfirmed(val side: TimerSide, val fromCount: Int, val toCount: Int) : TimerEvent()

    /** 一次 −1 起算替身冷却。anchorAtMs=证据锚点；uncertain=锚点为中点估计。 */
    data class SubstitutionStarted(
        val side: TimerSide,
        val anchorAtMs: Long,
        val endAtMs: Long,
        val fromCount: Int,
        val toCount: Int,
        val gapMs: Long,
        val uncertain: Boolean,
    ) : TimerEvent()

    /** 冷却内 −1：物理先验不可能是替身——记备选截止，原计时不让位。 */
    data class CooldownDrop(
        val side: TimerSide,
        val fromCount: Int,
        val toCount: Int,
        val anchorAtMs: Long,
        val remainingAtDropMs: Long,
    ) : TimerEvent()

    /** 一次多豆或窗口内连扣：奥义/连扣语义，不计时；链内已起计时已回滚。 */
    data class MultiDrop(val side: TimerSide, val fromCount: Int, val toCount: Int, val anchorAtMs: Long) : TimerEvent()

    /** 涨豆后短窗内掉豆：奥义清豆/涨豆残影，不计时。 */
    data class GainFlickerIgnored(val side: TimerSide, val fromCount: Int, val toCount: Int, val anchorAtMs: Long) : TimerEvent()

    /** 触发时刻不可知的掉豆：reason="gap"（证据空白过长）| "collapse"（双侧同塌）。 */
    data class AmbiguousDrop(val side: TimerSide, val fromCount: Int, val toCount: Int, val anchorAtMs: Long, val reason: String) : TimerEvent()

    /** 已起计时被短窗证据推翻：reason="rebound"|"cascade"|"collapse"；prevTimer 已恢复。 */
    data class TimerRetracted(val side: TimerSide, val reason: String, val retractedEndAtMs: Long) : TimerEvent()

    /** 基线重建：reason="hud_lost"（长不可读，计时保留）|"match_end"（持续非战斗，计时清空）| 服务侧原因。 */
    data class BaselineReset(val side: TimerSide?, val reason: String) : TimerEvent()
}

/**
 * 双侧替身推断引擎 v2。
 * 核心先验：替身冷却内不可能再次替身——冷却外首个 −1 起算计时，冷却内 −1 只记
 * 冲突与备选截止，不起算。计时即确认即起算（锚点=首次目击帧），只允许回弹/
 * 连扣/双侧同塌三种短窗证据证伪回滚；不可读帧不推进也不破坏任何证据。
 * 线程安全：引擎非线程安全，调用方串行化（服务侧 engineLock）。
 */
class SubstitutionEngine(
    private val config: TimerConfig,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val carried: Map<TimerSide, SideTimer> = emptyMap(),
) {
    /** created=该掉落创建的计时（D4 冷却冲突/MultiDrop 等为 null）——回弹/塌缩只回滚"掉落自己造成的"计时。 */
    private data class DropMark(val fromCount: Int, val toCount: Int, val anchorMs: Long, val created: SideTimer?)

    private inner class SideState(val side: TimerSide) {
        val tracker = BeadRowTracker(config.tuning.cellHoldMs)
        val confirmer = CountConfirmer(config.tuning.confirmFrames)
        var timer: SideTimer? = carried[side]
        var prevTimer: SideTimer? = timer?.prevTimer
        var lastGainAnchorMs: Long? = null
        val recentDrops = ArrayDeque<DropMark>()
        /** 最近一次"掉到 0"的首次目击时刻（真实观察时刻，非中点锚点）——塌缩检测用。 */
        var lastZeroDropFirstSeenMs = Long.MIN_VALUE
        /** 最近一次掉到 0 所创建的计时（未创建为 null）——塌缩只回滚它。 */
        var lastZeroDropTimer: SideTimer? = null
    }

    data class FrameResult(
        val events: List<TimerEvent>,
        /** 本帧逐格层读数；缺 key = 该侧本帧不可读（供 UI/诊断记录/回放语义）。 */
        val counts: Map<TimerSide, Int>,
    )

    private val self = SideState(TimerSide.SELF)
    private val enemy = SideState(TimerSide.ENEMY)
    private val diagnostics = ArrayDeque<String>(200)
    private var lastFrameAtMs = Long.MIN_VALUE
    private var unreadableSinceMs = Long.MIN_VALUE
    private var nonBattleSinceMs = Long.MIN_VALUE
    private var matchEnded = false

    var paused = false
        private set

    fun timers(nowMs: Long = clock()): Pair<SideTimer, SideTimer> =
        (self.timer ?: SideTimer(TimerSide.SELF)) to (enemy.timer ?: SideTimer(TimerSide.ENEMY))

    /** 热更新/重建时携带的在途计时（含 prevTimer 链）。 */
    fun carriedTimers(): Map<TimerSide, SideTimer> =
        listOfNotNull(self.timer?.let { TimerSide.SELF to it }, enemy.timer?.let { TimerSide.ENEMY to it }).toMap()

    fun diagnostics(): List<String> = diagnostics.toList()

    fun pause() { paused = true }

    fun resume() {
        paused = false
        // 暂停期间的帧不可信——基线重建，计时按单调 endAt 照常推进
        resetBaseline("paused_resume")
    }

    /** 基线重建：清证据与计数状态；计时存活（match_end 之外的复位都不动计时）。 */
    fun resetBaseline(reason: String) {
        for (s in listOf(self, enemy)) {
            s.confirmer.reset(); s.tracker.reset()
            s.recentDrops.clear(); s.lastGainAnchorMs = null
            s.lastZeroDropFirstSeenMs = Long.MIN_VALUE; s.lastZeroDropTimer = null
        }
        emit(TimerEvent.BaselineReset(null, reason))
    }

    /** 手动撤销一侧全部推断状态：计时、基线、证据——纠错后从头建立。 */
    fun cancelTimer(side: TimerSide) {
        val s = select(side)
        s.timer = null; s.prevTimer = null
        s.confirmer.reset(); s.tracker.reset()
        s.recentDrops.clear(); s.lastGainAnchorMs = null
        s.lastZeroDropFirstSeenMs = Long.MIN_VALUE; s.lastZeroDropTimer = null
    }

    /**
     * 逐格入口：每帧两侧格子状态（null=该侧不可读）。battle=false 整帧按不可读处理，
     * 只推进"持续非战斗"时钟。返回事件与本帧计数。帧时间必须单调递增。
     */
    fun onCells(
        selfCells: List<DotState>?,
        enemyCells: List<DotState>?,
        frameAtMs: Long = clock(),
        battle: Boolean = true,
    ): FrameResult {
        if (paused) return FrameResult(emptyList(), emptyMap())
        if (frameAtMs <= lastFrameAtMs) {
            log("stale frame dropped @$frameAtMs", frameAtMs)
            return FrameResult(emptyList(), emptyMap())
        }
        lastFrameAtMs = frameAtMs

        if (!battle) {
            if (nonBattleSinceMs == Long.MIN_VALUE) nonBattleSinceMs = frameAtMs
            val events = mutableListOf<TimerEvent>()
            if (!matchEnded && frameAtMs - nonBattleSinceMs > config.tuning.hudLostResetMs) {
                matchEnded = true
                matchEndReset()
                events += TimerEvent.BaselineReset(null, "match_end")
            }
            return FrameResult(events, emptyMap())
        }
        nonBattleSinceMs = Long.MIN_VALUE; matchEnded = false

        val events = mutableListOf<TimerEvent>()
        val selfCount = self.tracker.update(selfCells, frameAtMs)
        val enemyCount = enemy.tracker.update(enemyCells, frameAtMs)
        val counts = mutableMapOf<TimerSide, Int>()
        selfCount?.let { counts[TimerSide.SELF] = it }
        enemyCount?.let { counts[TimerSide.ENEMY] = it }
        if (selfCount == null && enemyCount == null) {
            if (unreadableSinceMs == Long.MIN_VALUE) unreadableSinceMs = frameAtMs
            if (frameAtMs - unreadableSinceMs > config.tuning.hudLostResetMs) {
                unreadableSinceMs = Long.MIN_VALUE        // 每个不可读周期至多触发一次
                resetBaseline("hud_lost")                  // 计时保留
                events += TimerEvent.BaselineReset(null, "hud_lost")
            }
            return FrameResult(events, counts)
        }
        unreadableSinceMs = Long.MIN_VALUE
        feedSide(self, selfCount, frameAtMs, events)
        feedSide(enemy, enemyCount, frameAtMs, events)
        return FrameResult(events, counts)
    }

    /**
     * 计数口径兼容入口（回放/未改造调用方）：计数合成规范豆形格列后转 onCells。
     * 返回类型与 v1 保持一致（List<TimerEvent>），服务侧 `eng.onFrame(...)` 调用原样可编译。
     */
    fun onFrame(selfCount: Int?, enemyCount: Int?, frameAtMs: Long = clock(), battle: Boolean = true): List<TimerEvent> =
        onCells(cellsForCount(TimerSide.SELF, selfCount), cellsForCount(TimerSide.ENEMY, enemyCount), frameAtMs, battle).events

    private fun feedSide(s: SideState, count: Int?, atMs: Long, events: MutableList<TimerEvent>) {
        val t = s.confirmer.update(count, atMs) ?: return
        if (t.from == null) {                          // 首个稳定计数 = 基线，不是事件
            events += TimerEvent.CountConfirmed(s.side, t.to)
            return
        }
        events += TimerEvent.CountConfirmed(s.side, t.to)
        if (t.to > t.from) {                           // 涨豆：先查"弹回"再记增益
            if (retractIfRebound(s, t, atMs, events)) return
            s.lastGainAnchorMs = t.firstSeenAtMs
            events += TimerEvent.GainConfirmed(s.side, t.from, t.to)
            return
        }
        events += evaluateDrop(s, t, atMs)
    }

    /** 掉豆判定表（§7.2 D0-D5，按顺序短路）。 */
    private fun evaluateDrop(s: SideState, t: CountConfirmer.Transition, now: Long): List<TimerEvent> {
        val events = mutableListOf<TimerEvent>()
        // feedSide 已短路 t.from==null（基线建立非掉落）；守卫仅为让编译器收窄 t.from: Int?→Int
        if (t.from == null) return events
        val uncertain = t.gapMs > config.tuning.uncertainGapMs
        val anchor = if (uncertain) t.lastOldSeenAtMs + t.gapMs / 2 else t.firstSeenAtMs
        val end = anchor + config.cooldownMs

        if (t.gapMs > config.tuning.maxAnchorGapMs) {                        // D0
            events += TimerEvent.AmbiguousDrop(s.side, t.from, t.to, anchor, "gap")
            s.recentDrops.clear(); return events
        }
        if (t.to == 0) {                                                      // D1
            val other = select(if (s.side == TimerSide.SELF) TimerSide.ENEMY else TimerSide.SELF)
            if (other.lastZeroDropFirstSeenMs != Long.MIN_VALUE &&
                abs(t.firstSeenAtMs - other.lastZeroDropFirstSeenMs) <= config.tuning.collapseWindowMs) {
                // 双侧近同时"掉到 0"=豆行整排消失（转场/结算）：
                // 只回滚对侧"由那次归零创建"的计时（若仍是当前计时），更早的真实计时不连坐。
                other.lastZeroDropTimer?.takeIf { it === other.timer }?.let { zt ->
                    other.timer = zt.prevTimer
                    other.prevTimer = zt.prevTimer?.prevTimer
                    events += TimerEvent.TimerRetracted(other.side, "collapse", zt.endAtMs)
                }
                events += TimerEvent.AmbiguousDrop(s.side, t.from, t.to, anchor, "collapse")
                s.recentDrops.clear(); return events
            }
            s.lastZeroDropFirstSeenMs = t.firstSeenAtMs
            s.lastZeroDropTimer = null                       // 若 D5 真起了计时再回填
        }
        s.lastGainAnchorMs?.let { gainAt ->                                   // D2
            if (anchor - gainAt <= config.tuning.gainFlickerMs) {
                events += TimerEvent.GainFlickerIgnored(s.side, t.from, t.to, anchor)
                s.recentDrops.clear(); return events
            }
        }
        val chainDrops = s.recentDrops.count {                                // D3
            it.toCount == it.fromCount - 1 && anchor - it.anchorMs <= config.tuning.cascadeWindowMs
        }
        val isChainStep = t.to == t.from - 1 && chainDrops >= 1
        if (t.to < t.from - 1 || isChainStep) {
            if (isChainStep) {
                retractTimersInWindow(s, anchor - config.tuning.cascadeWindowMs, now).forEach {
                    events += TimerEvent.TimerRetracted(s.side, "cascade", it.endAtMs)
                }
            }
            events += TimerEvent.MultiDrop(s.side, t.from, t.to, anchor)
            s.recentDrops.clear(); return events
        }
        var created: SideTimer? = null
        val tm = s.timer                                                      // D4 冷却先验
        if (tm != null && tm.remaining(now) > config.tuning.cooldownGraceMs) {
            tm.altEndAtMs = end; tm.conflict = true
            events += TimerEvent.CooldownDrop(s.side, t.from, t.to, anchor, tm.remaining(now))
        } else {                                                              // D5 起算
            created = startTimer(s, anchor, end, uncertain)
            if (t.to == 0) s.lastZeroDropTimer = created      // 归零起的计时才纳入塌缩回滚
            events += TimerEvent.SubstitutionStarted(
                s.side, anchor, end, t.from, t.to, t.gapMs, uncertain,
            )
        }
        s.recentDrops += DropMark(t.from, t.to, anchor, created)
        return events
    }

    /**
     * 涨豆落回到"当前计时那次掉落"的原水平且离该掉落锚点 ≤reboundRetractMs → 假掉落，回滚。
     * 只回滚创建了当前计时的那次掉落；冷却冲突掉豆（created=null）不参与。
     */
    private fun retractIfRebound(
        s: SideState,
        t: CountConfirmer.Transition,
        now: Long,
        events: MutableList<TimerEvent>,
    ): Boolean {
        val lastDrop = s.recentDrops.lastOrNull { it.created != null } ?: return false
        val tm = lastDrop.created!!
        if (s.timer !== tm || tm.remaining(now) <= 0) return false   // 已换计时/已到期不回滚
        if (t.firstSeenAtMs - lastDrop.anchorMs > config.tuning.reboundRetractMs) return false
        if (t.to < lastDrop.fromCount) return false                  // 未弹回掉前水平
        events += TimerEvent.TimerRetracted(s.side, "rebound", tm.endAtMs)
        s.timer = tm.prevTimer
        s.prevTimer = tm.prevTimer?.prevTimer
        s.recentDrops.clear()
        return true
    }

    /**
     * 回滚锚点落在 (lo, now] 的连续在途计时，返回被回滚者。
     * 链上锚点单调递减：只需从链头连续弹出——头部不在窗内则更深者必不在。
     * 结束后重同步镜像不变量 s.prevTimer = s.timer?.prevTimer（单字段弹出会破坏它）。
     */
    private fun retractTimersInWindow(s: SideState, lo: Long, now: Long): List<SideTimer> {
        val removed = mutableListOf<SideTimer>()
        while (s.timer?.let { it.anchorAtMs in (lo + 1)..now } == true) {
            removed += s.timer!!; s.timer = s.timer!!.prevTimer
        }
        s.prevTimer = s.timer?.prevTimer
        return removed
    }

    private fun startTimer(s: SideState, anchor: Long, end: Long, uncertain: Boolean): SideTimer {
        val t = SideTimer(
            side = s.side, active = true, endAtMs = end, suspected = true,
            anchorAtMs = anchor, eventAtMs = anchor,
            uncertain = uncertain, prevTimer = s.timer,
        )
        s.prevTimer = s.timer
        s.timer = t
        return t
    }

    /** 持续非战斗超时：清计时与基线（比赛结束语义），等下一局重新建立。 */
    private fun matchEndReset() {
        for (s in listOf(self, enemy)) {
            s.timer = null; s.prevTimer = null
            s.confirmer.reset(); s.tracker.reset()
            s.recentDrops.clear(); s.lastGainAnchorMs = null
            s.lastZeroDropFirstSeenMs = Long.MIN_VALUE; s.lastZeroDropTimer = null
        }
        emit(TimerEvent.BaselineReset(null, "match_end"))
    }

    private fun select(side: TimerSide) = if (side == TimerSide.SELF) self else enemy

    private fun log(msg: String, atMs: Long) {
        if (diagnostics.size >= 200) diagnostics.removeFirst()
        diagnostics.addLast("$atMs $msg")
    }

    private fun emit(event: TimerEvent) = log(event.toString(), clock())

    companion object {
        /** 计数 → 规范豆形格列（SELF 左锚 L..E..、ENEMY 右锚 E..L..）；null→null。 */
        internal fun cellsForCount(side: TimerSide, count: Int?): List<DotState>? {
            if (count == null) return null
            val n = count.coerceIn(0, 4)
            return when (side) {
                TimerSide.SELF -> List(4) { if (it < n) DotState.LIT else DotState.EMPTY }
                TimerSide.ENEMY -> List(4) { if (it >= 4 - n) DotState.LIT else DotState.EMPTY }
            }
        }
    }
}

/** 服务级运行状态：与每侧计时状态分离。 */
enum class TimerServiceState {
    DISABLED, NEEDS_PERMISSION, NEEDS_CALIBRATION,
    WAITING_FOR_GAME, MONITORING, PAUSED, ERROR,
}

/**
 * 启动协调状态机（纯函数式转移，副作用由调用方执行）。
 * onAppVisible 幂等：只在 DISABLED/NEEDS_PERMISSION/PAUSED(下次打开时) 下推进。
 */
class TimerStateMachine(
    private var state: TimerServiceState = TimerServiceState.DISABLED,
) {
    val current: TimerServiceState get() = state

    fun transition(event: LaunchEvent): TimerServiceState {
        state = when (event) {
            LaunchEvent.AppOpened -> when (state) {
                TimerServiceState.DISABLED, TimerServiceState.NEEDS_PERMISSION ->
                    TimerServiceState.NEEDS_PERMISSION
                else -> state
            }
            LaunchEvent.PermissionGranted -> if (state == TimerServiceState.NEEDS_PERMISSION)
                TimerServiceState.NEEDS_CALIBRATION else state
            LaunchEvent.PermissionDenied -> TimerServiceState.NEEDS_PERMISSION
            LaunchEvent.Calibrated -> if (state == TimerServiceState.NEEDS_CALIBRATION)
                TimerServiceState.WAITING_FOR_GAME else state
            LaunchEvent.GameForeground -> if (state == TimerServiceState.WAITING_FOR_GAME)
                TimerServiceState.MONITORING else state
            LaunchEvent.GameLeft -> if (state == TimerServiceState.MONITORING)
                TimerServiceState.WAITING_FOR_GAME else state
            LaunchEvent.Pause -> if (state == TimerServiceState.MONITORING ||
                state == TimerServiceState.WAITING_FOR_GAME) TimerServiceState.PAUSED else state
            LaunchEvent.Resume -> if (state == TimerServiceState.PAUSED)
                TimerServiceState.WAITING_FOR_GAME else state
            LaunchEvent.Stop, LaunchEvent.Disabled -> TimerServiceState.DISABLED
            LaunchEvent.SessionLost -> when (state) {
                TimerServiceState.MONITORING, TimerServiceState.WAITING_FOR_GAME,
                TimerServiceState.PAUSED -> TimerServiceState.NEEDS_PERMISSION
                else -> state
            }
            is LaunchEvent.Failed -> TimerServiceState.ERROR
        }
        return state
    }
}

sealed class LaunchEvent {
    data object AppOpened : LaunchEvent()
    data object PermissionGranted : LaunchEvent()
    data object PermissionDenied : LaunchEvent()
    data object Calibrated : LaunchEvent()
    data object GameForeground : LaunchEvent()
    data object GameLeft : LaunchEvent()
    data object Pause : LaunchEvent()
    data object Resume : LaunchEvent()
    data object Stop : LaunchEvent()
    data object Disabled : LaunchEvent()
    data object SessionLost : LaunchEvent()
    data class Failed(val message: String) : LaunchEvent()
}
