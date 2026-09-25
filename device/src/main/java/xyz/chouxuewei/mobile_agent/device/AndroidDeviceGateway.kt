package xyz.chouxuewei.mobile_agent.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.WindowManager
import com.topjohnwu.superuser.Shell
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import xyz.chouxuewei.mobile_agent.core.GesturePoint
import xyz.chouxuewei.mobile_agent.core.GestureStroke
import xyz.chouxuewei.mobile_agent.core.LaunchableApp
import xyz.chouxuewei.mobile_agent.core.NodeActionKind
import xyz.chouxuewei.mobile_agent.core.NodeSnapshot
import xyz.chouxuewei.mobile_agent.core.Observation
import xyz.chouxuewei.mobile_agent.core.Screenshot
import xyz.chouxuewei.mobile_agent.core.Viewport
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService
import xyz.chouxuewei.mobile_agent.device.accessibility.AccessibilityScreenshotException
import xyz.chouxuewei.mobile_agent.device.accessibility.AccessibilityWindowCapture
import xyz.chouxuewei.mobile_agent.device.accessibility.AccessibilityWindowTarget
import xyz.chouxuewei.mobile_agent.device.capture.FrameSource
import xyz.chouxuewei.mobile_agent.device.root.DisplayAdapter
import xyz.chouxuewei.mobile_agent.device.root.RootBridge

data class RootAccessState(
    val available: Boolean,
    val enabled: Boolean,
    val granted: Boolean,
    val detail: String,
)

/** App 层实现这个轻量接口，使设备模块无需反向依赖具体悬浮窗服务。 */
fun interface MainDisplayOverlayController {
    suspend fun setHiddenForDeviceInteraction(hidden: Boolean): Boolean
}

