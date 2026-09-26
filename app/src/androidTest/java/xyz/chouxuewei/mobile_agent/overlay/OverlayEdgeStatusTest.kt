package xyz.chouxuewei.mobile_agent.overlay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.chat.ComposerDraft
import xyz.chouxuewei.mobile_agent.core.ToolApprovalRequest
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.voice.VoiceInputSource
import xyz.chouxuewei.mobile_agent.voice.VoiceInputState
import xyz.chouxuewei.mobile_agent.voice.VoiceInputTarget

@RunWith(AndroidJUnit4::class)
class OverlayEdgeStatusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun runningAndTranscribingUseAnimatedIndicatorWhileIdleAndCompletedStayStatic() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(OverlayViewState())
        compose.setContent { OverlayContent(state.value, NoOpOverlayActions) }

        compose.onNodeWithTag("overlay_edge_progress").assertDoesNotExist()
        compose.runOnIdle { state.value = OverlayViewState(activeConversations = setOf("running")) }
        compose.mainClock.advanceTimeBy(32)
        val first = compose.onNodeWithTag("overlay_edge_progress").assertExists().captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(320)
        val second = compose.onNodeWithTag("overlay_edge_progress").assertExists().captureToImage().toPixelMap()
        assertTrue("执行中进度环应随时间改变绘制内容", pixelsDiffer(first, second))

        val target = VoiceInputTarget("voice", null, null, VoiceInputSource.OVERLAY)
        compose.runOnIdle {
            state.value = OverlayViewState(voiceInputState = VoiceInputState.Transcribing(target))
        }
        compose.mainClock.advanceTimeBy(32)
        val transcribingFirst = compose.onNodeWithTag("overlay_edge_progress")
            .assertExists().captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(320)
        val transcribingSecond = compose.onNodeWithTag("overlay_edge_progress")
            .assertExists().captureToImage().toPixelMap()
        assertTrue("语音转写进度环应随时间改变绘制内容", pixelsDiffer(transcribingFirst, transcribingSecond))

        compose.runOnIdle { state.value = OverlayViewState(completionConversationId = "completed") }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("overlay_edge_progress").assertDoesNotExist()
    }

    @Test
    fun virtualScreenCanSwitchBetweenCompactAndFocusedLayouts() {
        val state = mutableStateOf(
            OverlayViewState(
                presentation = OverlayPresentation.FULL_CHAT,
                mode = ExecutionMode.VIRTUAL_DISPLAY,
            ),
        )
        compose.setContent { OverlayContent(state.value, NoOpOverlayActions) }

        compose.onNodeWithTag("overlay_virtual_screen_compact").assertExists()
        compose.onNodeWithTag("overlay_virtual_screen_focused").assertDoesNotExist()
        compose.onNodeWithTag("overlay_virtual_screen_image").performClick()
        compose.onNodeWithTag("overlay_virtual_screen_focused").assertExists()

        compose.runOnIdle { state.value = state.value.copy(mode = ExecutionMode.MAIN_DISPLAY) }
        compose.onNodeWithTag("overlay_virtual_screen_compact").assertDoesNotExist()
        compose.onNodeWithTag("overlay_virtual_screen_focused").assertDoesNotExist()
    }

    private fun pixelsDiffer(first: androidx.compose.ui.graphics.PixelMap, second: androidx.compose.ui.graphics.PixelMap): Boolean {
        if (first.width != second.width || first.height != second.height) return true
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first[x, y] != second[x, y]) return true
            }
        }
        return false
    }

    private object NoOpOverlayActions : OverlayActions {
        override fun expandSummary() = Unit
        override fun expandFullChat() = Unit
        override fun collapseOneLevel() = Unit
        override fun openConversation() = Unit
        override fun selectConversation(id: String) = Unit
        override fun editDraft(draft: ComposerDraft) = Unit
        override fun sendMessage() = Unit
        override fun startVoiceInput() = false
        override fun finishVoiceInput(action: OverlayVoiceReleaseAction) = Unit
        override fun stopCurrent() = Unit
        override fun decideApproval(
            request: ToolApprovalRequest,
            allow: Boolean,
            choiceId: String?,
            permanently: Boolean,
        ) = Unit
        override fun answerQuestion(request: UserQuestionRequest, answer: String?) = Unit
        override fun dragEdge(dx: Float, dy: Float, finished: Boolean) = Unit
        override fun dragSummary(dx: Float, dy: Float, finished: Boolean) = Unit
        override fun dragWindow(dx: Float, dy: Float, finished: Boolean) = Unit
        override fun resize(corner: ResizeCorner, dx: Float, dy: Float, finished: Boolean) = Unit
    }
}
