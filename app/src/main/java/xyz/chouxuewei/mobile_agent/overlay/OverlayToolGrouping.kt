package xyz.chouxuewei.mobile_agent.overlay

import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus

internal data class OverlayToolGroups(
    val completed: List<ToolCallRecord>,
    val highlighted: List<ToolCallRecord>,
)

/** 等待授权由独立卡片承载；成功步骤可折叠，其它状态必须保持可见，避免错误被摘要吞掉。 */
internal fun groupOverlayToolCalls(calls: List<ToolCallRecord>): OverlayToolGroups {
    val visible = calls.filterNot { it.status == ToolCallStatus.WAITING_APPROVAL }
    return OverlayToolGroups(
        completed = visible.filter { it.status == ToolCallStatus.SUCCEEDED },
        highlighted = visible.filterNot { it.status == ToolCallStatus.SUCCEEDED },
    )
}
