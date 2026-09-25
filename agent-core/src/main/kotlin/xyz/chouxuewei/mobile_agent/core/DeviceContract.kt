package xyz.chouxuewei.mobile_agent.core

enum class ExecutionMode { MAIN_DISPLAY, VIRTUAL_DISPLAY }

enum class DeviceCapability {
    SCREENSHOT,
    NODES,
    POINTER_INPUT,
    TEXT_INPUT,
    APP_LAUNCH,
    SEMANTIC_ACTIONS,
    COMPLEX_GESTURES,
    SYSTEM_NAVIGATION,
}

data class ExecutionSession(
    val id: String,
    val mode: ExecutionMode,
    val capabilities: Set<DeviceCapability>,
)

data class Viewport(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { localizedText("显示尺寸必须大于 0", "Display dimensions must be greater than 0.") }
    }
}

data class NodeRef(val value: String)

/** 节点在当前识别坐标系中的矩形，可用于把可见文字转换为受约束的点击坐标。 */
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left && bottom >= top) { localizedText("节点边界无效", "Invalid node bounds.") }
    }
}

data class NodeSnapshot(
    val ref: NodeRef,
    val bounds: NodeBounds,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val editable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val viewId: String? = null,
    val hintText: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val selected: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val supportedActions: Set<NodeActionKind> = emptySet(),
)

/** 图片使用编码字节，避免核心层依赖 Android Bitmap。 */
class Screenshot(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
)

data class Observation(
    val id: String,
    val sessionId: String,
    val capturedAtEpochMillis: Long,
    val contentRevision: Long,
    val viewport: Viewport,
    val rotationDegrees: Int,
    val foregroundPackage: String?,
    val screenshot: Screenshot?,
    val nodes: List<NodeSnapshot>,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) { localizedText("旋转角度无效", "Invalid rotation.") }
    }
}

enum class DeviceKey {
    BACK,
    ENTER,
    HOME,
    RECENTS,
    ESCAPE,
    DELETE,
    TAB,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
}

enum class NodeActionKind {
    CLICK,
    LONG_CLICK,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    SET_TEXT,
}

enum class TextInputMode { REPLACE, APPEND }

data class GesturePoint(val x: Int, val y: Int)

/** 同一动作中的多条轨迹可并行开始，用于连续绘画、双指缩放和自定义手势。 */
data class GestureStroke(
    val points: List<GesturePoint>,
    val startTimeMs: Long = 0,
    val durationMs: Long = 500,
) {
    init {
        require(points.size >= 2) { localizedText("每条轨迹至少需要两个点", "Each gesture path requires at least two points.") }
        require(points.drop(1).any { it != points.first() }) { localizedText("轨迹必须产生实际位移；点击请使用 tap", "A gesture path must include movement; use tap for a tap.") }
        require(startTimeMs >= 0 && durationMs in 1..10_000) { localizedText("轨迹时间参数无效", "Invalid gesture timing.") }
    }
}

data class LaunchableApp(
    val label: String,
    val packageName: String,
)

/** Android 应用包名；实际启动入口仍由设备端 PackageManager 解析，模型不能传入组件名。 */
data class AppTarget(val packageName: String) {
    init {
        require(packageName.length <= 255 && PACKAGE_PATTERN.matches(packageName)) { localizedText("应用包名格式无效", "Invalid app package name.") }
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

sealed interface Action {
    data class Tap(val x: Int, val y: Int) : Action
    data class LongPress(val x: Int, val y: Int, val durationMs: Int = 700) : Action
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Int = 350,
    ) : Action
    data class InputText(
        val text: String,
        val node: NodeRef? = null,
        val mode: TextInputMode = TextInputMode.REPLACE,
    ) : Action
    data class PerformNodeAction(val node: NodeRef, val action: NodeActionKind) : Action
    data class MultiStrokeGesture(val strokes: List<GestureStroke>) : Action
    data class PressKey(val key: DeviceKey) : Action
    data class OpenApp(val target: AppTarget) : Action
    data class Wait(val durationMs: Long) : Action
    data object EnableNodeAccess : Action
}

sealed interface DeviceResult<out T> {
    data class Success<T>(val value: T) : DeviceResult<T>
    data class Unsupported(val reason: String) : DeviceResult<Nothing>
    data class SessionExpired(val reason: String) : DeviceResult<Nothing>
    data class Failure(val reason: String, val retryable: Boolean = false) : DeviceResult<Nothing>
}

sealed interface ActionResult {
    data class Performed(val detail: String = "") : ActionResult
    data class Unsupported(val reason: String) : ActionResult
    data class SessionExpired(val reason: String) : ActionResult
    data class ObservationMismatch(val reason: String) : ActionResult
    data class TargetMismatch(val reason: String) : ActionResult
    data class Failure(val reason: String, val retryable: Boolean = false) : ActionResult
}

interface DeviceGateway {
    /** 只读取当前能力状态，不应在工具列表页面触发 Root 授权弹窗。 */
    suspend fun availability(): DeviceResult<Unit> = DeviceResult.Success(Unit)
    /** 返回本机具有普通启动入口的应用；调用方使用返回的真实包名启动，不能自行猜测。 */
    suspend fun listApps(): DeviceResult<List<LaunchableApp>> =
        DeviceResult.Unsupported(localizedText("当前设备不支持读取应用列表", "This device does not support listing apps."))
    suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession>
    suspend fun observe(sessionId: String): DeviceResult<Observation>
    /**
     * 只刷新当前界面状态，不强制生成要传给模型的截图。
     * 批量执行器用它在本地重新定位节点；其它实现不支持轻量读取时仍可回退到完整识别。
     */
    suspend fun inspect(sessionId: String): DeviceResult<Observation> = observe(sessionId)
    /** 启动应用不依赖界面识别；其余界面动作必须携带最近一次识别的准确 ID。 */
    suspend fun execute(sessionId: String, observationId: String?, action: Action): ActionResult
    suspend fun closeSession(sessionId: String): DeviceResult<Unit>
}
