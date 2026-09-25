package xyz.chouxuewei.mobile_agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayStateMachineTest {
    @Test
    fun `attention expands edge handle but preserves full chat`() {
        val state = OverlayStateMachine()

        state.drawAttention()
        assertEquals(OverlayPresentation.SUMMARY, state.presentation)

        state.showFullChat()
        state.drawAttention()
        assertEquals(OverlayPresentation.FULL_CHAT, state.presentation)
    }

    @Test
    fun `collapse moves one level at a time`() {
        val state = OverlayStateMachine()
        state.showFullChat()

        state.collapseOneLevel()
        assertEquals(OverlayPresentation.SUMMARY, state.presentation)

        state.collapseOneLevel()
        assertEquals(OverlayPresentation.EDGE_HANDLE, state.presentation)
    }

    @Test
    fun `opening the app can reset directly to the edge handle`() {
        val state = OverlayStateMachine(OverlayPresentation.FULL_CHAT)

        state.showEdgeHandle()

        assertEquals(OverlayPresentation.EDGE_HANDLE, state.presentation)
    }

    @Test
    fun `idle timeout only collapses summary without work`() {
        val state = OverlayStateMachine(OverlayPresentation.SUMMARY)

        state.collapseIdleSummary(hasWork = true)
        assertEquals(OverlayPresentation.SUMMARY, state.presentation)

        state.collapseIdleSummary(hasWork = false)
        assertEquals(OverlayPresentation.EDGE_HANDLE, state.presentation)
    }
}
