package xyz.chouxuewei.mobile_agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus

class OverlayToolGroupingTest {
    @Test
    fun `successful steps collapse while pending and failed steps stay visible`() {
        val groups = groupOverlayToolCalls(
            listOf(
                call("done-1", ToolCallStatus.SUCCEEDED),
                call("approval", ToolCallStatus.WAITING_APPROVAL),
                call("running", ToolCallStatus.EXECUTING),
                call("done-2", ToolCallStatus.SUCCEEDED),
                call("failed", ToolCallStatus.FAILED),
            ),
        )

        assertEquals(listOf("done-1", "done-2"), groups.completed.map(ToolCallRecord::id))
        assertEquals(listOf("running", "failed"), groups.highlighted.map(ToolCallRecord::id))
    }

    private fun call(id: String, status: ToolCallStatus) = ToolCallRecord(
        id = id,
        conversationId = "conversation",
        runId = "run",
        replyMessageId = "reply",
        toolId = "tool-$id",
        argumentsJson = "{}",
        status = status,
        createdAt = 1L,
    )
}
