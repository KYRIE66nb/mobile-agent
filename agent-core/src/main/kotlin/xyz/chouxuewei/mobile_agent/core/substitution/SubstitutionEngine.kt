package xyz.chouxuewei.mobile_agent.core.substitution

import kotlin.math.max

/** 稳定计数器：只在连续 confirmFrames 帧一致时才确认计数；UNKNOWN 帧不参与。 */
internal class StabilityTracker(private val confirmFrames: Int) {
    private var candidateCount: Int = -1
    private var candidateStreak: Int = 0
    var stable: Int? = null
        private set

    /** 当前候选计数首次出现的时间——用于把计时锚定在"豆数实际变化"而非"确认"时刻。 */
    var firstObservedAtMs: Long = 0
        private set

    /** 输入本帧观测计数（null = 不可信帧）。返回新确认的稳定计数（含首次基线）。 */
    fun observe(count: Int?, atMs: Long): Int? {
        if (count == null) {
            candidateStreak = 0
            candidateCount = -1
            return stable
        }
        if (count == stable) {
            candidateStreak = 0
            candidateCount = -1
            return stable
        }
        if (count == candidateCount) {
            candidateStreak++
        } else {
            candidateCount = count
            candidateStreak = 1
            firstObservedAtMs = atMs
        }
        if (candidateStreak >= confirmFrames) {
            stable = count
            candidateStreak = 0
            candidateCount = -1
        }
        return stable
    }

    fun reset() {
        stable = null
        candidateStreak = 0
        candidateCount = -1
    }
}

sealed class TimerEvent {
    /** 某侧稳定豆数 n→n−1：疑似替身候选，计时已开始。conflict=覆盖了未走完的计时。 */
    data class SubstitutionCandidate(
        val side: TimerSide,
        val atMs: Long,
        val fromCount: Int,
        val toCount: Int,
        val conflict: Boolean,
    ) : TimerEvent()

    /** 一次掉多豆/异常跳变——不自动计时，需要人工判断。 */
    data class AmbiguousDrop(val side: TimerSide, val fromCount: Int, val toCount: Int) : TimerEvent()

    /** 悬置候选被推翻：豆数回弹/级联消耗——计时已撤销。 */
    data class CandidateCancelled(
        val side: TimerSide,
        val fromCount: Int,
        val toCount: Int,
        val reason: String,
    ) : TimerEvent()

    /** 悬置候选验证通过：回弹窗内豆数未回到原值。 */
    data class CandidateConfirmed(val side: TimerSide, val count: Int) : TimerEvent()

    /** 高位短驻后落回更早稳定值——判定为闪光误点亮抖动，不产生候选。 */
    data class WobbleIgnored(val side: TimerSide, val fromCount: Int, val toCount: Int) : TimerEvent()

    /** 基线重建（换局/校准变化/长时间无效帧）。 */
    data class BaselineReset(val side: TimerSide?, val reason: String) : TimerEvent()
}

/**
 * 双侧替身推断引擎。每帧输入两侧的稳定前计数估计（Int?，null=不可信）。
 * 首次稳定只建基线；只有稳定 n→n−1 产生候选并自动计时；
 * 涨豆/多豆跳变/UNKNOWN 不产生计时。暂停只停分析，既有倒计时按 endAt 持续。
 */
