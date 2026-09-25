package xyz.chouxuewei.mobile_agent.data

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.speechSettingsDataStore by preferencesDataStore(name = "speech_settings")

enum class SpeechApiFormat(val storageValue: String) {
    OPENAI_COMPATIBLE("openai"),
    IFLYTEK_IAT("iflytek_iat");

    companion object {
        fun fromStorage(value: String?): SpeechApiFormat = entries.firstOrNull { it.storageValue == value }
            ?: OPENAI_COMPATIBLE
    }
}

data class OpenAiSpeechProfile(
    val endpointUrl: String = "https://api.openai.com/v1/audio/transcriptions",
    val model: String = "gpt-4o-mini-transcribe",
    val hasApiKey: Boolean = false,
) {
    val configured: Boolean get() = endpointUrl.isNotBlank() && model.isNotBlank() && hasApiKey
}

data class IflytekSpeechProfile(
    val endpointUrl: String = "wss://iat-api.xfyun.cn/v2/iat",
    val appId: String = "",
    val hasApiKey: Boolean = false,
    val hasApiSecret: Boolean = false,
    val language: String = "zh_cn",
    val accent: String = "mandarin",
) {
    val configured: Boolean
        get() = endpointUrl.isNotBlank() && appId.isNotBlank() && hasApiKey && hasApiSecret &&
            language.isNotBlank() && accent.isNotBlank()
}

data class SpeechSettings(
    val selectedFormat: SpeechApiFormat = SpeechApiFormat.OPENAI_COMPATIBLE,
    val openAi: OpenAiSpeechProfile = OpenAiSpeechProfile(),
    val iflytek: IflytekSpeechProfile = IflytekSpeechProfile(),
) {
    val configured: Boolean
        get() = when (selectedFormat) {
            SpeechApiFormat.OPENAI_COMPATIBLE -> openAi.configured
            SpeechApiFormat.IFLYTEK_IAT -> iflytek.configured
        }
}

sealed interface ResolvedSpeechSettings {
    val format: SpeechApiFormat
}

data class ResolvedOpenAiSpeechSettings(
    val endpointUrl: String,
    val model: String,
    val apiKey: String,
) : ResolvedSpeechSettings {
    override val format = SpeechApiFormat.OPENAI_COMPATIBLE
}

data class ResolvedIflytekSpeechSettings(
    val endpointUrl: String,
    val appId: String,
    val apiKey: String,
    val apiSecret: String,
    val language: String,
    val accent: String,
) : ResolvedSpeechSettings {
    override val format = SpeechApiFormat.IFLYTEK_IAT
}

