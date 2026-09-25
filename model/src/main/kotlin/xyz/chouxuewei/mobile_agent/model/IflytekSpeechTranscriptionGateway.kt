package xyz.chouxuewei.mobile_agent.model

import java.io.File
import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class IflytekSpeechTranscriptionConfig(
    val endpointUrl: String,
    val appId: String,
    val apiKey: String,
    val apiSecret: String,
    val language: String = "zh_cn",
    val accent: String = "mandarin",
)

/** 科大讯飞语音听写（流式版）WebAPI 适配器，输入为 16k/16bit/单声道 WAV。 */
class IflytekSpeechTranscriptionGateway(
    private val client: OkHttpClient,
) {
    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(75, TimeUnit.SECONDS)
            .build(),
    )
    suspend fun transcribe(config: IflytekSpeechTranscriptionConfig, audioFile: File): String {
        val text = request(config, audioFile).trim()
        require(text.isNotEmpty()) { "没有识别到有效语音，请重试" }
        return text
    }

    suspend fun verify(config: IflytekSpeechTranscriptionConfig, audioFile: File) {
        request(config, audioFile)
    }

    private suspend fun request(config: IflytekSpeechTranscriptionConfig, audioFile: File): String {
        validate(config, audioFile)
        val signedUrl = signedUrl(config)
        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val parts = Collections.synchronizedSortedMap(TreeMap<Int, String>())
            var sender: Thread? = null
            fun fail(message: String, cause: Throwable? = null) {
                if (finished.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException(message, cause))
                }
            }
            fun succeed() {
                if (finished.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(parts.values.joinToString(""))
                }
            }

            val webSocket = client.newWebSocket(
                Request.Builder().url(signedUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sender = Thread({
                            runCatching { sendAudio(webSocket, config, audioFile) }
                                .onFailure { fail("科大讯飞音频发送失败，请重试", it) }
                        }, "iflytek-audio-sender").apply { start() }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val root = Json.parseToJsonElement(text).jsonObject
                            val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
                            if (code != 0) {
                                fail(iflytekError(code))
                                webSocket.close(1000, null)
                                return
                            }
                            val data = root["data"]?.jsonObject
                            val result = data?.get("result")?.jsonObject
                            val sn = result?.get("sn")?.jsonPrimitive?.intOrNull
                            val words = result?.get("ws")?.jsonArray?.joinToString("") { wordGroup ->
                                wordGroup.jsonObject["cw"]?.jsonArray?.firstOrNull()
                                    ?.jsonObject?.get("w")?.jsonPrimitive?.contentOrNull.orEmpty()
                            }.orEmpty()
                            if (sn != null && words.isNotEmpty()) parts[sn] = words
                            if (data?.get("status")?.jsonPrimitive?.intOrNull == 2) {
                                succeed()
                                webSocket.close(1000, null)
                            }
                        }.onFailure { fail("科大讯飞返回格式不兼容", it) }
                    }

                    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                        val message = when (response?.code) {
                            401, 403 -> "科大讯飞鉴权失败，请检查 AppID、APIKey、APISecret 和设备时间"
                            else -> "无法连接科大讯飞语音服务，请检查网络和接口地址"
                        }
                        fail(message, error)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!finished.get()) fail("科大讯飞语音连接提前关闭，请重试")
                    }
                },
            )
            continuation.invokeOnCancellation {
                finished.set(true)
                sender?.interrupt()
                webSocket.cancel()
            }
        }
    }

    private fun sendAudio(webSocket: WebSocket, config: IflytekSpeechTranscriptionConfig, file: File) {
        val bytes = file.readBytes()
        val offset = if (bytes.size >= WAV_HEADER_BYTES && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            WAV_HEADER_BYTES
        } else 0
        var position = offset
        var status = 0
        while (position < bytes.size) {
            val end = (position + FRAME_BYTES).coerceAtMost(bytes.size)
            val audio = Base64.getEncoder().encodeToString(bytes.copyOfRange(position, end))
            val payload = buildJsonObject {
                if (status == 0) {
                    put("common", buildJsonObject { put("app_id", config.appId) })
                    put("business", buildJsonObject {
                        put("language", config.language)
                        put("domain", "iat")
                        put("accent", config.accent)
                        put("ptt", 1)
                    })
                }
                put("data", buildJsonObject {
                    put("status", status)
                    put("format", "audio/L16;rate=16000")
                    put("encoding", "raw")
                    put("audio", audio)
                })
            }
            check(webSocket.send(payload.toString())) { "WebSocket 已关闭" }
            position = end
            status = 1
            Thread.sleep(FRAME_INTERVAL_MILLIS)
        }
        check(webSocket.send(buildJsonObject {
            put("data", buildJsonObject {
                put("status", 2)
                put("format", "audio/L16;rate=16000")
                put("encoding", "raw")
                put("audio", "")
            })
        }.toString())) { "WebSocket 已关闭" }
    }

    internal fun signedUrl(config: IflytekSpeechTranscriptionConfig): String {
        val uri = URI(config.endpointUrl)
        val host = uri.host ?: error("科大讯飞接口地址缺少主机名")
        val path = uri.rawPath?.takeIf(String::isNotBlank) ?: "/"
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
        val signatureOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(config.apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(signatureOrigin.toByteArray(Charsets.UTF_8)))
        val authorizationOrigin = "api_key=\"${config.apiKey}\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.getEncoder().encodeToString(authorizationOrigin.toByteArray(Charsets.UTF_8))
        val httpEndpoint = when {
            config.endpointUrl.startsWith("wss://") -> "https://${config.endpointUrl.removePrefix("wss://")}"
            config.endpointUrl.startsWith("ws://") -> "http://${config.endpointUrl.removePrefix("ws://")}"
            else -> config.endpointUrl
        }
        val signedHttpUrl = httpEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("authorization", authorization)
            .addQueryParameter("date", date)
            .addQueryParameter("host", host)
            .build()
            .toString()
        return when {
            config.endpointUrl.startsWith("wss://") -> signedHttpUrl.replaceFirst("https://", "wss://")
            config.endpointUrl.startsWith("ws://") -> signedHttpUrl.replaceFirst("http://", "ws://")
            else -> signedHttpUrl
        }
    }

    private fun validate(config: IflytekSpeechTranscriptionConfig, audioFile: File) {
        require(config.endpointUrl.startsWith("ws://") || config.endpointUrl.startsWith("wss://")) {
            "科大讯飞接口地址格式不正确"
        }
        require(config.appId.isNotBlank()) { "请填写科大讯飞 AppID" }
        require(config.apiKey.isNotBlank()) { "请填写科大讯飞 APIKey" }
        require(config.apiSecret.isNotBlank()) { "请填写科大讯飞 APISecret" }
        require(audioFile.isFile && audioFile.length() > WAV_HEADER_BYTES) { "录音文件不可用，请重新录音" }
    }

    private fun iflytekError(code: Int): String = when (code) {
        10005, 10010, 10110, 11200, 11201, 11202, 11203 -> "科大讯飞服务未授权、额度不足或配置不匹配（$code）"
        10007, 10009, 10043, 10044 -> "科大讯飞无法识别当前音频格式（$code）"
        10114, 10200, 10222, 10700 -> "科大讯飞服务响应超时，请重试（$code）"
        else -> "科大讯飞语音转写失败（$code）"
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val FRAME_BYTES = 1280
        const val FRAME_INTERVAL_MILLIS = 40L
    }
}