class SubstitutionEngine(
    private val config: TimerConfig,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Side(
        val side: TimerSide,
        confirmFrames: Int,
    ) {
        val tracker = StabilityTracker(confirmFrames)
        var timer: SideTimer = SideTimer(side)
        var lastCandidateAtMs: Long = Long.MIN_VALUE
        var lastValidAtMs: Long = 0
        var baselineEstablished = false
        /** 当前稳定计数确认的时点——"高位短驻"判抖动用。 */
        var stableSinceMs: Long = 0
        /** 上一个稳定计数（before 之前的那一个）——落回它说明是抖动不是替身。 */
        var prevStable: Int? = null
        // 悬置候选：from/to/验证截止时间/候选产生时刻（区分快回弹与真实涨豆）
        var pendingFrom: Int? = null
        var pendingTo: Int = -1
        var pendingUntilMs: Long = 0
        var pendingAtMs: Long = 0
        /** 基线重置前最后确认的稳定计数——盲窗重建时对比判断盲期内是否发生了替身。 */
        var resetFromStable: Int? = null
        /** 重置时被强制确认的悬置候选 from——重建发现回弹原值时要追加撤单。 */
        var resetPendingFrom: Int? = null
    }

    private val self = Side(TimerSide.SELF, config.tuning.confirmFrames)
    private val enemy = Side(TimerSide.ENEMY, config.tuning.confirmFrames)
    private val diagnostics = ArrayDeque<String>(200)

    var paused = false
        private set

    fun timers(nowMs: Long = clock()): Pair<SideTimer, SideTimer> = self.timer to enemy.timer
    fun diagnostics(): List<String> = diagnostics.toList()

    fun pause() { paused = true }
    fun resume() {
        paused = false
        // 暂停期间漏掉的帧不可信——回来时重建基线而不是沿用旧计数。
        resetBaseline("paused_resume")
    }

    fun resetBaseline(reason: String) {
        // 记录丢失前的稳定计数与未决候选——盲窗重建时做差值检测
        self.resetFromStable = self.tracker.stable
        enemy.resetFromStable = enemy.tracker.stable
        self.resetPendingFrom = self.pendingFrom
        enemy.resetPendingFrom = enemy.pendingFrom
        self.tracker.reset(); enemy.tracker.reset()
        self.baselineEstablished = false; enemy.baselineEstablished = false
        // 基线作废后悬置验证无从谈起——按已确认处理，保留计时继续走
        self.pendingFrom = null; enemy.pendingFrom = null
        self.timer = self.timer.copy(pending = false)
        enemy.timer = enemy.timer.copy(pending = false)
        emit(TimerEvent.BaselineReset(null, reason))
    }

    /** 手动撤销当前计时（纠错，不建替身事件）。 */
    fun cancelTimer(side: TimerSide) {
        select(side).timer = SideTimer(side)
    }

    /**
     * 输入一帧两侧观测计数（已分类统计后的数量，null=不可信）。
     * 返回本帧产生的事件。帧时间用单调时钟 ms。
     */
    fun onFrame(selfCount: Int?, enemyCount: Int?, frameAtMs: Long = clock()): List<TimerEvent> {
        if (paused) return emptyList()
        val events = mutableListOf<TimerEvent>()
        feed(self, selfCount, frameAtMs, events)
        feed(enemy, enemyCount, frameAtMs, events)
        return events
    }

    private fun feed(side: Side, count: Int?, atMs: Long, events: MutableList<TimerEvent>) {
        if (count == null) {
            side.tracker.observe(null, atMs)
            if (side.baselineEstablished && atMs - side.lastValidAtMs > config.tuning.invalidBaselineTimeoutMs) {
                side.resetFromStable = side.tracker.stable
                side.resetPendingFrom = side.pendingFrom
                side.tracker.reset()
                side.baselineEstablished = false
                // 无法继续验证 → 悬置候选按已确认处理，计时保留
                side.pendingFrom = null
                side.timer = side.timer.copy(pending = false)
                events += TimerEvent.BaselineReset(side.side, "invalid_frames_timeout")
                    .also { log(it.toString(), atMs) }
            }
            evaluatePending(side, atMs, events)
            return
        }
        side.lastValidAtMs = atMs
        val before = side.tracker.stable
        val after = side.tracker.observe(count, atMs)
        if (after == null || after == before) {
            evaluatePending(side, atMs, events)
            return
        }
        if (!side.baselineEstablished) {
            side.baselineEstablished = true
            side.stableSinceMs = atMs
            side.prevStable = null
            val resetFrom = side.resetFromStable
            val resetPendingFrom = side.resetPendingFrom
            side.resetFromStable = null
            side.resetPendingFrom = null
            if (resetFrom != null) {
                val diff = resetFrom - after
                when {
                    // 盲窗重建发现豆数弹回悬置前的原值 → 盲期内被保留的候选是假掉落，撤单
                    resetPendingFrom != null && after >= resetPendingFrom -> {
                        side.timer = SideTimer(side.side)
                        events += TimerEvent.CandidateCancelled(side.side, resetPendingFrom, after, "blind_rebound")
                            .also { log(it.toString(), atMs) }
                    }
                    // 盲窗内净掉一颗 → 替身发生在不可见期，补发候选（锚在重建首见帧，仍走悬置验证）
                    diff == 1 -> {
                        val anchor = side.tracker.firstObservedAtMs
                        side.lastCandidateAtMs = atMs
                        side.timer = SideTimer(
                            side = side.side, active = true,
                            endAtMs = anchor + config.cooldownMs,
                            suspected = true, eventAtMs = anchor, pending = true,
                        )
                        side.pendingFrom = resetFrom
                        side.pendingTo = after
                        side.pendingUntilMs = atMs + config.tuning.pendingVerifyMs
                        side.pendingAtMs = atMs
                        events += TimerEvent.SubstitutionCandidate(side.side, anchor, resetFrom, after, conflict = false)
                            .also { log("blind_window_recovery ${side.side} $resetFrom->$after", atMs) }
                    }
                    diff > 1 -> {
                        events += TimerEvent.AmbiguousDrop(side.side, resetFrom, after)
                            .also { log("blind_window_multi ${side.side} $resetFrom->$after", atMs) }
                    }
                    // diff <= 0 = 盲窗内涨豆/无变化——涨豆不产事件
                    else -> if (diff < 0) log("blind_window_gain ${side.side} $resetFrom->$after", atMs)
                }
            }
            log("baseline ${side.side}=$after resetFrom=$resetFrom", atMs)
            evaluatePending(side, atMs, events)
            return
        }
        // priorStable = "before" 之前的稳定值；heldMs = before 已驻留时长
        val priorStable = side.prevStable
        val heldMs = atMs - side.stableSinceMs
        side.prevStable = before
        side.stableSinceMs = atMs
        val drop = before!! - after
        when {
            drop == 1 -> {
                if (side.pendingFrom != null) {
                    // 悬置未决又掉豆 = 级联消耗（奥义逐颗耗尽等）——不是单次替身
                    cancelPending(side, atMs, events, "cascade_drop")
                    events += TimerEvent.AmbiguousDrop(side.side, before, after)
                        .also { log(it.toString(), atMs) }
                    return
                }
                if (heldMs < config.tuning.minStableBeforeDropMs && after == priorStable) {
                    // 高位短驻后落回原稳定值：闪光误点亮抖动，不是替身
                    events += TimerEvent.WobbleIgnored(side.side, before, after)
                        .also { log(it.toString(), atMs) }
                    return
                }
                val dedupe = side.lastCandidateAtMs != Long.MIN_VALUE &&
                    atMs - side.lastCandidateAtMs < config.tuning.dedupeMs
                if (dedupe) {
                    log("dedupe ${side.side} $before->$after", atMs)
                    return
                }
                val conflict = side.timer.active && !side.timer.pending &&
                    side.timer.remaining(atMs) > config.cooldownMs * config.tuning.conflictWindowFraction
                // 锚定在首次观测到该计数的帧——消除确认期带来的计时滞后
                val anchor = side.tracker.firstObservedAtMs
                side.lastCandidateAtMs = atMs
                side.timer = SideTimer(
                    side = side.side,
                    active = true,
                    endAtMs = anchor + config.cooldownMs,
                    suspected = true,
                    conflict = conflict,
                    eventAtMs = anchor,
                    pending = true,
                )
                side.pendingFrom = before
                side.pendingTo = after
                side.pendingUntilMs = atMs + config.tuning.pendingVerifyMs
                side.pendingAtMs = atMs
                events += TimerEvent.SubstitutionCandidate(side.side, anchor, before, after, conflict)
                    .also { log(it.toString(), atMs) }
            }
            drop > 1 -> {
                if (side.pendingFrom != null) cancelPending(side, atMs, events, "multi_drop")
                events += TimerEvent.AmbiguousDrop(side.side, before, after)
                    .also { log(it.toString(), atMs) }
            }
            // drop <= 0（涨豆）不产事件
        }
        evaluatePending(side, atMs, events)
    }

    /** 悬置候选验证：快回弹→撤单（遮挡闪烁）；慢回弹/窗口结束→确认（真实涨豆或真替身）。 */
    private fun evaluatePending(side: Side, atMs: Long, events: MutableList<TimerEvent>) {
        val from = side.pendingFrom ?: return
        val stable = side.tracker.stable
        if (stable != null && stable >= from) {
            if (atMs - side.pendingAtMs < config.tuning.fastReboundCancelMs) {
                cancelPending(side, atMs, events, "count_bounced")
            } else {
                confirmPending(side, atMs, events, stable, "regain")
            }
        } else if (atMs >= side.pendingUntilMs) {
            confirmPending(side, atMs, events, stable ?: -1, "window_end")
        }
    }

    private fun confirmPending(side: Side, atMs: Long, events: MutableList<TimerEvent>, count: Int, via: String) {
        side.pendingFrom = null
        if (side.timer.pending) {
            side.timer = side.timer.copy(pending = false)
            events += TimerEvent.CandidateConfirmed(side.side, count)
                .also { log("$it via=$via", atMs) }
        }
    }

    private fun cancelPending(side: Side, atMs: Long, events: MutableList<TimerEvent>, reason: String) {
        val from = side.pendingFrom ?: return
        side.pendingFrom = null
        if (side.timer.pending) side.timer = SideTimer(side.side)
        events += TimerEvent.CandidateCancelled(side.side, from, side.pendingTo, reason)
            .also { log(it.toString(), atMs) }
    }

    private fun select(side: TimerSide) = if (side == TimerSide.SELF) self else enemy

    private fun log(msg: String, atMs: Long) {
        if (diagnostics.size >= 200) diagnostics.removeFirst()
        diagnostics.addLast("$atMs $msg")
    }

    private fun emit(event: TimerEvent) = log(event.toString(), clock())
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
