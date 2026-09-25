package xyz.chouxuewei.mobile_agent.device.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.GestureStroke
import xyz.chouxuewei.mobile_agent.core.NodeRef
import xyz.chouxuewei.mobile_agent.core.NodeBounds
import xyz.chouxuewei.mobile_agent.core.NodeActionKind
import xyz.chouxuewei.mobile_agent.core.NodeSnapshot
import xyz.chouxuewei.mobile_agent.core.TextInputMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AccessibilityWindowTarget(
    val id: Int,
    val bounds: Rect,
    val packageName: String?,
)

data class AccessibilityWindowCapture(
    val bitmap: Bitmap,
    val target: AccessibilityWindowTarget,
)

class AccessibilityScreenshotException(
    val errorCode: Int,
) : IllegalStateException("窗口截图失败，系统错误码：$errorCode") {
    val isRateLimited: Boolean
        get() = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
}

class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var connected: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }

    /** 每次重新取目标显示的节点，不缓存 Android 节点或退回主屏窗口。 */
    private fun nodes(displayId: Int, windowId: Int? = null): List<AccessibilityNodeInfo> {
        check(Build.VERSION.SDK_INT >= 30 && displayId >= 0) { "无有效的显示目标" }
        // 浏览器节点可能来自系统缓存；每次识别必须重新读取，不能把上次的占位文字当成当前输入。
        if (Build.VERSION.SDK_INT >= 33) clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val windows = windowsOnAllDisplays.get(displayId).orEmpty()
        for (window in windows) {
            if (windowId == null || window.id == windowId) window.root?.let(pending::addLast)
            window.recycle()
        }
        while (pending.isNotEmpty() && result.size < 2000) {
            val node = pending.removeFirst()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
        pending.forEach { it.recycle() }
        return result
    }

    fun snapshot(displayId: Int, windowId: Int? = null): List<NodeSnapshot> {
        val nodes = nodes(displayId, windowId)
        try {
            return nodes.mapNotNull { node ->
                if (!node.isVisibleToUser || (node.text.isNullOrBlank() &&
                        node.contentDescription.isNullOrBlank() && !node.isEditable &&
                        !node.isClickable && !node.isLongClickable && !node.isScrollable)) return@mapNotNull null
                val bounds = Rect().also(node::getBoundsInScreen)
                NodeSnapshot(
                    ref = node.reference(bounds),
                    bounds = NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    packageName = node.packageName?.toString(),
                    editable = node.isEditable,
                    focused = node.isFocused,
                    enabled = node.isEnabled,
                    visible = node.isVisibleToUser,
                    viewId = node.viewIdResourceName,
                    hintText = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    selected = node.isSelected,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    supportedActions = node.actionList.mapNotNull { actionKind(it.id) }.toSet(),
                )
                }
        } finally { nodes.forEach { it.recycle() } }
    }

    /**
     * 悬浮助手可能暂时取得系统焦点，因此不能只依赖 isFocused。
     * 先排除自身窗口，再从应用窗口中按“焦点、活动、层级”选择当前操作目标。
     */
    fun focusedApplicationWindow(displayId: Int): AccessibilityWindowTarget? {
        check(Build.VERSION.SDK_INT >= 30 && displayId >= 0) { "无有效的显示目标" }
        val windows = windowsOnAllDisplays.get(displayId).orEmpty()
        return try {
            windows.mapIndexedNotNull { index, window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapIndexedNotNull null
                val root = window.root ?: return@mapIndexedNotNull null
                val targetPackage = try {
                    root.packageName?.toString()
                } finally {
                    root.recycle()
                }
                if (targetPackage == packageName) return@mapIndexedNotNull null
                val bounds = Rect().also(window::getBoundsInScreen)
                if (bounds.isEmpty) return@mapIndexedNotNull null
                val priority = (if (window.isFocused) 4 else 0) + (if (window.isActive) 2 else 0)
                Triple(
                    priority,
                    -index,
                    AccessibilityWindowTarget(window.id, bounds, targetPackage),
                )
            }.maxWithOrNull(compareBy<Triple<Int, Int, AccessibilityWindowTarget>> { it.first }
                .thenBy { it.second })?.third
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    /** Android 14 起可直接截取目标窗口；其它悬浮层不会进入返回的像素。 */
    suspend fun captureWindow(target: AccessibilityWindowTarget): AccessibilityWindowCapture {
        check(Build.VERSION.SDK_INT >= 34) { "窗口截图需要 Android 14 或更高版本" }
        val result = suspendCancellableCoroutine<ScreenshotResult> { continuation ->
            takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (continuation.isActive) continuation.resume(screenshot)
                    else screenshot.hardwareBuffer.close()
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(AccessibilityScreenshotException(errorCode))
                    }
                }
            })
        }
        val buffer = result.hardwareBuffer
        return try {
            val bitmap = withContext(Dispatchers.Default) {
                val hardwareBitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)) {
                    "系统返回的窗口截图格式不受支持"
                }
                try {
                    checkNotNull(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)) {
                        "窗口截图无法转换为可编码图片"
                    }
                } finally {
                    hardwareBitmap.recycle()
                }
            }
            AccessibilityWindowCapture(bitmap, target)
        } finally {
            buffer.close()
        }
    }

    fun writeText(displayId: Int, text: String, nodeRef: NodeRef?, mode: TextInputMode) {
        require(text.length <= 500) { "输入限制为 500 字符" }
        val nodes = nodes(displayId)
        try {
            val editable = nodes.filter { it.isEditable && it.isEnabled && it.isVisibleToUser }
            val requested = nodeRef?.let { expected ->
                val matches = editable.filter { it.reference() == expected }
                require(matches.size == 1) {
                    "目标节点已失效、不是唯一匹配或不可编辑"
                }
                matches.single()
            }
            val target = requested ?: editable.singleOrNull { it.isFocused } ?: editable.singleOrNull()
            checkNotNull(target) { "目标显示没有唯一可编辑节点，请先点选输入框" }
            val value = if (mode == TextInputMode.APPEND) target.text?.toString().orEmpty() + text else text
            require(value.length <= 2_000) { "输入后的总文本不能超过 2000 字符" }
            check(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) { "目标应用不支持节点文本输入" }
        } finally { nodes.forEach { it.recycle() } }
    }

    fun performNodeAction(displayId: Int, nodeRef: NodeRef, action: NodeActionKind) {
        require(action != NodeActionKind.SET_TEXT) { "文本输入必须使用 input_text" }
        val nodes = nodes(displayId)
        try {
            val matches = nodes.filter { it.isEnabled && it.isVisibleToUser && it.reference() == nodeRef }
            require(matches.size == 1) { "目标节点已失效或不是唯一匹配" }
            val actionId = actionId(action)
            check(matches.single().performAction(actionId)) { "目标节点不支持 ${action.name.lowercase()}" }
        } finally { nodes.forEach { it.recycle() } }
    }

    suspend fun performGesture(displayId: Int, strokes: List<GestureStroke>) {
        require(Build.VERSION.SDK_INT >= 30) { "复杂手势需要 Android 11 或更高版本" }
        require(strokes.isNotEmpty() && strokes.size <= 10) { "一次手势需要 1 到 10 条轨迹" }
        require(strokes.sumOf { it.points.size } <= 500) { "一次手势最多允许 500 个轨迹点" }
        require(strokes.maxOf { it.startTimeMs + it.durationMs } <= 10_000) { "一次手势最长为 10 秒" }
        val builder = GestureDescription.Builder().setDisplayId(displayId)
        strokes.forEach { stroke ->
            val path = Path().apply {
                moveTo(stroke.points.first().x.toFloat(), stroke.points.first().y.toFloat())
                stroke.points.drop(1).forEach { point -> lineTo(point.x.toFloat(), point.y.toFloat()) }
            }
            builder.addStroke(GestureDescription.StrokeDescription(
                path,
                stroke.startTimeMs,
                stroke.durationMs,
                false,
            ))
        }
        suspendCancellableCoroutine { continuation ->
            val accepted = dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("目标应用取消了复杂手势"))
                    }
                }
            }, null)
            if (!accepted && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("系统未接受复杂手势"))
            }
        }
    }

    private fun actionKind(actionId: Int): NodeActionKind? = when (actionId) {
        AccessibilityNodeInfo.ACTION_CLICK -> NodeActionKind.CLICK
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> NodeActionKind.LONG_CLICK
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> NodeActionKind.SCROLL_FORWARD
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> NodeActionKind.SCROLL_BACKWARD
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> NodeActionKind.SCROLL_UP
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> NodeActionKind.SCROLL_DOWN
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> NodeActionKind.SCROLL_LEFT
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> NodeActionKind.SCROLL_RIGHT
        AccessibilityNodeInfo.ACTION_SET_TEXT -> NodeActionKind.SET_TEXT
        else -> null
    }

    private fun actionId(action: NodeActionKind): Int = when (action) {
        NodeActionKind.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
        NodeActionKind.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
        NodeActionKind.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        NodeActionKind.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        NodeActionKind.SCROLL_UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        NodeActionKind.SCROLL_DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        NodeActionKind.SCROLL_LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        NodeActionKind.SCROLL_RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        NodeActionKind.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
    }

    private fun AccessibilityNodeInfo.reference(bounds: Rect = Rect().also(::getBoundsInScreen)): NodeRef {
        return NodeReference.create(
            windowId = windowId,
            viewId = viewIdResourceName,
            packageName = packageName?.toString(),
            className = className?.toString(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )
    }
}
