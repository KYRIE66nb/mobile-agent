package xyz.chouxuewei.mobile_agent.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.ChatRequest
import xyz.chouxuewei.mobile_agent.core.ChatTurn
import xyz.chouxuewei.mobile_agent.core.ModelConfig
import xyz.chouxuewei.mobile_agent.core.ModelEvent

class OpenAiChatGatewayCompatibilityTest {
    private val complete = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    private fun request(effort: String? = null) = ChatRequest(listOf(ChatTurn("user", "hello")), 128, effort)
    private fun gateway(server: MockWebServer, model: String = "test") = OpenAiChatGateway(
        ModelConfig(server.url("/v1").toString(), model, "test-secret"), OkHttpClient(),
    )

    @Test fun genericBackendReceivesExplicitNone() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(complete))
            gateway(server).stream(request("none")).toList()
            val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("none", payload["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
        }
    }


    @Test fun glm53OmitsUnsupportedReasoningOff() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(complete))
            gateway(server, model = "glm-5.3-flash").stream(request("none")).toList()
            val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(payload.containsKey("reasoning_effort"))
        }
    }

    @Test fun genericBackendOmitsUnspecifiedEffort() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(complete))
            gateway(server).stream(request()).toList()
            val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(payload.containsKey("reasoning_effort"))
        }
    }

    @Test fun serverErrorCodeCannotExposeApiKey() = runBlocking {
        val apiKey = "x".repeat(65)
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"error":{"code":"$apiKey"}}""")
            )
            val error = gateway(server).stream(request()).toList().single() as ModelEvent.Error
            assertFalse(error.message, error.message.contains(apiKey))
            assertTrue(error.message, error.message.contains("HTTP 400"))
        }
    }

    @Test fun malformedServerErrorCodeIsNotDisplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"unsafe\nmessage"}}"""))
            val error = gateway(server).stream(request()).toList().single() as ModelEvent.Error
            assertFalse(error.message.contains("unsafe"))
            assertTrue(error.message.contains("HTTP 400"))
        }
    }
}
