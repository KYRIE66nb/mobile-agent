package xyz.chouxuewei.mobile_agent.core.substitution

import kotlin.math.max

/**
 * 稳定计数器：连续 confirmFrames 帧一致且相邻观察间隔 ≤ maxGapMs 才确认计数；
 * UNKNOWN 帧与超时断帧都会打断连续证据（三次相隔数秒的相同读数不是"连续稳定"）。
 */
internal class StabilityTracker(
    private val confirmFrames: Int,
    private val maxGapMs: Long,
) {
    private var candidateCount: Int = -1
    private var candidateStreak: Int = 0
    private var lastObservedAtMs: Long = Long.MIN_VALUE
    var stable: Int? = null
        private set

    /** 当前候选计数首次出现的时间——用于把计时锚定在"豆数实际变化"而非"确认"时刻。 */
    var firstObservedAtMs: Long = 0
        private set

    /** 输入本帧观测计数（null = 不可信帧）。返回新确认的稳定计数（含首次基线）。 */
    fun observe(count: Int?, atMs: Long): Int? {
        // 相邻观察间隔超窗 → 连续证据断裂，候选重新累计（stable 基线不受影响，
        // 基线失效由引擎按盲窗时长判定）
        val gapOk = atMs - lastObservedAtMs <= maxGapMs
        lastObservedAtMs = atMs
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
        if (!gapOk) {
            candidateCount = count
            candidateStreak = 1
            firstObservedAtMs = atMs
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
        lastObservedAtMs = Long.MIN_VALUE
    }
}

sealed class TimerEvent {
    /** 某侧稳定豆数 n→n−1：疑似替身候选。conflict=候选落在未走完的冷却内（不接管正式计时）。 */
    data class SubstitutionCandidate(
        val side: TimerSide,
        val atMs: Long,
        val fromCount: Int,
        val toCount: Int,
        val conflict: Boolean,
    ) : TimerEvent()

    /**
     * 一次掉多豆/异常跳变/盲窗后净掉豆——不自动计时，需要人工判断。
     * 盲窗恢复场景下 fromCount 是盲窗前最后的稳定值，触发时刻不可知。
     */
    data class AmbiguousDrop(val side: TimerSide, val fromCount: Int, val toCount: Int) : TimerEvent()

    /** 悬置候选被推翻：豆数回弹/级联消耗/盲窗/重置——只撤销候选，不影响已接受计时。 */
    data class CandidateCancelled(
        val side: TimerSide,
        val fromCount: Int,
        val toCount: Int,
        val reason: String,
    ) : TimerEvent()

    /** 悬置候选证据被接受：回弹窗内豆数未回到原值且验证期有有效观察。 */
    data class CandidateConfirmed(val side: TimerSide, val count: Int) : TimerEvent()

    /** 高位短驻后落回更早稳定值——判定为闪光误点亮抖动，不产生候选。 */
    data class WobbleIgnored(val side: TimerSide, val fromCount: Int, val toCount: Int) : TimerEvent()

    /** 基线重建（换局/校准变化/长时间无效帧/暂停恢复）。 */
    data class BaselineReset(val side: TimerSide?, val reason: String) : TimerEvent()
}

/**
 * 双侧替身推断引擎。每帧输入两侧的稳定前计数估计（Int?，null=不可信）与战斗场景判定。
 *
 * 每侧三层状态分离：
 * - 观察基线：StabilityTracker 的连续稳定计数；
 * - 未决候选：掉豆证据待回弹验证，可提前向 UI 展示"推断中"，但绝不改写已接受计时；
 * - 已接受计时：单调 endAt 推进，只有证据被接受的候选才能替换，重置/盲窗/撤销不动它。
 *
 * 线程安全：引擎不是线程安全的——帧观察、暂停、重置、换边、配置切换必须由调用方
 * 在同一串行上下文执行（服务侧统一走 engineLock）。
 */
class SubstitutionEngine(
    private val config: TimerConfig,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** 未决候选：掉豆证据等待回弹验证。验证期需要"新的有效观察"而非纯时间流逝。 */
    private class PendingCandidate(
        val fromCount: Int,
        val toCount: Int,
        /** 候选计数首次观测帧时刻——确认后作为正式计时锚点。 */
        val anchorAtMs: Long,
        /** 候选确认帧时刻——快回弹判定基准。 */
        val createdAtMs: Long,
        /** 回弹验证截止（需配合有效证据才确认）。 */
        val untilMs: Long,
        /** 创建时已接受计时仍在冷却：冲突候选永不接管正式计时。 */
        val contested: Boolean,
    ) {
        /** 候选创建后是否收到过新的有效观察（确认候选的证据，不是时钟）。 */
        var evidenceSeen = false
    }

    private inner class Side(
        val side: TimerSide,
        confirmFrames: Int,
    ) {
        val tracker = StabilityTracker(confirmFrames, config.tuning.maxObservationGapMs)
        /** 已接受计时：只被确认的候选替换、被手动撤销清空。 */
        var accepted = SideTimer(side)
        /** 未决候选：独立生命周期——确认升级、撤销消亡，不连坐正式计时。 */
        var candidate: PendingCandidate? = null
        var lastCandidateAtMs: Long = Long.MIN_VALUE
        var baselineEstablished = false
        /** 当前稳定计数确认的时点——"高位短驻"判抖动用。 */
        var stableSinceMs: Long = 0
        /** 上一个稳定计数（before 之前的那一个）——落回它说明是抖动不是替身。 */
        var prevStable: Int? = null
        /** 最近一次有效观察时刻（null 帧不算）——盲窗判定用。 */
        var lastValidAtMs: Long = Long.MIN_VALUE
        /** 盲窗使基线失效前最后的稳定值——恢复后对比报歧义，不补精确计时。 */
        var lostBaseline: Int? = null

        /** 展示态：冷却中的正式计时 > 未决候选"推断中" > 已到期计时。 */
        fun display(nowMs: Long): SideTimer {
            val c = candidate
            return when {
                accepted.remaining(nowMs) > 0 -> accepted
                c != null -> SideTimer(
                    side = side, active = true,
                    endAtMs = c.anchorAtMs + config.cooldownMs,
                    suspected = true, conflict = c.contested,
                    eventAtMs = c.anchorAtMs, pending = true,
                )
                else -> accepted
            }
        }
    }

    private val self = Side(TimerSide.SELF, config.tuning.confirmFrames)
    private val enemy = Side(TimerSide.ENEMY, config.tuning.confirmFrames)
    private val diagnostics = ArrayDeque<String>(200)
    /** 最近一次接受的帧时刻——重放/乱序帧（旧会话、迟到的旧布局帧）直接丢弃。 */
    private var lastFrameAtMs = Long.MIN_VALUE

    var paused = false
        private set

    fun timers(nowMs: Long = clock()): Pair<SideTimer, SideTimer> =
        self.display(nowMs) to enemy.display(nowMs)

    fun diagnostics(): List<String> = diagnostics.toList()

    fun pause() { paused = true }
    fun resume() {
        paused = false
        // 暂停期间漏掉的帧不可信——回来时重建基线而不是沿用旧计数。
        resetBaseline("paused_resume")
    }

    fun resetBaseline(reason: String) {
        for (s in listOf(self, enemy)) {
            s.tracker.reset()
            s.baselineEstablished = false
            s.stableSinceMs = 0
            s.prevStable = null
            s.lostBaseline = null
            // 重置=重新武装：去重窗一并清掉，否则重置后 800ms 内被 dedupe 吞掉
            s.lastCandidateAtMs = Long.MIN_VALUE
            // 未决候选一律撤销——重建基线不得把未验证证据升级为已接受计时。
            // 已接受计时按单调 endAt 持续推进，不受基线重置影响。
            s.candidate?.let { c ->
                s.candidate = null
                if (c.contested) s.accepted = s.accepted.copy(conflict = false)
                log(TimerEvent.CandidateCancelled(s.side, c.fromCount, c.toCount, "reset:$reason")
                    .toString(), clock())
            }
        }
        emit(TimerEvent.BaselineReset(null, reason))
    }

    /** 手动撤销一侧全部推断状态：计时、候选、基线、去重——纠错后从头建立。 */
    fun cancelTimer(side: TimerSide) {
        val s = select(side)
        s.accepted = SideTimer(side)
        s.candidate = null
        s.tracker.reset()
        s.baselineEstablished = false
        s.stableSinceMs = 0
        s.prevStable = null
        s.lostBaseline = null
        s.lastCandidateAtMs = Long.MIN_VALUE
    }

    /**
     * 输入一帧两侧观测计数（已分类统计后的数量，null=不可信）。
     * battle=false 表示帧不是战斗画面（加载/菜单/结算）：两侧都按无效观察处理——
     * 不产候选、不提供候选确认证据，只推进盲窗时钟。
     * 返回本帧产生的事件。帧时间用单调时钟 ms，必须单调递增。
     */
    fun onFrame(
        selfCount: Int?,
        enemyCount: Int?,
        frameAtMs: Long = clock(),
        battle: Boolean = true,
    ): List<TimerEvent> {
        if (paused) return emptyList()
        if (frameAtMs <= lastFrameAtMs) {
            log("stale frame dropped @$frameAtMs", frameAtMs)
            return emptyList()
        }
        lastFrameAtMs = frameAtMs
        val events = mutableListOf<TimerEvent>()
        feed(self, if (battle) selfCount else null, frameAtMs, events)
        feed(enemy, if (battle) enemyCount else null, frameAtMs, events)
        return events
    }

    private fun feed(side: Side, count: Int?, atMs: Long, events: MutableList<TimerEvent>) {
        // 盲窗判定：距上次有效观察超过阈值 → 旧基线与悬置候选不可信。
        // 覆盖"持续无效帧"、"没收到新帧"与"非战斗画面"三种形态——有效证据缺席时长才是本质。
        if ((side.baselineEstablished || side.candidate != null) &&
            atMs - side.lastValidAtMs > config.tuning.invalidBaselineTimeoutMs
        ) {
            invalidateBaseline(side, atMs, events)
        }
        if (count == null) {
            side.tracker.observe(null, atMs)
            return
        }
        side.lastValidAtMs = atMs
        side.candidate?.let { it.evidenceSeen = true }
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
            val lost = side.lostBaseline
            side.lostBaseline = null
            log("baseline ${side.side}=$after", atMs)
            if (lost != null && after < lost) {
                // 盲窗内疑似消耗：触发时刻不可知——报歧义而非补发"恢复画面时刻"的精确冷却
                events += TimerEvent.AmbiguousDrop(side.side, lost, after)
                    .also { log("post_blind $it", atMs) }
            }
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
                if (side.candidate != null) {
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
                // 正式计时仍在冷却（按 remaining 判定，不靠归零后仍为 true 的 active 标志）→
                // 冲突候选：记录证据但默认不重启、不延长原计时
                val contested = side.accepted.remaining(atMs) > 0
                if (contested) side.accepted = side.accepted.copy(conflict = true)
                val anchor = side.tracker.firstObservedAtMs
                side.lastCandidateAtMs = atMs
                side.candidate = PendingCandidate(
                    fromCount = before, toCount = after,
                    anchorAtMs = anchor, createdAtMs = atMs,
                    untilMs = atMs + config.tuning.pendingVerifyMs,
                    contested = contested,
                )
                events += TimerEvent.SubstitutionCandidate(side.side, anchor, before, after, contested)
                    .also { log(it.toString(), atMs) }
            }
            drop > 1 -> {
                if (side.candidate != null) cancelPending(side, atMs, events, "multi_drop")
                events += TimerEvent.AmbiguousDrop(side.side, before, after)
                    .also { log(it.toString(), atMs) }
            }
            // drop <= 0（涨豆）不产事件
        }
        evaluatePending(side, atMs, events)
    }

    /** 盲窗失效：旧基线作废、悬置候选撤销（证据链已断），已接受计时不动。 */
    private fun invalidateBaseline(side: Side, atMs: Long, events: MutableList<TimerEvent>) {
        side.lostBaseline = side.tracker.stable
        side.tracker.reset()
        side.baselineEstablished = false
        side.stableSinceMs = 0
        side.prevStable = null
        cancelPending(side, atMs, events, "blind_window")
        events += TimerEvent.BaselineReset(side.side, "blind_window")
            .also { log(it.toString(), atMs) }
    }

    /**
     * 悬置候选验证：快回弹→撤单（遮挡闪烁）；慢回弹→确认（真实涨豆）；
     * 窗口结束且有新的有效观察→确认。纯时间流逝（UNKNOWN/无帧/非战斗画面）不确认。
     */
    private fun evaluatePending(side: Side, atMs: Long, events: MutableList<TimerEvent>) {
        val c = side.candidate ?: return
        val stable = side.tracker.stable
        if (stable != null && stable >= c.fromCount) {
            if (atMs - c.createdAtMs < config.tuning.fastReboundCancelMs) {
                cancelPending(side, atMs, events, "count_bounced")
            } else {
                confirmPending(side, atMs, events, stable, "regain")
            }
        } else if (atMs >= c.untilMs && c.evidenceSeen) {
            confirmPending(side, atMs, events, stable ?: -1, "window_end")
        }
        // 证据不足时候选保持悬置：由盲窗失效路径负责兜底撤销，不靠等待升级
    }

    private fun confirmPending(side: Side, atMs: Long, events: MutableList<TimerEvent>, count: Int, via: String) {
        val c = side.candidate ?: return
        side.candidate = null
        if (!c.contested) {
            // 证据被接受 → 候选升级为正式计时，锚定豆数首次观测帧
            side.accepted = SideTimer(
                side = side.side, active = true,
                endAtMs = c.anchorAtMs + config.cooldownMs,
                suspected = true, conflict = false,
                eventAtMs = c.anchorAtMs, pending = false,
            )
        }
        // contested：正式计时原样推进，conflict 标记已记录——冲突证据不接管冷却
        events += TimerEvent.CandidateConfirmed(side.side, count)
            .also { log("$it via=$via", atMs) }
    }

    private fun cancelPending(side: Side, atMs: Long, events: MutableList<TimerEvent>, reason: String) {
        val c = side.candidate ?: return
        side.candidate = null
        if (c.contested) {
            // 冲突候选被推翻 = 该次掉豆证据不足——摘掉冲突标记，正式计时始终不受影响
            side.accepted = side.accepted.copy(conflict = false)
        }
        events += TimerEvent.CandidateCancelled(side.side, c.fromCount, c.toCount, reason)
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
