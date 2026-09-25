package xyz.chouxuewei.mobile_agent.model

import xyz.chouxuewei.mobile_agent.core.localizedText
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response

data class SpeechTranscriptionConfig(
    val endpointUrl: String,
    val model: String,
    val apiKey: String,
)

/** 调用 OpenAI 兼容的 /audio/transcriptions，多段表单仅包含 file 与 model。 */
class SpeechTranscriptionGateway(
    private val client: OkHttpClient,
) {
    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .build(),
    )
    suspend fun transcribe(config: SpeechTranscriptionConfig, audioFile: File): String {
        val text = request(config, audioFile).trim()
        require(text.isNotEmpty()) { localizedText("没有识别到有效语音，请重试", "No speech was recognized. Please try again.") }
        return text
    }

    /** 设置页可用静音 WAV 验证鉴权与协议；空转写仍代表连接成功。 */
    suspend fun verify(config: SpeechTranscriptionConfig, audioFile: File) {
        request(config, audioFile)
    }

    private suspend fun request(config: SpeechTranscriptionConfig, audioFile: File): String {
        require(config.endpointUrl.startsWith("http://") || config.endpointUrl.startsWith("https://")) {
            localizedText("语音 API 地址格式不正确", "The speech API URL format is invalid.")
        }
        require(config.model.isNotBlank()) { localizedText("请填写语音模型 ID", "Enter a speech model ID.") }
        require(config.apiKey.isNotBlank()) { localizedText("请填写语音 API 密钥", "Enter the speech API key.") }
        require(audioFile.isFile && audioFile.length() > 0L) { localizedText("录音文件不可用，请重新录音", "The recording file is unavailable. Record again.") }
        require(audioFile.length() <= MAX_AUDIO_BYTES) { localizedText("录音文件超过 25 MB，请缩短录音", "The recording exceeds 25 MB. Shorten it.") }

        val mimeType = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "audio/mp4"
        }.toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model.trim())
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mimeType))
            .build()
        val call = client.newCall(
            Request.Builder()
                .url(config.endpointUrl.trim())
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "application/json")
                .post(body)
                .build(),
        )
        val response = call.await()
        response.use {
            if (!it.isSuccessful) throw IllegalStateException(httpErrorMessage(it.code))
            val raw = it.body?.string().orEmpty()
            return runCatching {
                Json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: throw IllegalStateException(localizedText("语音接口返回格式不兼容，需要 JSON 文本字段 text", "The speech API response is incompatible; it must contain the JSON text field."))
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        400 -> localizedText("语音接口拒绝了请求，请检查 API 地址和模型 ID", "The speech API rejected the request. Check the API URL and model ID.")
        401, 403 -> localizedText("语音 API 鉴权失败，请检查密钥", "Speech API authentication failed. Check the key.")
        404 -> localizedText("找不到语音转写接口，请填写完整的 /v1/audio/transcriptions 地址", "The transcription endpoint was not found. Enter the full /v1/audio/transcriptions URL.")
        408, 429 -> localizedText("语音接口繁忙，请稍后重试", "The speech API is busy. Please try again later.")
        in 500..599 -> localizedText("语音服务暂时不可用，请稍后重试", "The speech service is temporarily unavailable. Please try again later.")
        else -> localizedText("语音转写失败（HTTP $code）", "Speech transcription failed (HTTP $code).")
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(
                IOException(localizedText("无法连接语音服务，请检查网络和 API 地址", "Could not connect to the speech service. Check your network and API URL."), error),
            )
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
