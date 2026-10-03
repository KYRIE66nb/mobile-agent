package xyz.chouxuewei.mobile_agent.substitution

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.core.substitution.LaunchEvent
import xyz.chouxuewei.mobile_agent.core.substitution.TimerServiceState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerStateMachine
import xyz.chouxuewei.mobile_agent.data.SubstitutionTimerRepository
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/**
 * 自动启动协调器：用户打开 App 可见界面时幂等推进授权/待机流程。
 *
 * 防重入：consentInFlight + visibleSessionRequested 双闸门，
 * 旋转/重组/授权页返回不会重复弹系统授权；"暂停"与"永久关闭"分别走
 * Pause 与 Disabled 转移。
 */
object SubstitutionTimerCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: PrototypeApplication? = null
    private var machine = TimerStateMachine()
    private var consentInFlight = false
    private var visibleSessionRequested = false

    private val _state = MutableStateFlow(TimerServiceState.DISABLED)
    val state: StateFlow<TimerServiceState> = _state

    /** 已授权但服务还没确认启动的投影令牌——进程崩溃/后台拒启动都不丢，App 可见时重试。 */
    private var pendingConsentCode: Int = Activity.RESULT_CANCELED
    private var pendingConsentData: Intent? = null

    fun attach(app: PrototypeApplication) {
        this.app = app
        scope.launch {
            // 设置变化同步状态：用户永久关闭 → DISABLED；校准完成 → Calibrated
            app.substitutionSettings.config.collect { cfg ->
                if (!cfg.enabled && _state.value != TimerServiceState.DISABLED) {
                    pendingConsentCode = Activity.RESULT_CANCELED
                    pendingConsentData = null
                    transition(LaunchEvent.Disabled)
                    stopService()
                } else if (cfg.calibrated && _state.value == TimerServiceState.NEEDS_CALIBRATION) {
                    transition(LaunchEvent.Calibrated)
                }
            }
        }
    }

    val settings: SubstitutionTimerRepository? get() = app?.substitutionSettings

    /**
     * MainActivity 可见时调用（用户可见的打开/返回）。幂等：
     * - 功能关闭 → 保证 DISABLED
     * - 已有会话（MONITORING/WAITING/PAUSED）→ 不重复授权
     * - 停止过且自动启动仍开 → 重新进入合法授权
     */
    fun onAppVisible(launcher: ActivityResultLauncher<Intent>) {
        val app = app ?: return
        if (visibleSessionRequested || consentInFlight) return
        scope.launch {
            val cfg = app.substitutionSettings.current()
            if (!cfg.enabled || !cfg.autoStartOnAppOpen) {
                if (!cfg.enabled) transition(LaunchEvent.Disabled)
                return@launch
            }
            // 上次授权后服务没能起来（后台拒启动/进程被杀）→ 直接复用暂存令牌重试，不再弹窗
            if (pendingConsentData != null) {
                visibleSessionRequested = true
                tryStartPendingService()
                return@launch
            }
            xyz.chouxuewei.mobile_agent.core.AgentLog.i("SubTimer") {
                "onAppVisible state=${_state.value} enabled=${cfg.enabled} auto=${cfg.autoStartOnAppOpen}"
            }
            when (_state.value) {
                TimerServiceState.DISABLED -> {
                    transition(LaunchEvent.AppOpened)
                    visibleSessionRequested = true
                    requestConsent(app, launcher)
                }
                TimerServiceState.NEEDS_PERMISSION -> {
                    // 上次被拒/取消：本次可见会话只尝试一次
                    visibleSessionRequested = true
                    requestConsent(app, launcher)
                }
                else -> Unit // 会话存活：复用，什么都不做
            }
        }
    }

    fun onAppHidden() {
        visibleSessionRequested = false
    }

    private fun requestConsent(app: PrototypeApplication, launcher: ActivityResultLauncher<Intent>) {
        val manager = app.getSystemService(MediaProjectionManager::class.java)
        consentInFlight = true
        runCatching {
            launcher.launch(manager.createScreenCaptureIntent())
        }.onFailure {
            consentInFlight = false
            transition(LaunchEvent.Failed(it.message ?: "无法发起录屏授权"))
        }
    }

    /** MainActivity 授权结果回调。 */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        consentInFlight = false
        xyz.chouxuewei.mobile_agent.core.AgentLog.i("SubTimer") {
            "consent result code=$resultCode data=${data != null}"
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            pendingConsentCode = resultCode
            pendingConsentData = data
            tryStartPendingService()
        } else {
            transition(LaunchEvent.PermissionDenied)
        }
    }

    /** 服务完成启动后回掉：令牌已消费，丢弃暂存。 */
    fun onServiceStarted() {
        pendingConsentCode = Activity.RESULT_CANCELED
        pendingConsentData = null
    }

    private fun tryStartPendingService() {
        val data = pendingConsentData ?: return
        val ok = runCatching { startService(pendingConsentCode, data) }.isSuccess
        if (!ok) {
            // 单应用共享会把游戏立刻顶到前台——本进程已退后台时 startForegroundService 必抛。
            // 不转 Failed（ERROR 无出口）：令牌留着，停在 NEEDS_PERMISSION，
            // App 下次可见时 onAppVisible 用暂存令牌重试，不重新弹授权。
            xyz.chouxuewei.mobile_agent.core.AgentLog.w("SubTimer") {
                "deferred: startForegroundService blocked while backgrounded"
            }
        }
    }

    fun onLaunchEvent(event: LaunchEvent) = transition(event)

    fun transition(event: LaunchEvent): TimerServiceState {
        val before = machine.current
        val next = machine.transition(event)
        _state.value = next
        if (next != before || event is LaunchEvent.Failed) {
            xyz.chouxuewei.mobile_agent.core.AgentLog.i("SubTimer") {
                "machine $before -[$event]-> $next"
            }
        }
        return next
    }

    private fun startService(resultCode: Int, data: Intent) {
        val ctx = app ?: return
        val intent = Intent(ctx, SubstitutionTimerService::class.java).apply {
            action = SubstitutionTimerService.ACTION_START
            putExtra(SubstitutionTimerService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SubstitutionTimerService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun stopService() {
        val ctx = app ?: return
        ctx.startService(
            Intent(ctx, SubstitutionTimerService::class.java)
                .setAction(SubstitutionTimerService.ACTION_STOP),
        )
    }

    /** 设置页/通知的手动操作入口。 */
    fun pause() = serviceAction(SubstitutionTimerService.ACTION_PAUSE)
    fun resume() = serviceAction(SubstitutionTimerService.ACTION_RESUME)
    fun stop() = serviceAction(SubstitutionTimerService.ACTION_STOP)
    fun resetTimers() = serviceAction(SubstitutionTimerService.ACTION_RESET)
    fun swapSides() = serviceAction(SubstitutionTimerService.ACTION_SWAP)

    private fun serviceAction(action: String) {
        val ctx = app ?: return
        // 服务已死时后台 startService 会抛 IllegalStateException——手动操作绝不允许崩进程
        runCatching {
            ctx.startService(Intent(ctx, SubstitutionTimerService::class.java).setAction(action))
        }.onFailure {
            xyz.chouxuewei.mobile_agent.core.AgentLog.w("SubTimer") {
                "serviceAction $action failed: ${it.message}"
            }
        }
    }

    /** 手动启动（设置页"立即开始"按钮）：直接进授权流程，绕过自动启动开关。 */
    fun startNow(launcher: ActivityResultLauncher<Intent>) {
        val app = app ?: return
        if (consentInFlight) return
        when (_state.value) {
            TimerServiceState.MONITORING, TimerServiceState.WAITING_FOR_GAME,
            TimerServiceState.PAUSED, TimerServiceState.NEEDS_CALIBRATION -> return
            else -> {
                transition(LaunchEvent.AppOpened)
                requestConsent(app, launcher)
            }
        }
    }
}
