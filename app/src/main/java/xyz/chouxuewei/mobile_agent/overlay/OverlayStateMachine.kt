package xyz.chouxuewei.mobile_agent.overlay

import xyz.chouxuewei.mobile_agent.core.localizedText
/** 悬浮层的展示层级与任务状态分开保存，任务变化不会意外覆盖用户正在操作的完整聊天。 */
internal enum class OverlayPresentation { EDGE_HANDLE, SUMMARY, FULL_CHAT }

internal enum class FullWindowDockTarget { LEFT, RIGHT }

/** 完整窗需要继续推过安全边界一小段才吸附，防止原本靠边时的纵向拖动误触发。 */
internal fun fullWindowDockTarget(
    rawX: Float,
    minimumX: Float,
    maximumX: Float,
    threshold: Float,
): FullWindowDockTarget? {
    require(minimumX <= maximumX) { localizedText("窗口横向范围无效", "Invalid horizontal window range.") }
    require(threshold > 0f) { localizedText("吸附阈值必须大于 0", "Docking threshold must be greater than 0.") }
    return when {
        minimumX - rawX >= threshold -> FullWindowDockTarget.LEFT
        rawX - maximumX >= threshold -> FullWindowDockTarget.RIGHT
        else -> null
    }
}

internal class OverlayStateMachine(
    initial: OverlayPresentation = OverlayPresentation.EDGE_HANDLE,
) {
    var presentation: OverlayPresentation = initial
        private set

    fun showSummary() {
        presentation = OverlayPresentation.SUMMARY
    }

    fun showFullChat() {
        presentation = OverlayPresentation.FULL_CHAT
    }

    fun showEdgeHandle() {
        presentation = OverlayPresentation.EDGE_HANDLE
    }

    fun collapseOneLevel() {
        presentation = when (presentation) {
            OverlayPresentation.FULL_CHAT -> OverlayPresentation.SUMMARY
            OverlayPresentation.SUMMARY -> OverlayPresentation.EDGE_HANDLE
            OverlayPresentation.EDGE_HANDLE -> OverlayPresentation.EDGE_HANDLE
        }
    }

    /** 授权、询问或任务完成只提升低层状态；不能把用户正在使用的完整聊天降级。 */
    fun drawAttention() {
        if (presentation != OverlayPresentation.FULL_CHAT) {
            presentation = OverlayPresentation.SUMMARY
        }
    }

    fun collapseIdleSummary(hasWork: Boolean) {
        if (!hasWork && presentation == OverlayPresentation.SUMMARY) {
            presentation = OverlayPresentation.EDGE_HANDLE
        }
    }
}
