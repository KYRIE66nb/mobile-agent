package xyz.chouxuewei.mobile_agent.model

import xyz.chouxuewei.mobile_agent.core.localizedText
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.toByteString
import xyz.chouxuewei.mobile_agent.core.*

/** 与设备动作协议完全独立的文本 SSE 适配器。取消 Flow 会同时关闭 Call 和响应体。 */
class OpenAiChatGateway(
    private val config: ModelConfig,
    private val client: OkHttpClient,
) : ChatModelGateway {
    constructor(config: ModelConfig) : this(config, streamingChatHttpClient())

    override fun stream(request: ChatRequest): Flow<ModelEvent> = callbackFlow {
        val model = config.model?.takeIf { it.isNotBlank() }
        if (model == null) {
            send(
                ModelEvent.Error(
                    localizedText(
                        "请在模型设置中填写模型 ID",
                        "Enter a model ID in Model settings."
                    )
                )
            ); close(); return@callbackFlow
        }
        val payload = buildJsonObject {
            put("model", model);
            put("stream", true);
            put("max_tokens", request.maxOutputTokens)
            put("max_completion_tokens", request.maxOutputTokens)
            // OpenAI 兼容流式接口默认可能不返回用量；显式请求后，vLLM 会在 [DONE] 前追加 usage 尾包。
            putJsonObject("stream_options") { put("include_usage", true) }
            // 始终显式传 tools，避免 LocalServer 将“字段缺失”解释为启用服务器 MCP。
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.id)
                            put("description", tool.description)
                            put(
                                "parameters",
                                runCatching { Json.parseToJsonElement(tool.inputSchema) }
                                    .getOrElse { buildJsonObject { put("type", "object") } })
                        }
                    })
                }
            }
            request.reasoningEffort?.takeIf(String::isNotBlank)?.let { effort ->
                config.reasoningEffortField.takeIf(String::isNotBlank)
                    ?.let { field -> put(field, effort) }
            }
            putJsonArray("messages") {
                request.messages.forEach { turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        if (turn.role == "assistant" && turn.toolCalls.isNotEmpty()) {
                            if (turn.content.isEmpty()) put("content", JsonNull) else put(
                                "content",
                                turn.content
                            )
                            putJsonArray("tool_calls") {
                                turn.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("id", call.id); put("type", "function")
                                        putJsonObject("function") {
                                            put("name", call.toolId); put(
                                            "arguments",
                                            call.argumentsJson
                                        )
                                        }
                                    })
                                }
                            }
                        } else {
                            if (turn.role == "user" && turn.images.isNotEmpty()) {
                                putJsonArray("content") {
                                    if (turn.content.isNotBlank()) add(buildJsonObject {
                                        put("type", "text")
                                        put("text", turn.content)
                                    })
                                    turn.images.forEach { image ->
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put(
                                                    "url",
                                                    "data:${image.mimeType};base64,${
                                                        image.bytes.toByteString().base64()
                                                    }"
                                                )
                                                put("detail", "auto")
                                            }
                                        })
                                    }
                                }
                            } else {
                                put("content", turn.content)
                            }
                        }
                        turn.toolCallId?.let { put("tool_call_id", it) }
                        turn.name?.let { put("name", it) }
                    })
                }
            }
        }

        val base = config.baseUrl.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
        val payloadText = payload.toString()
        val requestStartedAt = System.currentTimeMillis()
        AgentLog.i("Model") {
            "request_start model=$model messages=${request.messages.size} tools=${request.tools} images=${request.messages.sumOf { it.images.size }} payload_bytes=${payloadText.toByteArray().size} max_output=${request.maxOutputTokens}"
        }
        val call = client.newCall(
            Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "text/event-stream")
                .post(payloadText.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
        call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AgentLog.e("Model", e) {
                        "request_failed model=$model cancelled=${call.isCanceled()} duration_ms=${System.currentTimeMillis() - requestStartedAt}"
                    }
                    if (!call.isCanceled()) trySend(
                        ModelEvent.Error(
                            localizedText(
                                "无法连接模型服务，请检查网络和服务地址",
                                "Could not connect to the model service. Check your network and service URL."
                            )
                        )
                    )
                    close()
                }

                override fun onResponse(call: Call, response: Response) {
                    AgentLog.i("Model") { "response_headers model=$model code=${response.code}" }
                    launch(Dispatchers.IO) {
                        try {
                            response.use {
                                if (!it.isSuccessful) {
                                    val hasImages =
                                        request.messages.any { turn -> turn.images.isNotEmpty() }
                                    val message = when {
                                        it.code == 401 || it.code == 403 ->
                                            localizedText(
                                                "API 密钥无效或没有访问权限，请检查模型设置",
                                                "The API key is invalid or lacks access. Check Model settings."
                                            )

                                        it.code == 404 ->
                                            localizedText(
                                                "没有找到该服务或模型，请检查服务地址和模型 ID",
                                                "The service or model was not found. Check the service URL and model ID."
                                            )

                                        it.code == 408 -> localizedText(
                                            "请求超时，请稍后重试",
                                            "The request timed out. Please try again later."
                                        )

                                        it.code == 413 -> localizedText(
                                            "本次发送的内容过大，请减少附件或缩短输入后重试",
                                            "This message is too large. Remove attachments or shorten the input and try again."
                                        )

                                        it.code == 429 ->
                                            localizedText(
                                                "请求过于频繁或账户额度不足，请稍后重试或检查账户额度",
                                                "Too many requests or insufficient account quota. Try again later or check your quota."
                                            )

                                        it.code in 500..599 -> localizedText(
                                            "模型服务暂时不可用，请稍后重试",
                                            "The model service is temporarily unavailable. Please try again later."
                                        )

                                        hasImages && it.code in 400..422 ->
                                            localizedText(
                                                "当前模型不支持图片理解，请更换支持图片的模型",
                                                "The current model does not support image understanding. Choose a vision-capable model."
                                            )

                                        else -> localizedText(
                                            "模型服务拒绝了本次请求，请检查模型设置后重试",
                                            "The model service rejected this request. Check Model settings and try again."
                                        )
                                    }
                                    // 保留 HTTP 状态码便于用户排查，同时不回显可能包含密钥或隐私的服务端响应正文。
                                    send(ModelEvent.Error("$message (HTTP ${it.code})")); return@use
                                }
                                val source = it.body?.source() ?: throw IOException("empty body")
                                val data = StringBuilder()
                                var done = false
                                var finish: String? = null

                                data class PendingCall(
                                    var id: String = "",
                                    var name: String = "",
                                    val arguments: StringBuilder = StringBuilder()
                                )

                                val pendingCalls = linkedMapOf<Int, PendingCall>()
                                suspend fun dispatch() {
                                    val value = data.toString().trim(); data.clear()
                                    if (value.isEmpty()) return
                                    if (value == "[DONE]") {
                                        done = true; return
                                    }
                                    val json = Json.parseToJsonElement(value).jsonObject
                                    if (json["error"] != null) throw IOException("server error")
                                    json["usage"]?.takeUnless { u -> u is JsonNull }?.jsonObject?.let { usage ->
                                        send(
                                            ModelEvent.Usage(
                                                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                                                usage["completion_tokens"]?.jsonPrimitive?.intOrNull
                                            )
                                        )
                                    }
                                    val choice =
                                        json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                            ?: return
                                    val delta = choice["delta"]?.jsonObject
                                    delta?.get("tool_calls")?.jsonArray?.forEach { element ->
                                        val value = element.jsonObject
                                        val index = value["index"]?.jsonPrimitive?.intOrNull
                                            ?: pendingCalls.size
                                        val pending = pendingCalls.getOrPut(index) { PendingCall() }
                                        value["id"]?.jsonPrimitive?.contentOrNull?.let {
                                            pending.id = it
                                        }
                                        value["function"]?.jsonObject?.let { function ->
                                            function["name"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                                // tool_calls 的 name 与 arguments 都是 delta 片段，必须按到达顺序原样拼接。
                                                pending.name += chunk
                                            }
                                            function["arguments"]?.jsonPrimitive?.contentOrNull?.let(
                                                pending.arguments::append
                                            )
                                        }
                                    }
                                    listOf(
                                        "reasoning_content",
                                        "reasoning",
                                        "thinking"
                                    ).firstNotNullOfOrNull { key ->
                                        (delta?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(
                                            String::isNotEmpty
                                        )
                                    }?.let { text -> send(ModelEvent.ReasoningDelta(text)) }
                                    (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                                        ?.takeIf(String::isNotEmpty)
                                        ?.let { text -> send(ModelEvent.TextDelta(text)) }
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                                        finish = reason
                                    }
                                }
                                // Okio 在完整 UTF-8 行上解码，网络包切在中文字符中间也不会乱码。
                                while (!done) {
                                    val line = source.readUtf8Line() ?: break
                                    when {
                                        line.isEmpty() -> dispatch()
                                        line.startsWith("data:") -> {
                                            if (data.isNotEmpty()) data.append('\n'); data.append(
                                                line.removePrefix("data:").trimStart()
                                            )
                                        }
                                    }
                                    check(data.length <= 1_000_000) {
                                        localizedText(
                                            "模型事件过大",
                                            "The model event is too large."
                                        )
                                    }
                                }
                                if (data.isNotEmpty()) dispatch()
                                pendingCalls.values.forEach { pending ->
                                    if (pending.id.isNotBlank() && pending.name.isNotBlank()) {
                                        send(
                                            ModelEvent.ToolCall(
                                                RequestedToolCall(
                                                    pending.id,
                                                    pending.name,
                                                    pending.arguments.toString().ifBlank { "{}" },
                                                )
                                            )
                                        )
                                    }
                                }
                                AgentLog.i("Model") {
                                    "response_complete model=$model finish=${finish ?: if (done) "stop" else "interrupted"} tool_calls=${pendingCalls.size} duration_ms=${System.currentTimeMillis() - requestStartedAt}"
                                }
                                if (!done && finish == null) send(
                                    ModelEvent.Error(
                                        localizedText(
                                            "回复意外中断，已生成的内容已保留",
                                            "The response was interrupted. Generated content was preserved."
                                        )
                                    )
                                )
                                else if (finish != null && finish !in setOf(
                                        "stop",
                                        "length",
                                        "tool_calls"
                                    )
                                ) send(
                                    ModelEvent.Error(
                                        localizedText(
                                            "模型提前结束，已生成的内容已保留",
                                            "The model ended early. Generated content was preserved."
                                        )
                                    )
                                )
                                else send(ModelEvent.Completed(finish ?: "stop"))
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            AgentLog.e("Model", error) {
                                "response_failed model=$model duration_ms=${System.currentTimeMillis() - requestStartedAt}"
                            }
                            if (!call.isCanceled()) send(
                                ModelEvent.Error(
                                    localizedText(
                                        "回复意外中断，已生成的内容已保留",
                                        "The response was interrupted. Generated content was preserved."
                                    )
                                )
                            )
                        } finally { response.close(); close() }
                    }
                }
        })
        awaitClose { call.cancel() }
    }
}

/**
 * 流式回复可能在模型长时间推理或组装大型工具参数时暂时没有网络数据。
 * 这里保留 OkHttp 默认的建连超时，但不限制读取间隔和请求总时长；用户停止任务仍会取消 Call。
 */
internal fun streamingChatHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .build()
