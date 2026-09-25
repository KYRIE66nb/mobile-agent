package xyz.chouxuewei.mobile_agent.model

import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IflytekSpeechTranscriptionGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var audio: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        audio = File.createTempFile("voice-", ".wav").apply {
            writeBytes(ByteArray(44).apply {
                "RIFF".toByteArray().copyInto(this)
            } + ByteArray(2_560) { (it % 7).toByte() })
        }
    }

    @After
    fun tearDown() {
        audio.delete()
        server.shutdown()
    }

    @Test
    fun `signs websocket request sends pcm frames and parses words`() = runBlocking {
        val statuses = mutableListOf<Int>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val status = Json.parseToJsonElement(text).jsonObject["data"]!!
                    .jsonObject["status"]!!.jsonPrimitive.int
                statuses += status
                if (status == 2) {
                    webSocket.send(
                        """{"code":0,"data":{"status":2,"result":{"sn":0,"ws":[{"cw":[{"w":"测试成功"}]}]}}}""",
                    )
                    webSocket.close(1000, null)
                }
            }
        }))
        val endpoint = server.url("/v2/iat").toString().replaceFirst("http", "ws")
        val config = IflytekSpeechTranscriptionConfig(endpoint, "app-id", "api-key", "api-secret")

        val text = IflytekSpeechTranscriptionGateway().transcribe(config, audio)

        assertEquals("测试成功", text)
        assertEquals(listOf(0, 1, 2), statuses)
        val request = server.takeRequest()
        assertNotNull(request.requestUrl?.queryParameter("authorization"))
        assertEquals(URI(endpoint).host, request.requestUrl?.queryParameter("host"))
        assertTrue(request.requestUrl?.queryParameter("date").orEmpty().contains("GMT"))
    }
}