/** 每种转写协议分别保存配置；切换当前协议不会覆盖另一套参数。 */
class SpeechSettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.speechSettingsDataStore
    private val secretCipher = SecureSecretCipher("mobile_agent_speech_api_key")

    val settings: Flow<SpeechSettings> = dataStore.data.map { values ->
        SpeechSettings(
            selectedFormat = SpeechApiFormat.fromStorage(values[Keys.SELECTED_FORMAT]),
            openAi = OpenAiSpeechProfile(
                endpointUrl = values[Keys.OPENAI_ENDPOINT] ?: DEFAULT_OPENAI_ENDPOINT,
                model = values[Keys.OPENAI_MODEL] ?: DEFAULT_OPENAI_MODEL,
                hasApiKey = hasSecret(values[Keys.OPENAI_KEY_CIPHERTEXT], values[Keys.OPENAI_KEY_IV]),
            ),
            iflytek = IflytekSpeechProfile(
                endpointUrl = values[Keys.IFLYTEK_ENDPOINT] ?: DEFAULT_IFLYTEK_ENDPOINT,
                appId = values[Keys.IFLYTEK_APP_ID].orEmpty(),
                hasApiKey = hasSecret(values[Keys.IFLYTEK_KEY_CIPHERTEXT], values[Keys.IFLYTEK_KEY_IV]),
                hasApiSecret = hasSecret(values[Keys.IFLYTEK_SECRET_CIPHERTEXT], values[Keys.IFLYTEK_SECRET_IV]),
                language = values[Keys.IFLYTEK_LANGUAGE] ?: "zh_cn",
                accent = values[Keys.IFLYTEK_ACCENT] ?: "mandarin",
            ),
        )
    }.distinctUntilChanged()

    suspend fun saveOpenAi(endpointUrl: String, model: String, newApiKey: String?) {
        val endpoint = validateHttpEndpoint(endpointUrl)
        val normalizedModel = validateField(model, localizedText("请填写语音模型 ID", "Enter a speech model ID."), 200)
        val key = validateOptionalSecret(newApiKey)
        dataStore.edit { values ->
            values[Keys.OPENAI_ENDPOINT] = endpoint
            values[Keys.OPENAI_MODEL] = normalizedModel
            key?.let(secretCipher::encrypt)?.let { encrypted ->
                values[Keys.OPENAI_KEY_CIPHERTEXT] = encrypted.ciphertext
                values[Keys.OPENAI_KEY_IV] = encrypted.iv
            }
            require(hasSecret(values[Keys.OPENAI_KEY_CIPHERTEXT], values[Keys.OPENAI_KEY_IV])) {
                localizedText("请填写 OpenAI 兼容 API 密钥", "Enter an OpenAI-compatible API key.")
            }
        }
    }

    suspend fun saveIflytek(
        endpointUrl: String,
        appId: String,
        newApiKey: String?,
        newApiSecret: String?,
        language: String,
        accent: String,
    ) {
        val endpoint = validateWebSocketEndpoint(endpointUrl)
        val normalizedAppId = validateField(appId, localizedText("请填写科大讯飞 AppID", "Enter the iFLYTEK AppID."), 200)
        val normalizedLanguage = validateField(language, localizedText("请填写讯飞语种", "Enter the iFLYTEK language."), 100)
        val normalizedAccent = validateField(accent, localizedText("请填写讯飞方言参数", "Enter the iFLYTEK dialect parameter."), 100)
        val apiKey = validateOptionalSecret(newApiKey)
        val apiSecret = validateOptionalSecret(newApiSecret)
        dataStore.edit { values ->
            values[Keys.IFLYTEK_ENDPOINT] = endpoint
            values[Keys.IFLYTEK_APP_ID] = normalizedAppId
            values[Keys.IFLYTEK_LANGUAGE] = normalizedLanguage
            values[Keys.IFLYTEK_ACCENT] = normalizedAccent
            apiKey?.let(secretCipher::encrypt)?.let { encrypted ->
                values[Keys.IFLYTEK_KEY_CIPHERTEXT] = encrypted.ciphertext
                values[Keys.IFLYTEK_KEY_IV] = encrypted.iv
            }
            apiSecret?.let(secretCipher::encrypt)?.let { encrypted ->
                values[Keys.IFLYTEK_SECRET_CIPHERTEXT] = encrypted.ciphertext
                values[Keys.IFLYTEK_SECRET_IV] = encrypted.iv
            }
            require(hasSecret(values[Keys.IFLYTEK_KEY_CIPHERTEXT], values[Keys.IFLYTEK_KEY_IV])) {
                localizedText("请填写科大讯飞 APIKey", "Enter the iFLYTEK APIKey.")
            }
            require(hasSecret(values[Keys.IFLYTEK_SECRET_CIPHERTEXT], values[Keys.IFLYTEK_SECRET_IV])) {
                localizedText("请填写科大讯飞 APISecret", "Enter the iFLYTEK APISecret.")
            }
        }
    }

    suspend fun setSelectedFormat(format: SpeechApiFormat) {
        val snapshot = settings.first()
        val configured = when (format) {
            SpeechApiFormat.OPENAI_COMPATIBLE -> snapshot.openAi.configured
            SpeechApiFormat.IFLYTEK_IAT -> snapshot.iflytek.configured
        }
        require(configured) { localizedText("请先保存这项语音服务配置", "Save this speech service configuration first.") }
        dataStore.edit { it[Keys.SELECTED_FORMAT] = format.storageValue }
    }

    suspend fun resolve(format: SpeechApiFormat? = null): ResolvedSpeechSettings {
        val values = dataStore.data.first()
        return when (format ?: SpeechApiFormat.fromStorage(values[Keys.SELECTED_FORMAT])) {
            SpeechApiFormat.OPENAI_COMPATIBLE -> ResolvedOpenAiSpeechSettings(
                endpointUrl = validateHttpEndpoint(values[Keys.OPENAI_ENDPOINT] ?: DEFAULT_OPENAI_ENDPOINT),
                model = validateField(values[Keys.OPENAI_MODEL] ?: DEFAULT_OPENAI_MODEL, localizedText("请填写语音模型 ID", "Enter a speech model ID."), 200),
                apiKey = decryptRequired(values[Keys.OPENAI_KEY_CIPHERTEXT], values[Keys.OPENAI_KEY_IV], localizedText("OpenAI 兼容 API 密钥", "OpenAI-compatible API key")),
            )
            SpeechApiFormat.IFLYTEK_IAT -> ResolvedIflytekSpeechSettings(
                endpointUrl = validateWebSocketEndpoint(values[Keys.IFLYTEK_ENDPOINT] ?: DEFAULT_IFLYTEK_ENDPOINT),
                appId = validateField(values[Keys.IFLYTEK_APP_ID].orEmpty(), localizedText("请填写科大讯飞 AppID", "Enter the iFLYTEK AppID."), 200),
                apiKey = decryptRequired(values[Keys.IFLYTEK_KEY_CIPHERTEXT], values[Keys.IFLYTEK_KEY_IV], localizedText("科大讯飞 APIKey", "iFLYTEK APIKey")),
                apiSecret = decryptRequired(values[Keys.IFLYTEK_SECRET_CIPHERTEXT], values[Keys.IFLYTEK_SECRET_IV], localizedText("科大讯飞 APISecret", "iFLYTEK APISecret")),
                language = validateField(values[Keys.IFLYTEK_LANGUAGE] ?: "zh_cn", localizedText("请填写讯飞语种", "Enter the iFLYTEK language."), 100),
                accent = validateField(values[Keys.IFLYTEK_ACCENT] ?: "mandarin", localizedText("请填写讯飞方言参数", "Enter the iFLYTEK dialect parameter."), 100),
            )
        }
    }

    private fun decryptRequired(ciphertext: String?, iv: String?, label: String): String {
        require(hasSecret(ciphertext, iv)) { localizedText("尚未配置$label", "$label is not configured.") }
        return try {
            secretCipher.decrypt(EncryptedSecret(ciphertext!!, iv!!))
        } catch (failure: Exception) {
            throw IllegalStateException(localizedText("无法读取$label，请重新填写", "Could not read $label. Enter it again."), failure)
        }
    }

    private object Keys {
        val SELECTED_FORMAT = stringPreferencesKey("selected_format")
        // 沿用第一版字段名，已有 OpenAI 配置可直接升级。
        val OPENAI_ENDPOINT = stringPreferencesKey("endpoint_url")
        val OPENAI_MODEL = stringPreferencesKey("model")
        val OPENAI_KEY_CIPHERTEXT = stringPreferencesKey("api_key_ciphertext")
        val OPENAI_KEY_IV = stringPreferencesKey("api_key_iv")
        val IFLYTEK_ENDPOINT = stringPreferencesKey("iflytek_endpoint_url")
        val IFLYTEK_APP_ID = stringPreferencesKey("iflytek_app_id")
        val IFLYTEK_KEY_CIPHERTEXT = stringPreferencesKey("iflytek_api_key_ciphertext")
        val IFLYTEK_KEY_IV = stringPreferencesKey("iflytek_api_key_iv")
        val IFLYTEK_SECRET_CIPHERTEXT = stringPreferencesKey("iflytek_api_secret_ciphertext")
        val IFLYTEK_SECRET_IV = stringPreferencesKey("iflytek_api_secret_iv")
        val IFLYTEK_LANGUAGE = stringPreferencesKey("iflytek_language")
        val IFLYTEK_ACCENT = stringPreferencesKey("iflytek_accent")
    }

    private companion object {
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-transcribe"
        const val DEFAULT_IFLYTEK_ENDPOINT = "wss://iat-api.xfyun.cn/v2/iat"
    }
}

