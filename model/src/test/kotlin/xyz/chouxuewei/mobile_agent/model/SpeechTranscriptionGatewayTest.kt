package xyz.chouxuewei.mobile_agent.model

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeechTranscriptionGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var audio: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        audio = File.createTempFile("voice-", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    }

    @After
    fun tearDown() {
        audio.delete()
        server.shutdown()
    }

    @Test
    fun `posts OpenAI compatible multipart request and parses text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"你好，世界\"}"))
        val config = SpeechTranscriptionConfig(server.url("/v1/audio/transcriptions").toString(), "whisper-test", "key-123")

        val result = SpeechTranscriptionGateway().transcribe(config, audio)

        assertEquals("你好，世界", result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer key-123", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("whisper-test"))
        assertTrue(body.contains("name=\"file\""))
    }

    @Test
    fun `does not expose key or server response in errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("server secret detail"))
        val key = "private-key-value"
        val failure = runCatching {
            SpeechTranscriptionGateway().transcribe(
                SpeechTranscriptionConfig(server.url("/speech").toString(), "model", key),
                audio,
            )
        }.exceptionOrNull()!!

        assertTrue(failure.message.orEmpty().contains("鉴权失败"))
        assertFalse(failure.message.orEmpty().contains(key))
        assertFalse(failure.message.orEmpty().contains("server secret detail"))
    }
}
