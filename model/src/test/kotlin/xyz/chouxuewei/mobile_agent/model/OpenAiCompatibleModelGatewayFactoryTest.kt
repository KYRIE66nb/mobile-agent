package xyz.chouxuewei.mobile_agent.model

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.ModelConfig
import xyz.chouxuewei.mobile_agent.core.ModelProbeResult
import xyz.chouxuewei.mobile_agent.core.NodeBounds
import xyz.chouxuewei.mobile_agent.core.NodeRef
import xyz.chouxuewei.mobile_agent.core.NodeSnapshot
import xyz.chouxuewei.mobile_agent.core.Observation
import xyz.chouxuewei.mobile_agent.core.Screenshot
import xyz.chouxuewei.mobile_agent.core.Viewport

class OpenAiCompatibleModelGatewayFactoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun probeUsesAliasAndIncludesCurrentScreenshot() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"qwen38-27b-real-id"}]}""",
        ).addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"{\"type\":\"needs_user\",\"reason\":\"已读取截图\"}"}}]}""",
        ).addHeader("Content-Type", "application/json"))
        val config = ModelConfig(server.url("/v1").toString(), "local", "test-key")
        val observation = Observation(
            id = "observation-1",
            sessionId = "session-1",
            capturedAtEpochMillis = 1,
            contentRevision = 1,
            viewport = Viewport(720, 1280),
            rotationDegrees = 0,
            foregroundPackage = "example.app",
            screenshot = Screenshot(byteArrayOf(1, 2, 3), "image/png", 720, 1280),
            nodes = listOf(
                NodeSnapshot(
                    ref = NodeRef("node-1"),
                    bounds = NodeBounds(10, 20, 110, 70),
                    text = "Android 版本",
                    contentDescription = null,
                    className = "android.widget.TextView",
                    packageName = "com.android.settings",
                    editable = false,
                    focused = false,
                    enabled = true,
                    visible = true,
                ),
            ),
        )

        val result = OpenAiCompatibleModelGatewayFactory().probe(config, observation)

        val connected = result as ModelProbeResult.Connected
        assertEquals("local", connected.model)
        assertTrue(connected.screenshotIncluded)
        val modelRequest = server.takeRequest()
        val completionRequest = server.takeRequest()
        assertEquals("Bearer test-key", modelRequest.getHeader("Authorization"))
        assertEquals("/v1/models", modelRequest.path)
        assertEquals("/v1/chat/completions", completionRequest.path)
        val body = completionRequest.body.readUtf8()
        assertTrue(body.contains("\"model\":\"local\""))
        assertTrue(body.contains("\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,AQID"))
        assertTrue(body.contains("bounds=[10,20][110,70]"))
        assertTrue(body.contains("Android 版本"))
    }
}
