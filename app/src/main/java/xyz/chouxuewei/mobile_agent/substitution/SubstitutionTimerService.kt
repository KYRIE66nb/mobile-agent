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
import xyz.chouxuewei.mobile_agent.core.substitution.DotSample
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.LaunchEvent
import xyz.chouxuewei.mobile_agent.core.substitution.SideTimer
import xyz.chouxuewei.mobile_agent.core.substitution.SubstitutionEngine
import xyz.chouxuewei.mobile_agent.core.substitution.TimerConfig
import xyz.chouxuewei.mobile_agent.core.substitution.TimerEvent
import xyz.chouxuewei.mobile_agent.core.substitution.TimerServiceState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
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
                engine?.cancelTimer(TimerSide.SELF)
                engine?.cancelTimer(TimerSide.ENEMY)
                engine?.resetBaseline("manual_reset")
                publishTimers()
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
                proj.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        onSessionLost("系统已停止屏幕共享")
                    }
                }, null)
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
                started = true
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
        capture = ProjectionCapture().also { c ->
            c.start(
                proj,
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                onFrame = ::onFrame,
                onClosed = { reason -> onSessionLost(reason) },
            )
        }
    }

    /** 采集线程回调：监视中按 frameIntervalMs 采样判定；缩略预览帧节流产出供校准页。 */
    private var lastSampleAt = 0L
    private var lastObservedLogAt = 0L

    private fun onFrame(access: ProjectionCapture.FrameAccess) {
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
            val cells = cfg.layout.dotCells(side, access.width, access.height)
            var lit = 0
            var unknown = 0
            val marks = StringBuilder()
            for (cell in cells) {
                val sample = DotSample.of(access.sample(cell[0], cell[1], cell[2], cell[3], SAMPLE_GRID))
                val result = classifier.classify(sample)
                if (cellDiag) marks.append(
                    when (result.state) {
                        DotState.LIT -> 'L'; DotState.EMPTY -> 'E'; DotState.UNKNOWN -> 'U'
                    } + "(%.2f/%.2f/%.2f)".format(sample.meanSaturation, sample.meanValue, sample.colorVariance),
                ).append(' ')
                when (result.state) {
                    DotState.LIT -> lit++
                    DotState.EMPTY -> Unit
                    DotState.UNKNOWN -> unknown++
                }
            }
            if (cellDiag) {
                AgentLog.d(TAG) { "cells $side=${marks.trim()} lit=$lit unk=$unknown" }
            }
            // 不可信格过多 → 本帧该侧无有效估计
            if (unknown > cells.size * cfg.tuning.maxUnknownRatio) return null
            return lit
        }

        val selfCount = countDots(TimerSide.SELF)
        val enemyCount = countDots(TimerSide.ENEMY)
        val events = eng.onFrame(selfCount, enemyCount, now)
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
            if (e is TimerEvent.AmbiguousDrop) {
                _uiState.update { it.copy(detail = "疑似多豆变化，未自动计时") }
            }
        }
        if (events.isNotEmpty()) publishTimers()
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
        // 悬浮窗 View 只能在主线程操作
        scope.launch { overlay?.updateTimers(selfTimer, enemyTimer, showSelf) }
    }

    /** 每 ~300ms 刷新倒计时显示（刷新频率与检测频率解耦）。 */
    private fun startTicker() {
        ticker = scope.launch {
            while (true) {
                delay(300)
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
        updateNotification()
    }

    /** 用户停止/系统撤销/共享抢占：释放资源并进入可解释状态。 */
    private fun stopSession(userInitiated: Boolean) {
        started = false
        ticker?.cancel(); foregroundWatch?.cancel()
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
        scope.launch {
            started = false
            ticker?.cancel(); foregroundWatch?.cancel()
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
        const val ACTION_STOP = "xyz.chouxuewei.mobile_agent.substitution.STOP"
        const val ACTION_OPEN_SETTINGS = "xyz.chouxuewei.mobile_agent.substitution.OPEN_SETTINGS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val SAMPLE_GRID = 6
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
