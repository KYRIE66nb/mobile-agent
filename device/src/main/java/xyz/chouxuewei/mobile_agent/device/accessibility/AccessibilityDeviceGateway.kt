package xyz.chouxuewei.mobile_agent.device.accessibility

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.app.KeyguardManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.DeviceCapability
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.DeviceKey
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.ExecutionSession
import xyz.chouxuewei.mobile_agent.core.GestureStroke
import xyz.chouxuewei.mobile_agent.core.GesturePoint
import xyz.chouxuewei.mobile_agent.core.LaunchableApp
import xyz.chouxuewei.mobile_agent.core.NodeSnapshot
import xyz.chouxuewei.mobile_agent.core.Observation
import xyz.chouxuewei.mobile_agent.core.Screenshot
import xyz.chouxuewei.mobile_agent.core.Viewport
import xyz.chouxuewei.mobile_agent.device.CoordinateMapper

/**
 * 免 Root 的主屏执行通道：节点树、坐标手势、语义动作、文本输入都走无障碍服务，
 * 截图走 takeScreenshot（API 30+），应用启动走 PackageManager，音量走 AudioManager。
 * 桌面桥（BridgeService）在任意已开启无障碍的机器上使用它，不要求 Root。
 */
class AccessibilityDeviceGateway(
    context: Context,
) : DeviceGateway {
    companion object {
        private const val SCREENSHOT_JPEG_QUALITY = 82
        private const val SCREENSHOT_RETRY_DELAY_MS = 350L
        private const val DISPLAY_ID = 0
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var active: ActiveSession? = null

    private class ActiveSession(
        val session: ExecutionSession,
        var viewport: Viewport,
        var latestObservation: Observation? = null,
    )

    /** 只读无障碍连接状态，不触发任何系统弹窗。 */
    fun accessibilityConnected(): Boolean = AgentAccessibilityService.connected != null

    override suspend fun availability(): DeviceResult<Unit> =
        if (accessibilityConnected()) {
            DeviceResult.Success(Unit)
        } else {
            DeviceResult.Unsupported(
                localizedText(
                    "请先开启无障碍服务：设置 → 无障碍 → ${appContext.applicationInfo.loadLabel(appContext.packageManager)}",
                    "Enable the accessibility service first: Settings → Accessibility.",
                )
            )
        }

    override suspend fun listApps(): DeviceResult<List<LaunchableApp>> = try {
        val apps = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            appContext.packageManager.getInstalledApplications(android.content.pm.PackageManager.MATCH_ALL)
                .mapNotNull { info ->
                    val packageName = info.packageName
                    if (appContext.packageManager.getLaunchIntentForPackage(packageName) == null) return@mapNotNull null
                    LaunchableApp(
                        label = appContext.packageManager.getApplicationLabel(info).toString().ifBlank { packageName },
                        packageName = packageName,
                    )
                }
                .distinctBy(LaunchableApp::packageName)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LaunchableApp::label).thenBy(LaunchableApp::packageName))
        }
        DeviceResult.Success(apps)
    } catch (error: Exception) {
        DeviceResult.Failure(error.message ?: localizedText("读取本机应用列表失败", "Failed to read the installed app list."))
    }

    override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> = mutex.withLock {
        if (mode == ExecutionMode.VIRTUAL_DISPLAY) {
            return@withLock DeviceResult.Unsupported(
                localizedText("免 Root 通道不支持后台虚拟屏", "The no-root channel does not support virtual displays.")
            )
        }
        val service = AgentAccessibilityService.connected ?: return@withLock availability()
            .let { DeviceResult.Unsupported((it as? DeviceResult.Unsupported)?.reason ?: "accessibility off") }
        if (Build.VERSION.SDK_INT < 30) {
            return@withLock DeviceResult.Unsupported(
                localizedText("界面识别需要 Android 11 或更高版本", "Screen inspection requires Android 11 or later.")
            )
        }
        if (active != null) {
            return@withLock DeviceResult.Failure(
                localizedText("已有手机操作正在进行，请先结束", "Another phone operation is active. End it first.")
            )
        }
        val session = ExecutionSession(
            id = UUID.randomUUID().toString(),
            mode = ExecutionMode.MAIN_DISPLAY,
            capabilities = buildSet {
                add(DeviceCapability.SCREENSHOT)
                add(DeviceCapability.NODES)
                add(DeviceCapability.POINTER_INPUT)
                add(DeviceCapability.TEXT_INPUT)
                add(DeviceCapability.APP_LAUNCH)
                add(DeviceCapability.SEMANTIC_ACTIONS)
                add(DeviceCapability.COMPLEX_GESTURES)
                add(DeviceCapability.SYSTEM_NAVIGATION)
            },
        )
        active = ActiveSession(session, mainDisplayViewport())
        AgentLog.i("Device") { "a11y_session_open session=${session.id}" }
        DeviceResult.Success(session)
    }

    override suspend fun observe(sessionId: String): DeviceResult<Observation> =
        captureObservation(sessionId, includeScreenshot = true)

    override suspend fun inspect(sessionId: String): DeviceResult<Observation> =
        captureObservation(sessionId, includeScreenshot = false)

    private suspend fun captureObservation(
        sessionId: String,
        includeScreenshot: Boolean,
    ): DeviceResult<Observation> = mutex.withLock {
        val current = active
            ?: return@withLock DeviceResult.SessionExpired(localizedText("会话已关闭", "Session closed."))
        if (current.session.id != sessionId) {
            return@withLock DeviceResult.SessionExpired(localizedText("会话标识不匹配", "Session ID mismatch."))
        }
        val service = AgentAccessibilityService.connected
            ?: return@withLock DeviceResult.Failure(
                localizedText("无障碍服务已断开", "The accessibility service disconnected.")
            )
        try {
            current.viewport = mainDisplayViewport()
            val screenshot = if (includeScreenshot) captureDisplayWithRetry(service, current.viewport) else null
            val (nodes, foregroundPackage) = withContext(Dispatchers.Main.immediate) {
                service.snapshot(DISPLAY_ID) to service.focusedApplicationWindow(
                    DISPLAY_ID, excludeOwnPackage = false,
                )?.packageName
            }
            val observation = Observation(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                capturedAtEpochMillis = System.currentTimeMillis(),
                contentRevision = System.nanoTime(),
                viewport = current.viewport,
                rotationDegrees = 0,
                foregroundPackage = foregroundPackage,
                screenshot = screenshot,
                nodes = nodes,
            )
            current.latestObservation = observation
            DeviceResult.Success(observation)
        } catch (error: Exception) {
            AgentLog.e("Device", error) { "a11y_observe_failed session=$sessionId" }
            DeviceResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun execute(
        sessionId: String,
        observationId: String?,
        action: Action,
    ): ActionResult = mutex.withLock {
        val current = active ?: return@withLock ActionResult.SessionExpired(localizedText("会话已关闭", "Session closed."))
        if (current.session.id != sessionId) return@withLock ActionResult.SessionExpired(localizedText("会话标识不匹配", "Session ID mismatch."))
        val service = AgentAccessibilityService.connected
            ?: return@withLock ActionResult.Failure(localizedText("无障碍服务已断开", "The accessibility service disconnected."))
        // 全局动作（开应用/等待/按键）不依赖坐标上下文；坐标与节点动作必须携带最新识别。
        val needsObservation = action !is Action.OpenApp && action !is Action.Wait && action !is Action.PressKey
        val observation = if (!needsObservation) null else {
            val latest = current.latestObservation
                ?: return@withLock ActionResult.ObservationMismatch(localizedText("执行前必须重新识别界面", "Inspect the screen again before acting."))
            if (latest.id != observationId) {
                return@withLock ActionResult.ObservationMismatch(localizedText("识别结果已过期，请重新识别界面后执行", "The observation expired. Inspect the screen again before acting."))
            }
            latest
        }
        try {
            when (action) {
                is Action.Tap -> tap(observation!!, action.x, action.y, 60L)
                is Action.LongPress -> tap(observation!!, action.x, action.y, action.durationMs.toLong())
                is Action.Swipe -> gesture(observation!!, listOf(
                    GestureStroke(
                        listOf(GesturePoint(action.startX, action.startY), GesturePoint(action.endX, action.endY)),
                        durationMs = action.durationMs.toLong(),
                    ),
                ))
                is Action.InputText -> withContext(Dispatchers.Main.immediate) {
                    service.writeText(DISPLAY_ID, action.text, action.node, action.mode)
                }
                is Action.PerformNodeAction -> withContext(Dispatchers.Main.immediate) {
                    service.performNodeAction(DISPLAY_ID, action.node, action.action)
                }
                is Action.MultiStrokeGesture -> gesture(observation!!, action.strokes)
                is Action.PressKey -> withContext(Dispatchers.Main.immediate) {
                    pressKey(action.key, service)
                }
                is Action.OpenApp -> launchApp(action.target.packageName)
                is Action.Wait -> {
                    require(action.durationMs in 0..5_000) { localizedText("等待时长超出限制", "Wait duration exceeds the limit.") }
                    delay(action.durationMs)
                }
                Action.EnableNodeAccess -> {
                    check(accessibilityConnected()) { localizedText("无障碍服务尚未连接", "The accessibility service is not connected.") }
                }
            }
            if (needsObservation) current.latestObservation = null
            ActionResult.Performed()
        } catch (error: IllegalArgumentException) {
            ActionResult.TargetMismatch(error.message ?: localizedText("动作参数无效", "Invalid action arguments."))
        } catch (error: Exception) {
            AgentLog.e("Device", error) { "a11y_action_failed session=$sessionId action=${action.javaClass.simpleName}" }
            ActionResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun closeSession(sessionId: String): DeviceResult<Unit> = mutex.withLock {
        val current = active ?: return@withLock DeviceResult.SessionExpired(localizedText("会话已关闭", "Session closed."))
        if (current.session.id != sessionId) {
            return@withLock DeviceResult.SessionExpired(localizedText("会话标识不匹配", "Session ID mismatch."))
        }
        active = null
        AgentLog.i("Device") { "a11y_session_closed session=$sessionId" }
        DeviceResult.Success(Unit)
    }

    private suspend fun tap(observation: Observation, x: Int, y: Int, durationMs: Long) {
        val service = AgentAccessibilityService.connected
            ?: throw IllegalStateException(localizedText("无障碍服务尚未连接", "The accessibility service is not connected."))
        val current = active ?: throw IllegalStateException(localizedText("会话已关闭", "Session closed."))
        val point = CoordinateMapper.map(x, y, observation.viewport, current.viewport, observation.rotationDegrees)
        withContext(Dispatchers.Main.immediate) { service.tapAt(DISPLAY_ID, point.x, point.y, durationMs) }
    }

    private suspend fun gesture(observation: Observation, strokes: List<GestureStroke>) {
        val service = AgentAccessibilityService.connected
            ?: throw IllegalStateException(localizedText("无障碍服务尚未连接", "The accessibility service is not connected."))
        val current = active ?: throw IllegalStateException(localizedText("会话已关闭", "Session closed."))
        val mapped = strokes.map { stroke ->
            stroke.copy(points = stroke.points.map { point ->
                val value = CoordinateMapper.map(point.x, point.y, observation.viewport, current.viewport, observation.rotationDegrees)
                GesturePoint(value.x, value.y)
            })
        }
        withContext(Dispatchers.Main.immediate) { service.performGesture(DISPLAY_ID, mapped) }
    }

    private fun pressKey(key: DeviceKey, service: AgentAccessibilityService) {
        when (key) {
            DeviceKey.HOME -> check(service.globalAction(AccessibilityService.GLOBAL_ACTION_HOME)) { localizedText("Home 动作被系统拒绝", "The Home action was rejected.") }
            DeviceKey.BACK -> check(service.globalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { localizedText("返回动作被系统拒绝", "The Back action was rejected.") }
            DeviceKey.RECENTS -> check(service.globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) { localizedText("最近任务动作被系统拒绝", "The Recents action was rejected.") }
            DeviceKey.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            DeviceKey.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            DeviceKey.SCREENSHOT -> takeSystemScreenshot(service)
            else -> throw IllegalArgumentException(
                localizedText("免 Root 通道不支持按键 ${key.name.lowercase()}（仅 home/back/recents/volume_up/volume_down）", "The no-root channel does not support the ${key.name.lowercase()} key (only home/back/recents/volume_up/volume_down).")
            )
        }
    }

    @SuppressLint("InlinedApi")
    private fun takeSystemScreenshot(service: AgentAccessibilityService) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            localizedText("系统截图需要 Android 9 或更高版本", "System screenshots require Android 9 or later.")
        }
        val keyguard = checkNotNull(appContext.getSystemService(KeyguardManager::class.java)) {
            localizedText("无法读取锁屏状态", "Could not read the lock-screen state.")
        }
        check(!keyguard.isKeyguardLocked &&
            !keyguard.isDeviceLocked
        ) {
            localizedText("锁屏时拒绝系统截图", "System screenshots are rejected while the device is locked.")
        }
        check(service.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            localizedText("系统截图动作被系统拒绝", "The system rejected the screenshot action.")
        }
    }

    private fun adjustVolume(direction: Int) {
        appContext.getSystemService(AudioManager::class.java)
            ?.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
            ?: throw IllegalStateException(localizedText("音量服务不可用", "The audio service is unavailable."))
    }

    private fun launchApp(packageName: String) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException(localizedText("应用没有可启动的入口：$packageName", "The app has no launchable entry: $packageName"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private suspend fun captureDisplayWithRetry(
        service: AgentAccessibilityService,
        viewport: Viewport,
    ): Screenshot? {
        val bitmap = try {
            withContext(Dispatchers.Main.immediate) { service.captureDisplay(DISPLAY_ID) }
        } catch (error: AccessibilityScreenshotException) {
            if (!error.isRateLimited) throw error
            delay(SCREENSHOT_RETRY_DELAY_MS)
            withContext(Dispatchers.Main.immediate) { service.captureDisplay(DISPLAY_ID) }
        } ?: return null
        return try {
            val encoded = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, output)) {
                        localizedText("屏幕截图压缩失败", "Screenshot compression failed.")
                    }
                    output.toByteArray()
                }
            }
            Screenshot(encoded, "image/jpeg", bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun mainDisplayViewport(): Viewport {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = appContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) return Viewport(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val metrics = appContext.resources.displayMetrics
        return Viewport(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }
}
