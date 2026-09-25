package xyz.chouxuewei.mobile_agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSettingsValidationTest {
    @Test
    fun `accepts full endpoints for both supported protocols`() {
        assertEquals(
            "https://example.com/v1/audio/transcriptions",
            validateHttpEndpoint(" https://example.com/v1/audio/transcriptions "),
        )
        assertEquals(
            "wss://iat-api.xfyun.cn/v2/iat",
            validateWebSocketEndpoint("wss://iat-api.xfyun.cn/v2/iat"),
        )
    }

    @Test
    fun `rejects using the wrong transport for a protocol`() {
        assertTrue(runCatching { validateHttpEndpoint("wss://example.com/iat") }.isFailure)
        assertTrue(runCatching { validateWebSocketEndpoint("https://example.com/iat") }.isFailure)
    }
}
