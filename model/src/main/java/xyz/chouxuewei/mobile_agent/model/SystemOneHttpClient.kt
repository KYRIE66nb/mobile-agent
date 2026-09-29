package xyz.chouxuewei.mobile_agent.model

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.chouxuewei.mobile_agent.core.DecisionBackend
import xyz.chouxuewei.mobile_agent.core.DecisionChoice
import xyz.chouxuewei.mobile_agent.core.DecisionChoiceRequest
import xyz.chouxuewei.mobile_agent.core.DecisionFailureKind
import xyz.chouxuewei.mobile_agent.core.DecisionLimits
import xyz.chouxuewei.mobile_agent.core.DecisionOutcome
import xyz.chouxuewei.mobile_agent.core.DecisionProvider
import xyz.chouxuewei.mobile_agent.core.limitViolation

/**
 * /v1/systemone 客户端：Laya 自建服务与 Jev 托管 API 共用的线协议适配。
 * - 请求体积/指令/选项在本地限额内才允许发送，超限直接 TOO_LARGE 不发包；
 * - 超时/断网/429/5xx 重试至多 MAX_RETRIES 次（决策 POST 幂等）；
 * - 连续失败 BREAKER_FAILURE_THRESHOLD 次后熔断 BREAKER_COOLDOWN_MS；
 * - 响应体按 MAX_RESPONSE_BYTES 截断并做严格结构校验；
 * - 取消直接传播，映射不到结果通道；
 * - 永不记录请求体或 Authorization。
 */
class SystemOneHttpClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    client: OkHttpClient? = null,
    private val retryDelayMs: Long = 400,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(DecisionLimits.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DecisionLimits.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(DecisionLimits.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(DecisionLimits.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val consecutiveFailures = AtomicInteger(0)
    private val breakerOpenUntil = AtomicLong(0)

    suspend fun choose(request: DecisionChoiceRequest): DecisionOutcome {
        if (clock() < breakerOpenUntil.get()) {
            return DecisionOutcome.Failed(DecisionFailureKind.CIRCUIT_OPEN)
        }
        val body = encodeRequest(request)
        val bytes = body.toByteArray(Charsets.UTF_8)
        request.limitViolation(bytes.size)?.let {
            return DecisionOutcome.Failed(it, detail = "request exceeds local limits")
        }
        var last: DecisionOutcome.Failed? = null
        repeat(DecisionLimits.MAX_RETRIES + 1) { attempt ->
            if (attempt > 0) delay(retryDelayMs)
            val outcome = execute(bytes)
            when {
                outcome is DecisionOutcome.Accepted -> {
                    consecutiveFailures.set(0)
                    return outcome
                }
                outcome is DecisionOutcome.Failed && retryable(outcome.kind) -> {
                    last = outcome
                    return@repeat
                }
                else -> {
                    recordFailure(outcome as? DecisionOutcome.Failed)
                    return outcome
                }
            }
        }
        recordFailure(last)
        return last ?: DecisionOutcome.Failed(DecisionFailureKind.NETWORK)
    }

    private fun retryable(kind: DecisionFailureKind) = kind == DecisionFailureKind.TIMEOUT ||
        kind == DecisionFailureKind.NETWORK ||
        kind == DecisionFailureKind.RATE_LIMITED ||
        kind == DecisionFailureKind.OVERLOADED ||
        kind == DecisionFailureKind.SERVER

    private fun recordFailure(failure: DecisionOutcome.Failed?) {
        if (failure == null || failure.kind == DecisionFailureKind.CIRCUIT_OPEN) return
        // 鉴权失败不计入熔断（配置问题，不是服务不可用）。
        if (failure.kind == DecisionFailureKind.AUTH) return
        if (consecutiveFailures.incrementAndGet() >= DecisionLimits.BREAKER_FAILURE_THRESHOLD) {
            breakerOpenUntil.set(clock() + DecisionLimits.BREAKER_COOLDOWN_MS)
        }
    }

    private fun encodeRequest(request: DecisionChoiceRequest): String {
        return buildJsonObject {
            put("state", request.state)
            put("model", model)
            putJsonObject("questions") {
                putJsonObject("decision") {
                    put("type", "choice")
                    put("instructions", request.instructions)
                    putJsonObject("criteria") {
                        request.options.forEach { (key, rubric) -> put(key, rubric) }
                    }
                }
            }
        }.toString()
    }

    private suspend fun execute(bodyBytes: ByteArray): DecisionOutcome {
        val startedAt = clock()
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/systemone")
            .post(bodyBytes.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply {
                apiKey?.takeIf(String::isNotBlank)?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()
        val response = try {
            http.newCall(httpRequest).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            return ioFailure(e)
        }
        // 响应体读取（body.string()）同样会抛 IO/超时，必须纳入同一失败映射；
        // 协程取消优先于 IO 语义，保证取消总是向上传播。
        return try {
            response.use { parseResponse(it, startedAt) }
        } catch (e: IOException) {
            ioFailure(e)
        }
    }

    private suspend fun ioFailure(e: IOException): DecisionOutcome {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return when (e) {
            is SocketTimeoutException, is InterruptedIOException ->
                DecisionOutcome.Failed(DecisionFailureKind.TIMEOUT, detail = e.javaClass.simpleName)
            else -> DecisionOutcome.Failed(DecisionFailureKind.NETWORK, detail = e.javaClass.simpleName)
        }
    }

    private fun parseResponse(response: Response, startedAt: Long): DecisionOutcome {
        val status = response.code
        if (status != 200) {
            // 不读取/不回显错误响应正文，防止服务端信息泄漏到日志。
            return DecisionOutcome.Failed(statusToKind(status), httpStatus = status)
        }
        val raw = response.body?.string() ?: ""
        if (raw.length > DecisionLimits.MAX_RESPONSE_BYTES) {
            return DecisionOutcome.Failed(DecisionFailureKind.BAD_RESPONSE, httpStatus = 200, detail = "oversized")
        }
        val latency = (clock() - startedAt).coerceAtLeast(0)
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val answers = root["answers"]?.jsonObject
                ?: return badResponse(latency, "missing answers")
            val decision = answers["decision"]?.jsonObject
                ?: return badResponse(latency, "missing decision answer")
            if (decision["type"]?.jsonPrimitive?.contentOrNull != "choice") {
                return badResponse(latency, "unexpected answer type")
            }
            val choice = decision["choice"]?.jsonPrimitive?.contentOrNull
                ?: return badResponse(latency, "missing choice")
            val confidence = decision["confidence"]?.jsonPrimitive?.doubleOrNull
            if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
                return badResponse(latency, "confidence out of range")
            }
            val probabilities: Map<String, Double> = decision["probabilities"]?.jsonObject
                ?.entries
                ?.mapNotNull { e -> e.value.jsonPrimitive.doubleOrNull?.let { e.key to it } }
                ?.toMap()
                ?.filterValues { it in 0.0..1.0 }
                ?: emptyMap()
            val effectiveConfidence = confidence ?: probabilities[choice]
            DecisionOutcome.Accepted(
                DecisionChoice(
                    choice = choice,
                    confidence = effectiveConfidence,
                    probabilities = probabilities,
                    latencyMillis = latency,
                    modelEcho = root["model"]?.jsonPrimitive?.contentOrNull?.take(120),
                )
            )
        } catch (e: Exception) {
            badResponse(latency, e.javaClass.simpleName)
        }
    }

    private fun badResponse(latency: Long, detail: String) =
        DecisionOutcome.Failed(DecisionFailureKind.BAD_RESPONSE, httpStatus = 200, detail = detail)

    private fun statusToKind(status: Int) = when (status) {
        401, 403 -> DecisionFailureKind.AUTH
        408 -> DecisionFailureKind.TIMEOUT
        413, 422, 400, 404 -> DecisionFailureKind.BAD_RESPONSE
        429 -> DecisionFailureKind.RATE_LIMITED
        529 -> DecisionFailureKind.OVERLOADED
        in 500..599 -> DecisionFailureKind.SERVER
        else -> DecisionFailureKind.BAD_RESPONSE
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response))
                else response.close()
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/**
 * 专用后端 Provider：Laya 与 Jev 共用同一客户端，仅 endpoint/model/密钥不同。
 * apiKey 可为空（本地 Laya 不开 LAYA_API_KEY 时）。
 */
class SystemOneDecisionProvider(
    override val backend: DecisionBackend,
    baseUrl: String,
    model: String,
    apiKey: String?,
    client: OkHttpClient? = null,
) : DecisionProvider {
    private val http = SystemOneHttpClient(baseUrl, model, apiKey, client)

    override suspend fun choose(request: DecisionChoiceRequest): DecisionOutcome =
        http.choose(request)
}

fun layaDecisionProvider(baseUrl: String, model: String, apiKey: String?): DecisionProvider =
    SystemOneDecisionProvider(DecisionBackend.LAYA, baseUrl.removeSuffix("/"), model, apiKey)

fun jevDecisionProvider(apiKey: String, baseUrl: String = "https://api.typesafe.ai", model: String = "jev-latest"): DecisionProvider =
    SystemOneDecisionProvider(DecisionBackend.JEV, baseUrl.removeSuffix("/"), model, apiKey)