private fun hasSecret(ciphertext: String?, iv: String?) = !ciphertext.isNullOrBlank() && !iv.isNullOrBlank()

private fun validateOptionalSecret(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
    require(it.length <= 4096) { localizedText("API 密钥过长", "The API key is too long.") }
}

private fun validateField(value: String, blankMessage: String, maxLength: Int): String = value.trim().also {
    require(it.isNotEmpty()) { blankMessage }
    require(it.length <= maxLength) { localizedText("配置内容过长", "The configuration is too long.") }
}

internal fun validateHttpEndpoint(value: String): String {
    val endpoint = validateField(value, localizedText("请填写完整的语音 API 地址", "Enter the full speech API URL."), 2_000)
    require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
        localizedText("OpenAI 兼容地址必须以 http:// 或 https:// 开头", "The OpenAI-compatible URL must begin with http:// or https://.")
    }
    return endpoint
}

internal fun validateWebSocketEndpoint(value: String): String {
    val endpoint = validateField(value, localizedText("请填写完整的科大讯飞 WebSocket 地址", "Enter the full iFLYTEK WebSocket URL."), 2_000)
    require(endpoint.startsWith("wss://") || endpoint.startsWith("ws://")) {
        localizedText("科大讯飞地址必须以 ws:// 或 wss:// 开头", "The iFLYTEK URL must begin with ws:// or wss://.")
    }
    return endpoint
}
