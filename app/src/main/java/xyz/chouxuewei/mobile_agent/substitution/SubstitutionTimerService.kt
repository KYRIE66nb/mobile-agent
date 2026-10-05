package xyz.chouxuewei.mobile_agent.substitution

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.MainActivity
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.LaunchEvent
import xyz.chouxuewei.mobile_agent.core.substitution.SideTimer
import xyz.chouxuewei.mobile_agent.core.substitution.SubstitutionEngine
import xyz.chouxuewei.mobile_agent.core.substitution.TimerConfig
import xyz.chouxuewei.mobile_agent.core.substitution.TimerEvent
import xyz.chouxuewei.mobile_agent.core.substitution.TimerServiceState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService
import xyz.chouxuewei.mobile_agent.device.capture.AccessibilityFrameSource
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess
import xyz.chouxuewei.mobile_agent.device.capture.projection.ProjectionCapture
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/**
 * 替身计时前台服务：持有 MediaProjection 采集会话 + 检测引擎 + 悬浮窗。
 * 只做本地观察/推断/计时——不注入输入、不联网、不读内存。
 */
class SubstitutionTimerService : Service() {

    data class UiState(
        val state: TimerServiceState = TimerServiceState.DISABLED,
        val detail: String = "",
        val selfTimer: SideTimer = SideTimer(TimerSide.SELF),
        val enemyTimer: SideTimer = SideTimer(TimerSide.ENEMY),
        val selfDots: Int = -1,
        val enemyDots: Int = -1,
        val hudValid: Boolean = false,
        val gameInForeground: Boolean = false,
        val showSelf: Boolean = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var capture: ProjectionCapture? = null
    /** 无障碍截屏兜底通道：录屏抢投影时启用，与 MediaProjection 完全独立。 */
    private var a11ySource: AccessibilityFrameSource? = null
    /** 切通道时 suppress capture 的"正常停止"回调——不能当会话丢失处理。 */
    private var suppressCaptureClose = false
    private var projectionCallback: android.media.projection.MediaProjection.Callback? = null
    /** 采集线程最近一帧到达时刻——VirtualDisplay 被系统夺走时帧流静默，靠它兜底。 */
    @Volatile private var lastFrameAt = 0L
    private var captureRestarts = 0
    private var captureRestartWindowStart = 0L
    private var projection: android.media.projection.MediaProjection? = null
    private var engine: SubstitutionEngine? = null
    private var config: TimerConfig? = null
    private var overlay: SubstitutionOverlay? = null
    private var foregroundWatch: Job? = null
    private var ticker: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_RESET -> {
                AgentLog.i(TAG) { "manual reset engine=${engine != null} started=$started paused=${engine?.paused}" }
                // 重置=修正语义：暂停态也一并解除，否则基线永不重建看起来像"不再计算"
                if (engine?.paused == true) {
                    engine?.resume()
                    SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.Resume)
                    updateNotification()
                }
                engine?.cancelTimer(TimerSide.SELF)
                engine?.cancelTimer(TimerSide.ENEMY)
                engine?.resetBaseline("manual_reset")
                pushState(detail = "已重置计时，重新建立基线")
                publishTimers()
            }
            ACTION_SWAP -> {
                val cfg = config
                if (cfg != null) {
                    // 显式选边：EXTRA_SELF_LEFT=true → 我方在左（swapSides=false）
                    val selfOnLeft = intent?.getBooleanExtra(EXTRA_SELF_LEFT, !cfg.swapSides)
                        ?: !cfg.swapSides
                    val targetSwap = !selfOnLeft
                    if (targetSwap != cfg.swapSides) {
                        val app = application as PrototypeApplication
                        scope.launch { app.substitutionSettings.setSwapSides(targetSwap) }
                    }
                    AgentLog.i(TAG) { "side select selfOnLeft=$selfOnLeft swapSides=$targetSwap" }
                    pushState(detail = if (selfOnLeft) "我方在左 · 敌方在右" else "我方在右 · 敌方在左")
                }
            }
            ACTION_STOP -> stopSession(userInitiated = true)
            else -> stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (started) return // 幂等：多次 START 复用同一会话
        // FGS 契约：无论授权结果如何都必须先 startForeground，否则系统按超时杀进程
        startForegroundWithNotification()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            AgentLog.w(TAG) { "consent invalid: code=$resultCode data=${data != null}" }
            pushState(detail = "录屏授权被拒绝")
            SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.PermissionDenied)
            stopSelf()
            return
        }
        val app = application as PrototypeApplication
        scope.launch {
            config = app.substitutionSettings.current()
            val cfg = config ?: return@launch
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val proj = manager.getMediaProjection(resultCode, data)
                    ?: error("MediaProjection 创建失败")
                projection = proj
                // 官方要求：创建捕获前先注册停止回调
                val cb = object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        // 统一走裁决链：先无障碍兜底、后台则挂起回前台再判——
                        // 系统收回投影≠会话终结（录屏抢占/息屏都会触发）
                        onCaptureClosed("系统已停止屏幕共享")
                    }
                }
                projectionCallback = cb
                proj.registerCallback(cb, null)
                engine = SubstitutionEngine(cfg, clock = SystemClock::elapsedRealtime)
                SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.PermissionGranted)
                if (cfg.calibrated) {
                    SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.Calibrated)
                    pushState(detail = "等待进入目标游戏")
                } else {
                    pushState(detail = "请在设置页完成豆槽校准后开始识别")
                }
                overlay = SubstitutionOverlay(this@SubstitutionTimerService, app.substitutionSettings)
                startCapture(proj)
                startForegroundWatch()
                startTicker()
                startCaptureWatchdog()
                started = true
                SubstitutionTimerCoordinator.onServiceStarted()
                // 协调器状态机是唯一事实源：服务只镜像，不再各推各的状态
                scope.launch {
                    SubstitutionTimerCoordinator.state.collect { s ->
                        if (s != _uiState.value.state) {
                            AgentLog.i(TAG) { "state ${_uiState.value.state} -> $s" }
                        }
                        _uiState.update { it.copy(state = s) }
                        updateNotification()
                    }
                }
                // 配置变更热更新：仅检测相关字段变化才重建引擎（悬浮窗位置等不触发）
                scope.launch {
                    app.substitutionSettings.config.collect { newCfg ->
                        val old = config
                        config = newCfg
                        if (old != null && !old.calibrated && newCfg.calibrated &&
                            _uiState.value.gameInForeground
                        ) {
                            // 游戏中完成校准：立即重评估前台状态，不必等离开再回来
                            SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.GameForeground)
                            scope.launch { overlay?.show() }
                        }
                        if (old != null && old.swapSides != newCfg.swapSides) {
                            // 换边：身份归属翻转，旧计时/基线全部作废重建
                            engine?.cancelTimer(TimerSide.SELF)
                            engine?.cancelTimer(TimerSide.ENEMY)
                            engine?.resetBaseline("side_swap")
                            publishTimers()
                        }
                        if (old == null || old.layout != newCfg.layout ||
                            old.tuning != newCfg.tuning || old.cooldownMs != newCfg.cooldownMs
                        ) {
                            engine = SubstitutionEngine(newCfg, clock = SystemClock::elapsedRealtime)
                            publishTimers()
                        }
                    }
                }
            } catch (t: Throwable) {
                pushState(detail = "采集启动失败：${t.message}")
                SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.Failed(t.message ?: "start failed"))
            }
        }
    }

    private fun startCapture(proj: android.media.projection.MediaProjection) {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let {
                metrics.widthPixels = it.width(); metrics.heightPixels = it.height()
            }
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        }
        lastFrameAt = SystemClock.elapsedRealtime()
        capture = ProjectionCapture().also { c ->
            c.start(
                proj,
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                onFrame = ::onFrame,
                onClosed = { reason -> onCaptureClosed(reason) },
            )
        }
    }

    /** 后台期捕获死亡的挂起标记：回前台时优先切无障碍兜底再丢会话。 */
    private var captureDead = false

    /** 捕获会话关闭：令牌真死前先看无障碍兜底能不能接住（录屏共存），不行才丢会话。 */
    private fun onCaptureClosed(reason: String) {
        if (suppressCaptureClose) return
        scope.launch {
            if (started && Build.VERSION.SDK_INT >= 34 &&
                AgentAccessibilityService.connected != null) {
                AgentLog.w(TAG) { "projection lost ($reason), switching to a11y capture" }
                switchToA11yCapture()
            } else if (started && !_uiState.value.gameInForeground) {
                // 后台/息屏时捕获死亡（投影被系统收走、屏幕不渲染）是常态，
                // 无障碍此刻多半还没重连——先挂起，回前台由前台监听裁决
                captureDead = true
                AgentLog.w(TAG) {
                    "capture lost in background ($reason), deferred " +
                        "(a11y=${AgentAccessibilityService.connected != null})"
                }
                pushState(detail = "捕获已断开，回到游戏时自动恢复")
            } else {
                onSessionLost(reason)
            }
        }
    }

    /** 采集线程回调：监视中按 frameIntervalMs 采样判定；缩略预览帧节流产出供校准页。 */
    private var lastSampleAt = 0L
    private var lastObservedLogAt = 0L

    private fun onFrame(access: FrameAccess) {
        lastFrameAt = SystemClock.elapsedRealtime()
        val cfg = config ?: return
        val eng = engine ?: return
        val monitoring = !eng.paused && uiState.value.state == TimerServiceState.MONITORING
        val inGame = _uiState.value.gameInForeground

        // 预览帧只在"游戏在前台"时产出：离开游戏即冻结，保住最后一帧游戏画面供校准；
        // 校准页开着(2fps)比监视中(1fps)刷新快。离开游戏且无预览需求 → 整帧不处理。
        val now = SystemClock.elapsedRealtime()
        if (!inGame && !previewEnabled) return
        if (inGame && now - lastPreviewAt > if (previewEnabled) 500 else 1000) {
            lastPreviewAt = now
            publishPreview(access.snapshot(PREVIEW_WIDTH))
        }
        if (!monitoring) return
        // 按配置采样间隔节流：投影帧率(~160fps)远高于检测需求
        if (now - lastSampleAt < cfg.frameIntervalMs) return
        lastSampleAt = now

        val classifier = classifierOf(cfg)
        val cellDiag = AgentLog.enabled && now - lastObservedLogAt > 2000
        fun countDots(side: TimerSide): Int? {
            val cells = physicalCells(cfg, side, access.width, access.height)
            val states = ArrayList<DotState>(cells.size)
            val marks = StringBuilder()
            val inset = (1f - cfg.tuning.cellInnerFraction) / 2f
            for (cell in cells) {
                // 只采样格子中心区：避开豆间分隔线与 HUD 边缘混入的背景
                val w = cell[2] - cell[0]; val h = cell[3] - cell[1]
                val l = (cell[0] + w * inset).toInt()
                val t = (cell[1] + h * inset).toInt()
                val r = (cell[2] - w * inset).toInt()
                val b = (cell[3] - h * inset).toInt()
                val pixels = access.sample(l, t, r, b, cfg.tuning.sampleGrid)
                val result = classifier.classify(pixels, side)
                if (cellDiag) marks.append(
                    when (result.state) {
                        DotState.LIT -> 'L'; DotState.EMPTY -> 'E'; DotState.UNKNOWN -> 'U'
                    } + "(%.2f/%.2f)".format(result.litRatio, result.dimRatio),
                ).append(' ')
                states += result.state
            }
            if (cellDiag) {
                AgentLog.d(TAG) { "cells $side=${marks.trim()}" }
            }
            // 规范豆型校验：点亮的豆必须连续且锚定某一端——左锚右锚都算合法。
            // 实测同一物理槽位的锚定方向随分边翻转（训练场自方在左读 L L L E，
            // 实战敌方分到左槽读 E L L L）——方向假设写死必然有一侧瞎。
            // 含 UNKNOWN、洞形（暗-亮-暗）、居中块的帧整帧丢弃，不产状态变化。
            val firstLit = states.indexOfFirst { it == DotState.LIT }
            val lastLit = states.indexOfLast { it == DotState.LIT }
            val anchored = firstLit == 0 || lastLit == states.size - 1
            val contiguous = firstLit >= 0 &&
                (firstLit..lastLit).all { states[it] == DotState.LIT }
            val canonical = states.none { it == DotState.UNKNOWN } &&
                (firstLit < 0 || (anchored && contiguous))
            // 非规范 → null：引擎侧整帧忽略，稳定计数跨过噪声期继续持有
            if (!canonical) return null
            return states.count { it == DotState.LIT }
        }

        val selfCount = countDots(TimerSide.SELF)
        val enemyCount = countDots(TimerSide.ENEMY)
        // 双零跳帧：两侧"全灭"同时出现 = 回合间/转场豆槽整排消失（暗背景命中
        // 暗盒读出的伪 0），不是真实零豆状态——整帧忽略，计数跨过转场保持。
        // 单侧 0 照常喂（最后一颗豆的替身仍能触发）。
        val bothDark = selfCount == 0 && enemyCount == 0
        val events = eng.onFrame(
            if (bothDark) null else selfCount,
            if (bothDark) null else enemyCount,
            now,
        )
        _uiState.update {
            it.copy(
                selfDots = selfCount ?: -1,
                enemyDots = enemyCount ?: -1,
                hudValid = selfCount != null && enemyCount != null,
            )
        }
        // 诊断节流：每 ~2s 记一次观测计数（只记数字，不记画面）
        if (now - lastObservedLogAt > 2000) {
            lastObservedLogAt = now
            AgentLog.d(TAG) { "observe self=$selfCount enemy=$enemyCount" }
        }
        for (e in events) {
            AgentLog.i(TAG) { "event $e" }
            when (e) {
                is TimerEvent.AmbiguousDrop ->
                    _uiState.update { it.copy(detail = "疑似多豆变化，未自动计时") }
                is TimerEvent.CandidateCancelled ->
                    _uiState.update { it.copy(detail = "候选已撤销（豆数回弹，非替身）") }
                is TimerEvent.WobbleIgnored ->
                    _uiState.update { it.copy(detail = "豆数短暂抖动，已忽略") }
                else -> Unit
            }
        }
        if (events.isNotEmpty()) {
            publishTimers()
            dumpEventFrame(access, events)
        }
    }

    private var dumpedFrames = 0

    /** 事件帧取证：候选/确认/撤销时刻的原始画面落盘，"替身没算"纠纷时
     *  能直接回看分类器当时的输入，而不是靠口述对时间。 */
    private fun dumpEventFrame(access: FrameAccess, events: List<TimerEvent>) {
        if (dumpedFrames >= 80) return
        dumpedFrames++
        val cfg = config ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                // 采样格叠画到取证帧：直接看出格子有没有对准豆
                val bmp = access.snapshot(640)
                val scale = bmp.width.toFloat() / access.width
                val canvas = android.graphics.Canvas(bmp)
                val paint = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 2f
                }
                for (side in listOf(TimerSide.SELF, TimerSide.ENEMY)) {
                    paint.color = if (side == TimerSide.SELF) 0xFF81C784.toInt() else 0xFFFF7043.toInt()
                    for (c in physicalCells(cfg, side, access.width, access.height)) {
                        canvas.drawRect(
                            c[0] * scale, c[1] * scale, c[2] * scale, c[3] * scale, paint,
                        )
                    }
                }
                val dir = File(filesDir, "subtimer_frames").apply { mkdirs() }
                val kind = events.first()::class.simpleName ?: "event"
                val f = File(dir, "ev_${System.currentTimeMillis()}_$kind.jpg")
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, f.outputStream())
                bmp.recycle()
                AgentLog.i(TAG) { "event frame saved ${f.name} (${events.joinToString { it::class.simpleName!! }})" }
            }
        }
    }

    /** 槽位与身份解耦：实战我方可能分到右侧——swapSides 时我方读右槽。 */
    private fun physicalCells(cfg: TimerConfig, logical: TimerSide, w: Int, h: Int): List<IntArray> {
        val onLeft = (logical == TimerSide.SELF) != cfg.swapSides
        return cfg.layout.dotCells(
            if (onLeft) TimerSide.SELF else TimerSide.ENEMY, w, h,
        )
    }

    private var classifier: DotClassifier? = null
    private var classifierConfig = 0

    private fun classifierOf(cfg: TimerConfig): DotClassifier {
        val key = cfg.tuning.hashCode()
        if (classifier == null || classifierConfig != key) {
            classifier = DotClassifier(cfg.tuning)
            classifierConfig = key
        }
        return classifier!!
    }

    private fun publishTimers() {
        val eng = engine ?: return
        val now = SystemClock.elapsedRealtime()
        val (selfTimer, enemyTimer) = eng.timers(now)
        _uiState.update {
            it.copy(selfTimer = selfTimer, enemyTimer = enemyTimer)
        }
        val showSelf = config?.showSelfTimer ?: true
        val st = _uiState.value
        // 悬浮窗 View 只能在主线程操作
        scope.launch {
            overlay?.updateTimers(
                selfTimer, enemyTimer, showSelf,
                paused = eng.paused,
                monitoring = st.state == TimerServiceState.MONITORING,
                selfDots = st.selfDots, enemyDots = st.enemyDots,
            )
        }
    }

    /** 帧流看门狗：MONITORING+游戏前台却持续无帧 = VirtualDisplay 被夺走
     *  （系统录屏抢投影，MIUI 单投影互斥）或采集线程死亡。
     *  先原地重建 VD 两次；仍饿则切无障碍截屏通道（与投影完全独立、与录屏共存）。 */
    private fun startCaptureWatchdog() {
        scope.launch {
            while (true) {
                delay(1000)
                val state = _uiState.value
                if (!started || state.state != TimerServiceState.MONITORING ||
                    !state.gameInForeground) continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 3000) continue
                val a11y = a11ySource
                if (a11y != null) {
                    // 无障碍通道已激活却饿：服务断连→丢会话，否则重建帧源
                    if (AgentAccessibilityService.connected == null) {
                        AgentLog.e(TAG) { "a11y capture starved and service disconnected" }
                        onSessionLost("无障碍服务被系统关闭")
                        break
                    }
                    AgentLog.w(TAG) { "a11y capture starved ${now - lastFrameAt}ms, restarting source" }
                    lastFrameAt = now
                    a11y.stop()
                    a11ySource = null
                    startA11yCapture()
                    continue
                }
                val c = capture ?: continue
                if (now - captureRestartWindowStart > 60_000) {
                    captureRestartWindowStart = now; captureRestarts = 0
                }
                captureRestarts++
                val hasA11y = Build.VERSION.SDK_INT >= 34 &&
                    AgentAccessibilityService.connected != null
                when {
                    // 重建两次仍被抢 → 有备用通道直接切，不再跟录屏互抢
                    captureRestarts > 2 && hasA11y -> {
                        AgentLog.w(TAG) { "VD restart failed twice, switching to accessibility capture" }
                        switchToA11yCapture()
                    }
                    captureRestarts > 3 -> {
                        AgentLog.e(TAG) { "capture starved: restarts failed, session lost" }
                        onSessionLost("画面捕获被系统中断且无法恢复")
                        break
                    }
                    else -> {
                        AgentLog.w(TAG) { "capture starved ${now - lastFrameAt}ms, restarting VD (#$captureRestarts)" }
                        lastFrameAt = now
                        c.restart()
                    }
                }
            }
        }
    }

    /** 放弃投影通道，切换到无障碍截屏（系统录屏共存模式）。 */
    private fun switchToA11yCapture() {
        suppressCaptureClose = true
        captureDead = false
        // 先摘掉 onStop 监听——stop() 会触发它，不能误当会话丢失
        projectionCallback?.let { cb ->
            runCatching { projection?.unregisterCallback(cb) }
        }
        capture?.stop(); capture = null
        projection = null // 令牌让位——不再持有死投影
        startA11yCapture()
        pushState(detail = "已切换无障碍捕获（可与录屏共存）")
    }

    private fun startA11yCapture() {
        val svc = AgentAccessibilityService.connected ?: return
        val cfg = config ?: return
        val metrics = resources.displayMetrics
        lastFrameAt = SystemClock.elapsedRealtime()
        a11ySource = AccessibilityFrameSource(
            svc, android.view.Display.DEFAULT_DISPLAY, cfg.gamePackage,
            metrics.widthPixels, metrics.heightPixels,
            onFrame = ::onFrame,
            onClosed = { reason -> onSessionLost(reason) },
        ).also { it.start() }
        AgentLog.i(TAG) { "a11y capture started pkg=${cfg.gamePackage}" }
    }

    /** 每 ~300ms 刷新倒计时显示（刷新频率与检测频率解耦）。 */
    private fun startTicker() {
        ticker = scope.launch {
            while (true) {
                delay(100)
                publishTimers()
            }
        }
    }

    /** 前台包名轮询：只在会话存活期间进行。 */
    private fun startForegroundWatch() {
        val usm = getSystemService(UsageStatsManager::class.java) ?: run {
            pushState(detail = "缺少使用情况访问权限，无法检测游戏前台状态")
            return
        }
        foregroundWatch = scope.launch(Dispatchers.IO) {
            var lastKnown: String? = null
            while (true) {
                val cfg = config ?: break
                // 最近窗口无事件时保持上次判定——切换事件只发生在跳转瞬间，
                // 短窗口查空不等于"离开了游戏"
                val pkg = currentForegroundPackage(usm) ?: lastKnown
                if (pkg != null) lastKnown = pkg
                val inGame = cfg.gamePackage.isNotBlank() && pkg == cfg.gamePackage
                // 自愈：已在游戏内但状态停在 WAITING（如 PAUSED→Resume 后前台包未变化，
                // 不会再发 GameForeground）→ 每轮轮询补发，转移本身是幂等的
                if (inGame && _uiState.value.state == TimerServiceState.WAITING_FOR_GAME) {
                    SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.GameForeground)
                }
                // 后台期捕获死亡的延迟裁决：回游戏时无障碍已连上就切兜底，
                // 还没连上就继续挂着等下一轮（投影令牌已死，不急着判死）
                if (inGame && captureDead && Build.VERSION.SDK_INT >= 34 &&
                    AgentAccessibilityService.connected != null) {
                    AgentLog.i(TAG) { "dead capture + game foreground → a11y fallback" }
                    switchToA11yCapture()
                }
                if (inGame != _uiState.value.gameInForeground) {
                    AgentLog.i(TAG) { "foreground pkg=$pkg inGame=$inGame" }
                    _uiState.update { it.copy(gameInForeground = inGame) }
                    SubstitutionTimerCoordinator.onLaunchEvent(
                        if (inGame) LaunchEvent.GameForeground else LaunchEvent.GameLeft
                    )
                    if (inGame) {
                        // 进游戏：重建基线，首组稳定帧只建基线不计时
                        engine?.resetBaseline("game_foreground")
                        // 校准完成才挂计时悬浮窗，未校准期间不显示"监视中"误导
                        if (config?.calibrated == true) scope.launch { overlay?.show() }
                        // 未校准不进 MONITORING——不能伪装成"识别中"；预览帧照产供校准用
                        if (_uiState.value.state == TimerServiceState.WAITING_FOR_GAME) {
                            pushState(detail = "")
                        } else if (_uiState.value.state == TimerServiceState.NEEDS_CALIBRATION) {
                            pushState(detail = "已进入游戏——回设置页完成校准后开始识别")
                        }
                    } else {
                        scope.launch { overlay?.hide() }
                        engine?.resetBaseline("game_left")
                        if (_uiState.value.state == TimerServiceState.MONITORING) {
                            pushState(detail = "已离开游戏")
                        }
                    }
                }
                delay(900)
            }
        }
    }

    private fun currentForegroundPackage(usm: UsageStatsManager): String? {
        val end = System.currentTimeMillis()
        // 两分钟窗口取最后一条前台事件：停留在一屏不放前台事件属常态
        val events = usm.queryEvents(end - 120_000, end)
        var pkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    private fun pauseSession() {
        engine?.pause()
        pushState(detail = "已暂停本次（倒计时仍按截止时间计）")
        SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.Pause)
        updateNotification()
    }

    private fun resumeSession() {
        engine?.resume()
        pushState(detail = "")
        SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.Resume)
        // Resume 只会回到 WAITING_FOR_GAME——人还在游戏里时立刻补发 GameForeground
        if (_uiState.value.gameInForeground) {
            SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.GameForeground)
        }
        updateNotification()
    }

    /** 用户停止/系统撤销/共享抢占：释放资源并进入可解释状态。 */
    private fun stopSession(userInitiated: Boolean) {
        suppressCaptureClose = true
        started = false
        ticker?.cancel(); foregroundWatch?.cancel()
        a11ySource?.stop(); a11ySource = null
        capture?.stop(); capture = null
        overlay?.destroy(); overlay = null
        projection = null
        pushState(detail = "")
        if (userInitiated) SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.Stop)
        else SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.SessionLost)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onSessionLost(reason: String) {
        AgentLog.e(TAG) {
            "session lost: $reason (started=$started inGame=${_uiState.value.gameInForeground} " +
                "a11y=${AgentAccessibilityService.connected != null} a11ySrc=${a11ySource != null} " +
                "cap=${capture != null})"
        }
        scope.launch {
            suppressCaptureClose = true
            started = false
            ticker?.cancel(); foregroundWatch?.cancel()
            a11ySource?.stop(); a11ySource = null
            capture?.stop(); capture = null
            overlay?.destroy(); overlay = null
            pushState(detail = "$reason，返回 App 重新授权")
            SubstitutionTimerCoordinator.onLaunchEvent(LaunchEvent.SessionLost)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 只推说明文本；状态由 SubstitutionTimerCoordinator 状态机镜像接管。 */
    private fun pushState(detail: String? = null) {
        _uiState.update { it.copy(detail = detail ?: it.detail) }
        updateNotification()
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification("替身计时器运行中"), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("替身计时器运行中"))
        }
    }

    private fun updateNotification() {
        if (!started) return
        val text = when (_uiState.value.state) {
            TimerServiceState.MONITORING -> "识别中 — 替身冷却会显示在游戏上方"
            TimerServiceState.WAITING_FOR_GAME -> "等待进入游戏"
            TimerServiceState.PAUSED -> "已暂停"
            TimerServiceState.NEEDS_CALIBRATION -> "需要校准豆槽位置"
            else -> "替身计时器运行中"
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pauseAction = NotificationCompat.Action.Builder(
            null,
            if (_uiState.value.state == TimerServiceState.PAUSED) "继续" else "暂停本次",
            PendingIntent.getService(
                this, 1,
                Intent(this, SubstitutionTimerService::class.java).setAction(
                    if (_uiState.value.state == TimerServiceState.PAUSED) ACTION_RESUME else ACTION_PAUSE
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        val stopAction = NotificationCompat.Action.Builder(
            null, "停止",
            PendingIntent.getService(
                this, 2,
                Intent(this, SubstitutionTimerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("替身计时器")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(pauseAction)
            .addAction(stopAction)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "替身计时器", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** 校准页使用的最新帧预览（只在内存中，不落盘不上传）。旧帧交给 GC，不主动 recycle——Compose 可能仍持有引用。 */
    private fun publishPreview(frame: Bitmap) {
        _preview.value = frame
    }

    override fun onDestroy() {
        ticker?.cancel(); foregroundWatch?.cancel()
        a11ySource?.stop(); a11ySource = null
        capture?.stop()
        overlay?.destroy()
        _uiState.value = UiState()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "xyz.chouxuewei.mobile_agent.substitution.START"
        const val ACTION_PAUSE = "xyz.chouxuewei.mobile_agent.substitution.PAUSE"
        const val ACTION_RESUME = "xyz.chouxuewei.mobile_agent.substitution.RESUME"
        const val ACTION_RESET = "xyz.chouxuewei.mobile_agent.substitution.RESET"
        const val ACTION_SWAP = "xyz.chouxuewei.mobile_agent.substitution.SWAP"
        const val EXTRA_SELF_LEFT = "xyz.chouxuewei.mobile_agent.substitution.SELF_LEFT"
        const val ACTION_STOP = "xyz.chouxuewei.mobile_agent.substitution.STOP"
        const val ACTION_OPEN_SETTINGS = "xyz.chouxuewei.mobile_agent.substitution.OPEN_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "substitution_timer"
        private const val NOTIFICATION_ID = 9401
        private const val TAG = "SubTimer"

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState

        private val _preview = MutableStateFlow<Bitmap?>(null)
        /** 校准页实时预览：内存帧，不落盘不上传。 */
        val preview: StateFlow<Bitmap?> = _preview

        /** 校准页置位才产预览帧；默认关闭，避免无谓整帧拷贝。 */
        @Volatile var previewEnabled = false
        @Volatile private var lastPreviewAt = 0L
        private const val PREVIEW_WIDTH = 480
    }
}
