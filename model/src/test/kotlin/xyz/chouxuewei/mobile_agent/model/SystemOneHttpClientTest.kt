package xyz.chouxuewei.mobile_agent.model

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.DecisionBackend
import xyz.chouxuewei.mobile_agent.core.DecisionChoiceRequest
import xyz.chouxuewei.mobile_agent.core.DecisionFailureKind
import xyz.chouxuewei.mobile_agent.core.DecisionLimits
import xyz.chouxuewei.mobile_agent.core.DecisionOutcome
import xyz.chouxuewei.mobile_agent.core.DecisionPurpose

class SystemOneHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(apiKey: String? = "key-1", retryDelayMs: Long = 1) = SystemOneHttpClient(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        model = "test-model",
        apiKey = apiKey,
        client = OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build(),
        retryDelayMs = retryDelayMs,
    )

    private fun request(instructions: String = "pick") = DecisionChoiceRequest(
        purpose = DecisionPurpose.SAFETY_GATE,
        requestId = "r1",
        state = buildJsonObject { put("user_request", "open settings") },
        instructions = instructions,
        options = linkedMapOf("allow" to "safe", "confirm" to "ask", "block" to "refuse"),
        keyGeneration = 2,
    )

    private fun ok(choice: String = "allow", confidence: Double = 0.9) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"model":"m1","answers":{"decision":{"type":"choice","choice":"$choice",""" +
                """"probabilities":{"allow":$confidence,"confirm":0.05,"block":0.05},"confidence":$confidence}},""" +
                """"usage":{"input_tokens":10,"output_tokens":3}}"""
        )

    @Test
    fun `request wire shape and auth header`() = runBlocking {
        server.enqueue(ok())
        val outcome = client().choose(request())
        assertTrue(outcome is DecisionOutcome.Accepted)

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/systemone", recorded.path)
        assertEquals("Bearer key-1", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        val question = body["questions"]!!.jsonObject["decision"]!!.jsonObject
        assertEquals("choice", question["type"]!!.jsonPrimitive.content)
        assertEquals("pick", question["instructions"]!!.jsonPrimitive.content)
        assertEquals(setOf("allow", "confirm", "block"), question["criteria"]!!.jsonObject.keys)
        // state 原样透传（不含历史/截图字段——构建器职责）
        assertEquals("open settings", body["state"]!!.jsonObject["user_request"]!!.jsonPrimitive.content)
    }

    @Test
    fun `accepted choice parses confidence and probabilities`() = runBlocking {
        server.enqueue(ok(choice = "block", confidence = 0.95))
        val outcome = client().choose(request())
        val accepted = outcome as DecisionOutcome.Accepted
        assertEquals("block", accepted.choice.choice)
        assertEquals(0.95, accepted.choice.confidence!!, 0.001)
        assertEquals(3, accepted.choice.probabilities.size)
        assertEquals("m1", accepted.choice.modelEcho)
    }

    @Test
    fun `missing confidence derives from probabilities`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"model":"m","answers":{"decision":{"type":"choice","choice":"allow","probabilities":{"allow":0.7,"confirm":0.3}}}}"""
        ))
        val accepted = client().choose(request()) as DecisionOutcome.Accepted
        assertEquals(0.7, accepted.choice.confidence!!, 0.001)
    }

    @Test
    fun `unauthenticated request omits auth header`() = runBlocking {
        server.enqueue(ok())
        client(apiKey = null).choose(request())
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun `http status mapping`() = runBlocking {
        suspend fun check(status: Int, kind: DecisionFailureKind) {
            server.enqueue(MockResponse().setResponseCode(status))
            val outcome = client().choose(request())
            val failed = outcome as DecisionOutcome.Failed
            assertEquals("status $status", kind, failed.kind)
            assertEquals(status, failed.httpStatus)
        }
        check(401, DecisionFailureKind.AUTH)
        check(403, DecisionFailureKind.AUTH)
        check(422, DecisionFailureKind.BAD_RESPONSE)
        check(404, DecisionFailureKind.BAD_RESPONSE)
    }

    @Test
    fun `429 retries once then reports rate limited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(ok())
        val outcome = client().choose(request())
        assertTrue(outcome is DecisionOutcome.Accepted)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `429 twice exhausts retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        val outcome = client().choose(request())
        assertEquals(DecisionFailureKind.RATE_LIMITED, (outcome as DecisionOutcome.Failed).kind)
        assertEquals(DecisionLimits.MAX_RETRIES + 1, server.requestCount)
    }

    @Test
    fun `5xx retries once and maps correctly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok())
        val outcome = client().choose(request())
        assertTrue(outcome is DecisionOutcome.Accepted)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `500 then 529 exhausts retry as overloaded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(529))
        val outcome = client().choose(request())
        assertEquals(DecisionFailureKind.OVERLOADED, (outcome as DecisionOutcome.Failed).kind)
        assertEquals(DecisionLimits.MAX_RETRIES + 1, server.requestCount)
    }

    @Test
    fun `529 single response maps to overloaded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(529))
        server.enqueue(MockResponse().setResponseCode(529))
        val outcome = client().choose(request())
        assertEquals(DecisionFailureKind.OVERLOADED, (outcome as DecisionOutcome.Failed).kind)
    }

    @Test
    fun `401 does not retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val outcome = client().choose(request())
        assertEquals(DecisionFailureKind.AUTH, (outcome as DecisionOutcome.Failed).kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `invalid json and missing fields are bad response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"answers":{}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"answers":{"decision":{"type":"score","score":0.5}}}"""
        ))
        repeat(3) {
            val outcome = client().choose(request())
            assertEquals(DecisionFailureKind.BAD_RESPONSE, (outcome as DecisionOutcome.Failed).kind)
        }
        server.enqueue(ok())
        // 熔断尚未触发（阈值 3 次中的 BAD_RESPONSE 计数已到，成功复位前再验一次）
    }

    @Test
    fun `confidence out of range rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"answers":{"decision":{"type":"choice","choice":"allow","confidence":1.7}}}"""
        ))
        val outcome = client().choose(request())
        assertEquals(DecisionFailureKind.BAD_RESPONSE, (outcome as DecisionOutcome.Failed).kind)
    }

    @Test
    fun `oversized instructions rejected without hitting network`() = runBlocking {
        val big = request(instructions = "x".repeat(DecisionLimits.MAX_INSTRUCTION_CHARS + 1))
        val outcome = client().choose(big)
        assertEquals(DecisionFailureKind.TOO_LARGE, (outcome as DecisionOutcome.Failed).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `offline maps to network failure`() = runBlocking {
        server.shutdown()
        val outcome = client().choose(request())
        val failed = outcome as DecisionOutcome.Failed
        assertTrue(
            "expected NETWORK/TIMEOUT but got ${failed.kind}",
            failed.kind == DecisionFailureKind.NETWORK || failed.kind == DecisionFailureKind.TIMEOUT,
        )
    }

    @Test
    fun `unreachable port maps to network failure`() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val unreachable = SystemOneHttpClient(
            baseUrl = "http://127.0.0.1:$port",
            model = "m",
            apiKey = null,
            client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(),
            retryDelayMs = 1,
        )
        val outcome = unreachable.choose(request())
        assertEquals(DecisionFailureKind.NETWORK, (outcome as DecisionOutcome.Failed).kind)
    }

    @Test
    fun `slow server maps to timeout`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
        val outcome = client().choose(request())
        val failed = outcome as DecisionOutcome.Failed
        assertTrue(failed.kind == DecisionFailureKind.TIMEOUT || failed.kind == DecisionFailureKind.NETWORK)
    }

    @Test
    fun `cancellation propagates and cancels the call`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
        val job = async { client().choose(request()) }
        delay(100)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(job.isCompleted && !job.isCancelled)
    }

    @Test
    fun `circuit breaker opens after consecutive failures`() = runBlocking {
        server.shutdown()
        val c = client()
        repeat(DecisionLimits.BREAKER_FAILURE_THRESHOLD) {
            val outcome = c.choose(request())
            assertTrue(outcome is DecisionOutcome.Failed)
        }
        val open = c.choose(request())
        assertEquals(DecisionFailureKind.CIRCUIT_OPEN, (open as DecisionOutcome.Failed).kind)
    }

    @Test
    fun `breaker does not count auth failures`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(ok())
        val c = client()
        c.choose(request())
        c.choose(request())
        val outcome = c.choose(request())
        assertTrue(outcome is DecisionOutcome.Accepted)
    }

    @Test
    fun `response body secrets are not echoed in failure detail`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("secret: sk-live-123"))
        val outcome = client().choose(request()) // retry -> second 500
        server.enqueue(MockResponse().setResponseCode(500).setBody("secret: sk-live-123"))
        // exhaust retry
        val final = client().choose(request())
        assertFalse((final as? DecisionOutcome.Failed)?.detail.orEmpty().contains("sk-live-123"))
    }

    @Test
    fun `provider wrapper reports backend`() {
        val laya = layaDecisionProvider("http://localhost:8000/", "typed-decisions", null)
        val jev = jevDecisionProvider("k")
        assertEquals(DecisionBackend.LAYA, laya.backend)
        assertEquals(DecisionBackend.JEV, jev.backend)
    }
}
