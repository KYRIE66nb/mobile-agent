package xyz.chouxuewei.mobile_agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayVoiceGestureTest {
    private val threshold = 48f
    private val hysteresis = 10f

    @Test
    fun neutralMovementKeepsCurrentConversationSend() {
        assertEquals(
            OverlayVoiceReleaseAction.SEND_CURRENT,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.SEND_CURRENT,
                verticalOffset = 30f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
    }

    @Test
    fun upwardMovementCancelsAndDownwardMovementCreatesConversation() {
        assertEquals(
            OverlayVoiceReleaseAction.CANCEL,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.SEND_CURRENT,
                verticalOffset = -48f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
        assertEquals(
            OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.SEND_CURRENT,
                verticalOffset = 48f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
    }

    @Test
    fun hysteresisPreventsBoundaryFlickerAndCenterRestoresDefault() {
        assertEquals(
            OverlayVoiceReleaseAction.CANCEL,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.CANCEL,
                verticalOffset = -40f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
        assertEquals(
            OverlayVoiceReleaseAction.SEND_CURRENT,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.CANCEL,
                verticalOffset = -30f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
        assertEquals(
            OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION,
                verticalOffset = 40f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
        assertEquals(
            OverlayVoiceReleaseAction.SEND_CURRENT,
            nextOverlayVoiceReleaseAction(
                OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION,
                verticalOffset = 30f,
                threshold = threshold,
                hysteresis = hysteresis,
            ),
        )
    }
}
