package xyz.chouxuewei.mobile_agent.model

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.toByteString
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.AppTarget
import xyz.chouxuewei.mobile_agent.core.Decision
import xyz.chouxuewei.mobile_agent.core.DeviceKey
import xyz.chouxuewei.mobile_agent.core.ModelConfig
import xyz.chouxuewei.mobile_agent.core.ModelGateway
import xyz.chouxuewei.mobile_agent.core.ModelGatewayFactory
import xyz.chouxuewei.mobile_agent.core.ModelProbeResult
import xyz.chouxuewei.mobile_agent.core.ModelRequest
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.core.Observation
import xyz.chouxuewei.mobile_agent.core.Viewport

/** OpenAI Chat Completions 兼容实现，适配当前局域网模型服务。 */
class OpenAiCompatibleModelGatewayFactory : ModelGatewayFactory {
    private val api = OpenAiApiClient(defaultClient())

    override fun create(config: ModelConfig): ModelGateway = OpenAiCompatibleModelGateway(config, api)

    override suspend fun probe(config: ModelConfig, observation: Observation?): ModelProbeResult =
        try {
            val models = api.listModels(config)
            val selectedModel = selectModel(config.model, models)
            val selectedConfig = ModelConfig(
                config.baseUrl,
                selectedModel,
                config.apiKey,
                config.reasoningEffortField,
            )
            val request = ModelRequest(
                instruction = "这是连接测试。请观察输入，只返回 needs_user，reason 简短说明已读取到的内容。不要返回设备动作。",
                observation = observation ?: emptyObservation(),
                recentResults = emptyList(),
            )
            val raw = api.complete(selectedConfig, request)
            val decision = ModelDecisionParser.parse(raw, request.observation.viewport)
            ModelProbeResult.Connected(
                model = selectedModel,
                responseSummary = decision.summary(),
                screenshotIncluded = observation?.screenshot != null,
            )
        } catch (error: Exception) {
            ModelProbeResult.Failed(error.safeMessage())
        }

    private fun selectModel(requested: String?, models: List<String>): String {
        require(models.isNotEmpty()) { "模型服务没有返回可用模型" }
        if (!requested.isNullOrBlank()) {
            // 一些兼容服务会接受 local 之类的路由别名，但模型列表只返回实际后端 ID。
            return requested
        }
        return models.firstOrNull { it.contains("qwen", true) && it.contains("27b", true) }
            ?: models.first()
    }

    private fun emptyObservation() = Observation(
        id = "model-probe",
        sessionId = "model-probe",
        capturedAtEpochMillis = System.currentTimeMillis(),
        contentRevision = 0,
        viewport = Viewport(1, 1),
        rotationDegrees = 0,
        foregroundPackage = null,
        screenshot = null,
        nodes = emptyList(),
    )

