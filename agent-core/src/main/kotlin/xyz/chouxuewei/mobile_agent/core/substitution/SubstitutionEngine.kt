package xyz.chouxuewei.mobile_agent.core.substitution

import kotlin.math.max

/** 稳定计数器：只在连续 confirmFrames 帧一致时才确认计数；UNKNOWN 帧不参与。 */
internal class StabilityTracker(private val confirmFrames: Int) {
    private var candidateCount: Int = -1
    private var candidateStreak: Int = 0
    var stable: Int? = null
        private set

    /** 输入本帧观测计数（null = 不可信帧）。返回新确认的稳定计数（含首次基线）。 */
    fun observe(count: Int?): Int? {
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
        self.tracker.reset(); enemy.tracker.reset()
        self.baselineEstablished = false; enemy.baselineEstablished = false
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
            side.tracker.observe(null)
            if (side.baselineEstablished && atMs - side.lastValidAtMs > config.tuning.invalidBaselineTimeoutMs) {
                side.tracker.reset()
                side.baselineEstablished = false
                events += TimerEvent.BaselineReset(side.side, "invalid_frames_timeout")
                    .also { log(it.toString(), atMs) }
            }
            return
        }
        side.lastValidAtMs = atMs
        val before = side.tracker.stable
        val after = side.tracker.observe(count)
        if (after == null || after == before) return
        if (!side.baselineEstablished) {
            side.baselineEstablished = true
            log("baseline ${side.side}=$after", atMs)
            return
        }
        val drop = before!! - after
        when {
            drop == 1 -> {
                val dedupe = side.lastCandidateAtMs != Long.MIN_VALUE &&
                    atMs - side.lastCandidateAtMs < config.tuning.dedupeMs
                if (dedupe) {
                    log("dedupe ${side.side} $before->$after", atMs)
                    return
                }
                val conflict = side.timer.active &&
                    side.timer.remaining(atMs) > config.cooldownMs * config.tuning.conflictWindowFraction
                side.lastCandidateAtMs = atMs
                side.timer = SideTimer(
                    side = side.side,
                    active = true,
                    endAtMs = atMs + config.cooldownMs,
                    suspected = true,
                    conflict = conflict,
                    eventAtMs = atMs,
                )
                events += TimerEvent.SubstitutionCandidate(side.side, atMs, before, after, conflict)
                    .also { log(it.toString(), atMs) }
            }
            drop > 1 -> {
                events += TimerEvent.AmbiguousDrop(side.side, before, after)
                    .also { log(it.toString(), atMs) }
            }
            // drop <= 0（涨豆）不产事件
        }
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