class AndroidDeviceGateway(
    context: Context,
    private val overlayController: MainDisplayOverlayController? = null,
) : DeviceGateway {
    companion object {
        private const val ROOT_PREFERENCES = "root_access"
        private const val ROOT_ENABLED = "enabled"
        private const val SCREENSHOT_JPEG_QUALITY = 82
        // Android 对同一窗口截图至少间隔 333ms，略留余量避免边界抖动再次触发限频。
        private const val WINDOW_SCREENSHOT_RETRY_DELAY_MS = 350L
        @Volatile private var shellConfigured = false

        @Synchronized
        fun configureRootShell() {
            if (shellConfigured) return
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
            shellConfigured = true
        }
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(ROOT_PREFERENCES, Context.MODE_PRIVATE)
    private val bridge = RootBridge(appContext)
    private val mutex = Mutex()
    private var active: ActiveSession? = null
    private val mutableActiveMode = MutableStateFlow<ExecutionMode?>(null)
    val activeMode: StateFlow<ExecutionMode?> = mutableActiveMode

    private class ActiveSession(
        val session: ExecutionSession,
        val displayId: Int,
        var viewport: Viewport,
        val frames: FrameSource?,
        var latestObservation: Observation? = null,
    )

    private data class CapturedObservation(
        val screenshot: Screenshot?,
        val nodes: List<NodeSnapshot>,
        val foregroundPackage: String?,
        val source: String,
    )

    /** 这里只检查 su、当前进程状态与用户保存的开关，不会弹出 Root 管理器。 */
    fun rootAccessState(): RootAccessState {
        val cachedRoot = Shell.getCachedShell()?.isRoot == true
        // libsu 在冷启动尚未创建 Shell 时会返回 null，它表示“尚未确认”，不能当作授权已丢失。
        val grantState = if (cachedRoot) true else Shell.isAppGrantedRoot()
        val granted = grantState == true
        val available = cachedRoot || hasSuExecutable()
        val requested = if (preferences.contains(ROOT_ENABLED)) {
            preferences.getBoolean(ROOT_ENABLED, false)
        } else {
            // 兼容升级前已授予 Root 的安装，首次进入设置时继续保持可用。
            granted
        }
        val enabled = available && requested && grantState != false
        val detail = when {
            !available -> "当前设备未检测到可用的 Root 环境"
            !requested -> "Root 权限已关闭"
            granted -> "Root 权限已开启"
            enabled -> "Root 权限已开启，首次使用时将请求系统确认"
            else -> "请先授予 Root 权限"
        }
        return RootAccessState(available, enabled, granted, detail)
    }

    /** 只有用户主动打开开关时才请求系统 Root 授权。 */
    suspend fun setRootEnabled(enabled: Boolean): RootAccessState {
        if (!enabled) {
            preferences.edit().putBoolean(ROOT_ENABLED, false).apply()
            closeActiveSession()
            withContext(Dispatchers.IO) { runCatching { Shell.getCachedShell()?.close() } }
            return rootAccessState()
        }
        if (!hasSuExecutable()) return rootAccessState()
        val granted = withContext(Dispatchers.IO) {
            runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        }
        preferences.edit().putBoolean(ROOT_ENABLED, granted).apply()
        return rootAccessState().let { state ->
            if (!granted && state.available) state.copy(detail = "未获得 Root 权限") else state
        }
    }

    override suspend fun availability(): DeviceResult<Unit> {
        val root = rootAccessState()
        return if (root.enabled) {
            DeviceResult.Success(Unit)
        } else {
            // 此处只读授权状态，避免用户只是打开工具列表就收到 Root 请求。
            DeviceResult.Unsupported("请先授予 Root 权限")
        }
    }

    override suspend fun listApps(): DeviceResult<List<LaunchableApp>> {
        if (!rootAccessState().enabled) return DeviceResult.Unsupported("请先授予 Root 权限")
        return try {
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
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LaunchableApp::label)
                        .thenBy(LaunchableApp::packageName))
            }
            DeviceResult.Success(apps)
        } catch (error: Exception) {
            DeviceResult.Failure(error.message ?: "读取本机应用列表失败")
        }
    }

    override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> = mutex.withLock {
        val startedAt = System.currentTimeMillis()
        AgentLog.i("Device") { "session_open_start mode=${mode.name.lowercase()}" }
        if (!rootAccessState().enabled) {
            return@withLock DeviceResult.Unsupported("请先授予 Root 权限")
        }
        if (active != null) return@withLock DeviceResult.Failure("已有手机操作正在进行，请先结束")
        if (mode == ExecutionMode.VIRTUAL_DISPLAY && Build.VERSION.SDK_INT < 34) {
            return@withLock DeviceResult.Unsupported("后台操作需要 Android 14 或更高版本")
        }
        var frames: FrameSource? = null
        try {
            withContext(Dispatchers.IO) { bridge.connect() }
            // Android 11 起才能按 displayId 读取节点和分发复杂手势；旧系统仍保留截图与坐标输入。
            if (Build.VERSION.SDK_INT >= 30) ensureNodeService()
            val displayId: Int
            val viewport: Viewport
            if (mode == ExecutionMode.VIRTUAL_DISPLAY) {
                frames = FrameSource()
                displayId = withContext(Dispatchers.IO) { bridge.createDisplay(frames.surface) }
                viewport = Viewport(DisplayAdapter.WIDTH, DisplayAdapter.HEIGHT)
            } else {
                displayId = 0
                viewport = mainDisplayViewport()
            }
            val session = ExecutionSession(
                id = UUID.randomUUID().toString(),
                mode = mode,
                capabilities = buildSet {
                    add(DeviceCapability.SCREENSHOT)
                    add(DeviceCapability.POINTER_INPUT)
                    add(DeviceCapability.APP_LAUNCH)
                    add(DeviceCapability.SYSTEM_NAVIGATION)
                    if (Build.VERSION.SDK_INT >= 30) {
                        add(DeviceCapability.NODES)
                        add(DeviceCapability.TEXT_INPUT)
                        add(DeviceCapability.SEMANTIC_ACTIONS)
                        add(DeviceCapability.COMPLEX_GESTURES)
                    }
                },
            )
            active = ActiveSession(session, displayId, viewport, frames)
            mutableActiveMode.value = mode
            AgentLog.i("Device") {
                "session_open_finish session=${session.id} mode=${mode.name.lowercase()} display=$displayId duration_ms=${System.currentTimeMillis() - startedAt}"
            }
            DeviceResult.Success(session)
        } catch (error: Exception) {
            AgentLog.e("Device", error) { "session_open_failed mode=${mode.name.lowercase()}" }
            frames?.close()
            runCatching { bridge.close() }
            DeviceResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun observe(sessionId: String): DeviceResult<Observation> =
        captureObservation(sessionId, includeScreenshot = true)

    override suspend fun inspect(sessionId: String): DeviceResult<Observation> =
        captureObservation(sessionId, includeScreenshot = false)

    private suspend fun captureObservation(
        sessionId: String,
        includeScreenshot: Boolean,
    ): DeviceResult<Observation> = mutex.withLock {
        val startedAt = System.currentTimeMillis()
        val current = active
            ?: return@withLock DeviceResult.SessionExpired("会话已关闭")
        if (current.session.id != sessionId) {
            return@withLock DeviceResult.SessionExpired("会话标识不匹配")
        }
        try {
            var contentRevision = 0L
            val focusedWindow = if (current.session.mode == ExecutionMode.MAIN_DISPLAY) {
                runCatching { captureFocusedWindowObservation(current, includeScreenshot) }
                    .onFailure { error ->
                        AgentLog.w("Device", error) {
                            "focused_window_observation_failed display=${current.displayId}"
                        }
                    }
                    .getOrNull()
            } else null
            val capturedObservation = focusedWindow ?: withoutMainDisplayOverlay(current) {
                val captured = when {
                    includeScreenshot && current.session.mode == ExecutionMode.VIRTUAL_DISPLAY ->
                        current.frames?.latestJpeg(SCREENSHOT_JPEG_QUALITY)?.let {
                            contentRevision = it.revision
                            Screenshot(it.bytes, "image/jpeg", current.viewport.width, current.viewport.height)
                        }
                    includeScreenshot -> captureMain().also {
                        current.viewport = Viewport(it.width, it.height)
                        contentRevision = System.nanoTime()
                    }
                    current.session.mode == ExecutionMode.VIRTUAL_DISPLAY -> {
                        contentRevision = current.frames?.latestRevision() ?: 0L
                        null
                    }
                    else -> {
                        contentRevision = System.nanoTime()
                        null
                    }
                }
                val capturedNodes = withContext(Dispatchers.Main.immediate) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        AgentAccessibilityService.connected?.snapshot(current.displayId).orEmpty()
                    } else emptyList()
                }
                CapturedObservation(
                    screenshot = captured,
                    nodes = capturedNodes,
                    foregroundPackage = capturedNodes.firstNotNullOfOrNull { it.packageName },
                    source = if (current.session.mode == ExecutionMode.VIRTUAL_DISPLAY) {
                        "virtual_display"
                    } else "main_display_fallback",
                )
            }
            if (focusedWindow != null) contentRevision = System.nanoTime()
            val screenshot = capturedObservation.screenshot
            val nodes = capturedObservation.nodes
            val observation = Observation(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                capturedAtEpochMillis = System.currentTimeMillis(),
                contentRevision = contentRevision,
                viewport = current.viewport,
                rotationDegrees = 0,
                foregroundPackage = capturedObservation.foregroundPackage,
                screenshot = screenshot,
                nodes = nodes,
            )
            current.latestObservation = observation
            AgentLog.d("Device") {
                "observe session=$sessionId screenshot=$includeScreenshot source=${capturedObservation.source} bytes=${screenshot?.bytes?.size ?: 0} viewport=${current.viewport.width}x${current.viewport.height} nodes=${nodes.size} duration_ms=${System.currentTimeMillis() - startedAt}"
            }
            DeviceResult.Success(observation)
        } catch (error: Exception) {
            AgentLog.e("Device", error) { "observe_failed session=$sessionId screenshot=$includeScreenshot" }
            DeviceResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun execute(
        sessionId: String,
        observationId: String?,
        action: Action,
    ): ActionResult = mutex.withLock {
        val startedAt = System.currentTimeMillis()
        val actionName = action.javaClass.simpleName
        AgentLog.d("Device") { "action_start session=$sessionId action=$actionName" }
        val current = active ?: return@withLock ActionResult.SessionExpired("会话已关闭")
        if (current.session.id != sessionId) return@withLock ActionResult.SessionExpired("会话标识不匹配")
        // open_app 只依赖已校验的包名和活动会话，不需要为了取得识别 ID 额外读取一次屏幕。
        val observation = if (action is Action.OpenApp) null else {
            val latest = current.latestObservation
                ?: return@withLock ActionResult.ObservationMismatch("执行前必须重新识别界面")
            if (latest.id != observationId) {
                return@withLock ActionResult.ObservationMismatch("识别结果已过期，请重新识别界面后执行")
            }
            latest
        }
        if (action is Action.InputText && action.node != null &&
            observation?.nodes?.none { it.ref == action.node } == true
        ) {
            return@withLock ActionResult.TargetMismatch("节点不属于当前识别结果")
        }
        if (action is Action.PerformNodeAction &&
            observation?.nodes?.none { it.ref == action.node } == true
        ) {
            return@withLock ActionResult.TargetMismatch("节点不属于当前识别结果")
        }
        try {
            when (action) {
                is Action.Tap -> gesture(current, requireNotNull(observation), action.x, action.y, action.x, action.y, 0)
                is Action.LongPress -> gesture(
                    current, requireNotNull(observation), action.x, action.y, action.x, action.y, action.durationMs,
                )
                is Action.Swipe -> gesture(
                    current, requireNotNull(observation), action.startX, action.startY, action.endX, action.endY,
                    action.durationMs,
                )
                is Action.InputText -> {
                    if (DeviceCapability.TEXT_INPUT !in current.session.capabilities) {
                        return@withLock ActionResult.Unsupported("当前 Android 版本不支持按节点输入文字")
                    }
                    val service = AgentAccessibilityService.connected
                        ?: return@withLock ActionResult.Unsupported("节点服务尚未连接")
                    withContext(Dispatchers.Main.immediate) {
                        service.writeText(current.displayId, action.text, action.node, action.mode)
                    }
                }
                is Action.PerformNodeAction -> {
                    if (DeviceCapability.SEMANTIC_ACTIONS !in current.session.capabilities) {
                        return@withLock ActionResult.Unsupported("当前 Android 版本不支持节点语义动作")
                    }
                    val service = AgentAccessibilityService.connected
                        ?: return@withLock ActionResult.Unsupported("节点服务尚未连接")
                    val snapshot = requireNotNull(observation).nodes.singleOrNull { it.ref == action.node }
                        ?: return@withLock ActionResult.TargetMismatch("节点不属于当前识别结果")
                    if (action.action !in snapshot.supportedActions) {
                        return@withLock ActionResult.TargetMismatch(
                            "当前识别结果中的目标节点不支持 ${action.action.name.lowercase()}",
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        service.performNodeAction(current.displayId, action.node, action.action)
                    }
                }
                is Action.MultiStrokeGesture -> {
                    if (DeviceCapability.COMPLEX_GESTURES !in current.session.capabilities) {
                        return@withLock ActionResult.Unsupported("复杂触控需要 Android 11 或更高版本")
                    }
                    complexGesture(current, requireNotNull(observation), action.strokes)
                }
                is Action.PressKey -> withContext(Dispatchers.IO) {
                    bridge.pressKey(current.displayId, action.key.keyCode())
                }
                is Action.OpenApp -> withContext(Dispatchers.IO) {
                    bridge.launch(current.displayId, action.target)
                }
                is Action.Wait -> {
                    require(action.durationMs in 0..5_000) { "等待时长超出限制" }
                    delay(action.durationMs)
                }
                Action.EnableNodeAccess -> {
                    if (Build.VERSION.SDK_INT < 30) {
                        return@withLock ActionResult.Unsupported("节点识别需要 Android 11 或更高版本")
                    }
                    ensureNodeService()
                }
            }
            current.latestObservation = null
            AgentLog.d("Device") {
                "action_finish session=$sessionId action=$actionName duration_ms=${System.currentTimeMillis() - startedAt}"
            }
            ActionResult.Performed()
        } catch (error: IllegalArgumentException) {
            AgentLog.w("Device", error) { "action_rejected session=$sessionId action=$actionName" }
            ActionResult.TargetMismatch(error.message ?: "动作参数无效")
        } catch (error: Exception) {
            AgentLog.e("Device", error) { "action_failed session=$sessionId action=$actionName" }
            ActionResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private suspend fun ensureNodeService() {
        if (AgentAccessibilityService.connected != null) return
        withContext(Dispatchers.IO) { bridge.enableNodeService() }
        for (attempt in 0 until 30) {
            if (AgentAccessibilityService.connected != null) return
            delay(200)
        }
        error("系统尚未连接节点服务")
    }

    override suspend fun closeSession(sessionId: String): DeviceResult<Unit> = mutex.withLock {
        val current = active ?: return@withLock DeviceResult.SessionExpired("会话已关闭")
        if (current.session.id != sessionId) {
            return@withLock DeviceResult.SessionExpired("会话标识不匹配")
        }
        active = null
        mutableActiveMode.value = null
        try {
            if (current.session.mode == ExecutionMode.VIRTUAL_DISPLAY) {
                withContext(Dispatchers.IO) { bridge.releaseDisplay(current.displayId) }
            }
            AgentLog.i("Device") { "session_closed session=$sessionId" }
            DeviceResult.Success(Unit)
        } catch (error: Exception) {
            DeviceResult.Failure(error.message ?: error.javaClass.simpleName)
        } finally {
            current.frames?.close()
            runCatching { bridge.close() }
        }
    }

    private suspend fun closeActiveSession() = mutex.withLock {
        val current = active
        active = null
        mutableActiveMode.value = null
        try {
            if (current?.session?.mode == ExecutionMode.VIRTUAL_DISPLAY) {
                withContext(Dispatchers.IO) { bridge.releaseDisplay(current.displayId) }
            }
        } finally {
            current?.frames?.close()
            runCatching { bridge.close() }
        }
    }

    private fun hasSuExecutable(): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .any { directory -> directory.isNotBlank() && File(directory, "su").canExecute() }

    private suspend fun gesture(
        current: ActiveSession,
        observation: Observation,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ) {
        require(durationMs in 0..2_000) { "手势时长超出限制" }
        val start = CoordinateMapper.map(x1, y1, observation.viewport, current.viewport, observation.rotationDegrees)
        val end = CoordinateMapper.map(x2, y2, observation.viewport, current.viewport, observation.rotationDegrees)
        withoutMainDisplayOverlay(current) {
            withContext(Dispatchers.IO) {
                bridge.gesture(current.displayId, start.x, start.y, end.x, end.y, durationMs)
            }
        }
    }

    private suspend fun complexGesture(
        current: ActiveSession,
        observation: Observation,
        strokes: List<GestureStroke>,
    ) {
        require(strokes.isNotEmpty() && strokes.size <= 10) { "一次手势需要 1 到 10 条轨迹" }
        require(strokes.sumOf { it.points.size } <= 500) { "一次手势最多允许 500 个轨迹点" }
        val mapped = strokes.map { stroke ->
            stroke.copy(points = stroke.points.map { point ->
                val value = CoordinateMapper.map(
                    point.x,
                    point.y,
                    observation.viewport,
                    current.viewport,
                    observation.rotationDegrees,
                )
                GesturePoint(value.x, value.y)
            })
        }
        val service = AgentAccessibilityService.connected
            ?: throw IllegalStateException("节点服务尚未连接")
        withoutMainDisplayOverlay(current) {
            withContext(Dispatchers.Main.immediate) {
                service.performGesture(current.displayId, mapped)
            }
        }
    }

    private fun DeviceKey.keyCode(): Int = when (this) {
        DeviceKey.BACK -> KeyEvent.KEYCODE_BACK
        DeviceKey.ENTER -> KeyEvent.KEYCODE_ENTER
        DeviceKey.HOME -> KeyEvent.KEYCODE_HOME
        DeviceKey.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
        DeviceKey.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        DeviceKey.DELETE -> KeyEvent.KEYCODE_DEL
        DeviceKey.TAB -> KeyEvent.KEYCODE_TAB
        DeviceKey.DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
        DeviceKey.DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        DeviceKey.DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        DeviceKey.DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
    }

    private suspend fun <T> withoutMainDisplayOverlay(
        current: ActiveSession,
        block: suspend () -> T,
    ): T {
        val controller = overlayController
        if (current.session.mode != ExecutionMode.MAIN_DISPLAY || controller == null) return block()
        val wasVisible = controller.setHiddenForDeviceInteraction(true)
        return try {
            // WindowManager 的移除需要经过一次合成，随后截图和坐标动作才不会命中控制层。
            if (wasVisible) delay(20)
            block()
        } finally {
            controller.setHiddenForDeviceInteraction(false)
        }
    }

    private suspend fun captureFocusedWindowObservation(
        current: ActiveSession,
        includeScreenshot: Boolean,
    ): CapturedObservation? {
        val service = AgentAccessibilityService.connected ?: return null
        val target = withContext(Dispatchers.Main.immediate) {
            service.focusedApplicationWindow(current.displayId)
        } ?: return null
        current.viewport = mainDisplayViewport()
        val screenshot = if (includeScreenshot) {
            if (Build.VERSION.SDK_INT < 34) return null
            val capture = captureWindowWithRateLimitRetry(service, target)
            encodeWindowCapture(capture, current.viewport)
        } else null
        val nodes = withContext(Dispatchers.Main.immediate) {
            service.snapshot(current.displayId, target.id)
        }
        return CapturedObservation(
            screenshot = screenshot,
            nodes = nodes,
            foregroundPackage = target.packageName ?: nodes.firstNotNullOfOrNull { it.packageName },
            source = "focused_window",
        )
    }

    private suspend fun captureWindowWithRateLimitRetry(
        service: AgentAccessibilityService,
        target: AccessibilityWindowTarget,
    ): AccessibilityWindowCapture {
        return try {
            withContext(Dispatchers.Main.immediate) { service.captureWindow(target) }
        } catch (error: AccessibilityScreenshotException) {
            if (!error.isRateLimited) throw error
            delay(WINDOW_SCREENSHOT_RETRY_DELAY_MS)
            withContext(Dispatchers.Main.immediate) { service.captureWindow(target) }
        }
    }

    /**
     * 无障碍窗口截图只包含目标窗口。把它按窗口边界放回整屏画布，
     * 让图片坐标继续与节点的屏幕坐标、Root 手势坐标保持一致。
     */
    private suspend fun encodeWindowCapture(
        capture: AccessibilityWindowCapture,
        viewport: Viewport,
    ): Screenshot = withContext(Dispatchers.Default) {
        val source = capture.bitmap
        var composed: Bitmap? = null
        try {
            val outputBitmap = if (source.width == viewport.width && source.height == viewport.height) {
                source
            } else {
                Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888).also { frame ->
                    composed = frame
                    val bounds = capture.target.bounds
                    val destination = Rect(
                        bounds.left.coerceIn(0, viewport.width),
                        bounds.top.coerceIn(0, viewport.height),
                        bounds.right.coerceIn(0, viewport.width),
                        bounds.bottom.coerceIn(0, viewport.height),
                    )
                    check(!destination.isEmpty) { "目标窗口不在当前屏幕范围内" }
                    Canvas(frame).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                }
            }
            val encoded = ByteArrayOutputStream().use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, output)) {
                    "窗口截图压缩失败"
                }
                output.toByteArray()
            }
            Screenshot(encoded, "image/jpeg", viewport.width, viewport.height)
        } finally {
            composed?.recycle()
            source.recycle()
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

    private suspend fun captureMain(): Screenshot = withContext(Dispatchers.IO) {
        val bytes = bridge.captureMain().use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "主屏截图数据无效"
        }
        try {
            val encoded = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, output)) {
                    "主屏截图压缩失败"
                }
                output.toByteArray()
            }
            Screenshot(encoded, "image/jpeg", bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