    private fun Decision.summary(): String = when (this) {
        is Decision.Execute -> "动作格式有效：${action.javaClass.simpleName}"
        is Decision.Completed -> "模型返回完成：$summary"
        is Decision.NeedsUser -> "模型返回：$reason"
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

private class OpenAiCompatibleModelGateway(
    private val config: ModelConfig,
    private val api: OpenAiApiClient,
) : ModelGateway {
    override suspend fun decide(request: ModelRequest): Decision {
        val raw = try {
            api.complete(config, request)
        } catch (cancelled: CancellationException) {
            // 停止任务和单步超时都依赖协程取消，不能把它误报成“需要用户处理”。
            throw cancelled
        } catch (error: Exception) {
            return Decision.NeedsUser(userFacingMessage(error, "模型请求失败，请稍后重试"))
        }
        return try {
            ModelDecisionParser.parse(raw, request.observation.viewport)
        } catch (error: Exception) {
            Decision.NeedsUser("模型返回了无法识别的操作指令，请重试")
        }
    }
}

private class OpenAiApiClient(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(config: ModelConfig): List<String> {
        validate(config)
        val request = requestBuilder(config, "models").get().build()
        val body = client.newCall(request).awaitBody()
        val root = json.parseToJsonElement(body).jsonObject
        return root["data"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }
    }

    suspend fun complete(config: ModelConfig, request: ModelRequest): String {
        validate(config)
        val model = requireNotNull(config.model?.takeIf { it.isNotBlank() }) { "尚未选择模型" }
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("temperature", 0.6)
            put("top_p", 0.95)
            // 部分本地推理模型即使关闭 thinking 仍会产生少量 reasoning，给最终 JSON 留出空间。
            put("max_tokens", 1_024)
            put("response_format", buildJsonObject {
                put("type", "json_object")
            })
            put("chat_template_kwargs", buildJsonObject {
                put("enable_thinking", false)
            })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.userContent())
                })
            })
        }
        val httpRequest = requestBuilder(config, "chat/completions")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val body = client.newCall(httpRequest).awaitBody()
        val choice = json.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("模型响应缺少 choices[0]")
        val message = choice["message"]?.jsonObject
            ?: throw IOException("模型响应缺少 choices[0].message")
        val content = message["content"]?.jsonPrimitive?.contentOrNull
        if (content.isNullOrBlank()) {
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "未知"
            val reasoningLength = message["reasoning_content"]?.jsonPrimitive?.contentOrNull?.length ?: 0
            throw IOException("模型最终输出为空，finish_reason=$finishReason，reasoning_chars=$reasoningLength")
        }
        return content
    }

    private fun requestBuilder(config: ModelConfig, path: String): Request.Builder =
        Request.Builder()
            .url("${apiBase(config.baseUrl)}/$path")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")

    private fun validate(config: ModelConfig) {
        require(config.apiKey.isNotBlank()) { "尚未配置 API 密钥" }
        require(config.baseUrl.startsWith("http://") || config.baseUrl.startsWith("https://")) {
            "服务地址必须以 http:// 或 https:// 开头"
        }
    }

    private fun apiBase(value: String): String {
        val base = value.trim().trimEnd('/')
        return if (base.endsWith("/v1")) base else "$base/v1"
    }

    private fun ModelRequest.userContent(): JsonElement = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", promptText())
        })
        observation.screenshot?.let { screenshot ->
            val encoded = screenshot.bytes.toByteString().base64()
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:${screenshot.mimeType};base64,$encoded")
                })
            })
        }
    }

    private fun ModelRequest.promptText(): String = buildString {
        appendLine("用户目标：$instruction")
        appendLine("识别结果ID：${observation.id}")
        appendLine("屏幕：${observation.viewport.width}x${observation.viewport.height}，旋转 ${observation.rotationDegrees}°")
        appendLine("前台包名：${observation.foregroundPackage ?: "未知"}")
        appendLine("可见节点：")
        observation.nodes.take(80).forEach { node ->
            append("- ref=${node.ref.value}, bounds=[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}], ")
            append("editable=${node.editable}, focused=${node.focused}, text=")
            appendLine((node.text ?: node.contentDescription ?: "").take(160))
        }
        if (recentResults.isNotEmpty()) {
            appendLine("最近动作结果：")
            recentResults.takeLast(4).forEach { appendLine("- $it") }
        }
    }

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val reason = serverError(body)
                        continuation.resumeWith(Result.failure(IOException("模型服务 HTTP ${it.code}：$reason")))
                    } else {
                        continuation.resumeWith(Result.success(body))
                    }
                }
            }
        })
    }

    private fun serverError(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["detail"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.take(300) ?: body.take(300).ifBlank { "无错误正文" }

    companion object {
        private const val SYSTEM_PROMPT = """
你是 Android 手机操作代理。你只能返回一个 JSON 对象，不能添加 Markdown 或解释文字。
动作名称必须放在 type 字段，不能使用 action 字段。例如：
{"type":"open_app","package_name":"com.android.settings"}
{"type":"completed","summary":"已经完成"}
允许的 type 和字段：
- tap: x, y
- long_press: x, y, duration_ms
- swipe: start_x, start_y, end_x, end_y, duration_ms
- input_text: text，可选 node_ref
- press_key: key，只能是 back 或 enter
- open_app: package_name，填写真实 Android 应用包名
- wait: duration_ms，范围 0..5000
- enable_node_access
- completed: summary
- needs_user: reason
坐标必须位于提供的屏幕尺寸内。一次只返回一个动作；信息不足时返回 needs_user。
当前识别结果是唯一事实来源。只有当前截图或可见节点已经直接证明用户的完整目标时，才允许返回 completed。
读取信息的任务必须在 summary 中写出当前识别结果里可见的准确标签和值；不得把设备型号、页面标题或推测值当成系统版本。
如果目标信息尚未显示，继续导航；看不清或无法确认时返回 needs_user，不得猜测完成。
completed 的 summary 不超过 80 个汉字，只概括完成证据，不罗列搜索结果标题，不在字符串内容中使用英文双引号。
可见节点提供 bounds 时，优先点击目标文字对应矩形的中心，避免凭截图估算相邻项目的坐标。
需要在多级页面中查找信息而“可见节点”为空时，先返回 enable_node_access，再依据下一次识别结果导航。
"""
    }
}

internal object ModelDecisionParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, viewport: Viewport): Decision {
        val value = json.parseToJsonElement(extractObject(raw)).jsonObject
        val rawType = value.string("type") ?: value.string("action") ?: value.string("status")
            ?: error("缺少字段 type")
        return when (rawType.lowercase().replace('-', '_')) {
            "tap" -> Decision.Execute(Action.Tap(value.x("x", viewport), value.y("y", viewport)))
            "long_press" -> Decision.Execute(Action.LongPress(
                value.x("x", viewport),
                value.y("y", viewport),
                value.duration(),
            ))
            "swipe" -> Decision.Execute(Action.Swipe(
                value.x("start_x", viewport),
                value.y("start_y", viewport),
                value.x("end_x", viewport),
                value.y("end_y", viewport),
                value.duration(),
            ))
            "input_text" -> Decision.Execute(Action.InputText(
                text = value.requiredString("text").also { require(it.length <= 2_000) { "输入文本过长" } },
                node = value["node_ref"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { xyz.chouxuewei.mobile_agent.core.NodeRef(it) },
            ))
            "press_key" -> Decision.Execute(Action.PressKey(when (value.requiredString("key").lowercase()) {
                "back" -> DeviceKey.BACK
                "enter" -> DeviceKey.ENTER
                else -> error("不支持的按键")
            }))
            "open_app", "launch_app" -> Decision.Execute(Action.OpenApp(
                AppTarget(value.requiredString("package_name")),
            ))
            "wait" -> Decision.Execute(Action.Wait(value.requiredLong("duration_ms").also {
                require(it in 0..5_000) { "等待时间超出范围" }
            }))
            "enable_node_access" -> Decision.Execute(Action.EnableNodeAccess)
            "completed", "complete", "done", "finish" -> Decision.Completed(
                value.string("summary") ?: value.string("message") ?: "任务已完成",
            )
            "needs_user", "need_user", "ask_user" -> Decision.NeedsUser(
                value.string("reason") ?: value.string("message") ?: "需要用户处理",
            )
            else -> error("未知动作类型")
        }
    }

    private fun JsonObject.x(name: String, viewport: Viewport): Int = requiredInt(name).also {
        require(it in 0 until viewport.width) { "$name 超出屏幕宽度" }
    }

    private fun JsonObject.y(name: String, viewport: Viewport): Int = requiredInt(name).also {
        require(it in 0 until viewport.height) { "$name 超出屏幕高度" }
    }

    private fun JsonObject.duration(): Int = (this["duration_ms"]?.jsonPrimitive?.intOrNull ?: 350).also {
        require(it in 100..5_000) { "动作时长超出范围" }
    }

    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: error("缺少字段 $name")

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: error("字段 $name 不是整数")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: error("字段 $name 不是整数")

    /** 兼容模型偶尔包裹的代码块，同时仍只接受第一个完整 JSON 对象。 */
    private fun extractObject(raw: String): String {
        val start = raw.indexOf('{')
        require(start >= 0) { "响应中没有 JSON 对象" }
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return raw.substring(start, index + 1)
                }
            }
        }
        error("JSON 对象不完整")
    }
}

private fun Exception.safeMessage(): String = message?.take(400) ?: javaClass.simpleName
