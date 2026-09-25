package xyz.chouxuewei.mobile_agent.model

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.*

class OpenAiChatGatewayTest {
    private val request = ChatRequest(listOf(ChatTurn("user", "请记住蓝色"), ChatTurn("assistant", "好的"), ChatTurn("user", "什么颜色？")), 512)
    private fun gateway(server: MockWebServer, client: OkHttpClient = OkHttpClient()) = OpenAiChatGateway(ModelConfig(server.url("/v1").toString(), "test", "test-secret"), client)
    private val complete = "data: {\"choices\":[{\"delta\":{\"content\":\"中文跨块🙂\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}\n\ndata: [DONE]\n\n"

    @Test fun defaultStreamingClientHasNoReadOrCallTimeout() {
        val client = streamingChatHttpClient()

        assertEquals(0, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertTrue(client.connectTimeoutMillis > 0)
    }

    @Test fun chineseAcrossBytesAndMultiturnRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(complete).throttleBody(1, 1, TimeUnit.MILLISECONDS))
            val events = gateway(server).stream(request).toList()
            assertEquals("中文跨块🙂", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
            assertEquals("stop", events.filterIsInstance<ModelEvent.Completed>().single().reason)
            assertEquals(12, events.filterIsInstance<ModelEvent.Usage>().single().inputTokens)
            val body = server.takeRequest().body.readUtf8()
            val json = Json.parseToJsonElement(body).jsonObject
            assertTrue(body.contains("什么颜色")); assertTrue(body.contains("assistant")); assertFalse(body.contains("observation"))
            assertTrue(json["stream"]!!.jsonPrimitive.boolean)
            assertTrue(json["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
            assertTrue(json["tools"]!!.jsonArray.isEmpty())
        }
    }
    @Test fun partialDisconnectRetainsDeltaAndReportsError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"保留\"}}]}\n\n"))
            val events=gateway(server).stream(request).toList()
            assertEquals("保留", (events.first() as ModelEvent.TextDelta).text)
            assertTrue(events.last() is ModelEvent.Error); assertFalse(events.any { it is ModelEvent.Completed })
        }
    }
    @Test fun httpErrorDoesNotEchoSecretsOrServerBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("test-secret"))
            val error=gateway(server).stream(request).toList().single() as ModelEvent.Error
            assertTrue(error.message.contains("401")); assertFalse(error.message.contains("test-secret"))
        }
    }
    @Test fun cancellationCancelsNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            val client=OkHttpClient()
            server.enqueue(MockResponse().setBody(complete).throttleBody(32, 100, TimeUnit.MILLISECONDS))
            val adapter=gateway(server,client)
            val job=launch { adapter.stream(request).collect() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5,TimeUnit.SECONDS)) }
            withTimeout(3000) { job.cancelAndJoin() }
            withTimeout(3000) { while(client.dispatcher.runningCallsCount()>0) delay(10) }
            assertEquals(0,client.dispatcher.runningCallsCount())
        }
    }
    @Test fun malformedEventIsNotSuccessfulCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: not-json\n\n"))
            assertTrue(gateway(server).stream(request).toList().single() is ModelEvent.Error)
        }
    }
    @Test fun lengthLimitIsExplicit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(complete.replace("stop", "length")))
            assertEquals("length", gateway(server).stream(request).toList().filterIsInstance<ModelEvent.Completed>().single().reason)
        }
    }
}
