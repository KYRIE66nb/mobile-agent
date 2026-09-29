package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.DecisionBackend
import xyz.chouxuewei.mobile_agent.core.DecisionMode
import xyz.chouxuewei.mobile_agent.core.DecisionProfile
import xyz.chouxuewei.mobile_agent.core.DecisionSettingsSnapshot
import xyz.chouxuewei.mobile_agent.core.localizedText

private val Context.decisionSettingsDataStore by preferencesDataStore("decision_settings")

/**
 * 专用决策后端设置：独立 DataStore 文件，与模型/执行设置完全隔离。
 * API 密钥明文不落盘——只存 Keystore 加密密文+IV 与引用名；
 * keyGeneration 单调递增，撤销/轮换密钥后旧请求可凭审计区分且新解析立即失效。
 */
class DecisionSettingsRepository(context: Context) {

    private val store = context.applicationContext.decisionSettingsDataStore
    private val cipher = SecureSecretCipher("mobile_agent_decision_api_key")

    private object Keys {
        val BACKEND = stringPreferencesKey("decision_backend")
        val MODE = stringPreferencesKey("decision_mode")
        val CONSENT = booleanPreferencesKey("decision_outbound_consent")
        val NAV_ACCEL = booleanPreferencesKey("decision_nav_acceleration")

        fun baseUrl(backend: DecisionBackend) = stringPreferencesKey("decision_${backend.wireName}_base_url")
        fun model(backend: DecisionBackend) = stringPreferencesKey("decision_${backend.wireName}_model")
        fun keyGeneration(backend: DecisionBackend) = intPreferencesKey("decision_${backend.wireName}_key_gen")
        fun keyCiphertext(backend: DecisionBackend) = stringPreferencesKey("decision_${backend.wireName}_key_ct")
        fun keyIv(backend: DecisionBackend) = stringPreferencesKey("decision_${backend.wireName}_key_iv")
    }

    /** 密钥引用名只标识槽位，不含密钥材料。 */
    private fun keyReference(backend: DecisionBackend) = "decision_${backend.wireName}"

    val snapshot: Flow<DecisionSettingsSnapshot> = store.data.map { prefs ->
        DecisionSettingsSnapshot(
            backend = DecisionBackend.parse(prefs[Keys.BACKEND]),
            mode = DecisionMode.parse(prefs[Keys.MODE]),
            outboundConsent = prefs[Keys.CONSENT] ?: false,
            navigationAcceleration = prefs[Keys.NAV_ACCEL] ?: false,
            laya = readProfile(prefs, DecisionBackend.LAYA, defaultModel = "typed-decisions"),
            jev = readProfile(
                prefs, DecisionBackend.JEV,
                defaultBaseUrl = "https://api.typesafe.ai", defaultModel = "jev-latest",
            ),
        )
    }.distinctUntilChanged()

    private fun readProfile(
        prefs: androidx.datastore.preferences.core.Preferences,
        backend: DecisionBackend,
        defaultBaseUrl: String = "",
        defaultModel: String = "",
    ) = DecisionProfile(
        baseUrl = prefs[Keys.baseUrl(backend)] ?: defaultBaseUrl,
        model = prefs[Keys.model(backend)] ?: defaultModel,
        keyReference = if (prefs[Keys.keyCiphertext(backend)] != null) keyReference(backend) else null,
        keyGeneration = prefs[Keys.keyGeneration(backend)] ?: 0,
    )

    suspend fun current(): DecisionSettingsSnapshot = snapshot.first()

    suspend fun setBackend(backend: DecisionBackend) {
        store.edit { it[Keys.BACKEND] = backend.wireName }
    }

    suspend fun setMode(mode: DecisionMode) {
        store.edit { it[Keys.MODE] = mode.wireName }
    }

    suspend fun setOutboundConsent(granted: Boolean) {
        store.edit { it[Keys.CONSENT] = granted }
    }

    suspend fun setNavigationAcceleration(enabled: Boolean) {
        store.edit { it[Keys.NAV_ACCEL] = enabled }
    }

    suspend fun saveProfile(backend: DecisionBackend, baseUrl: String, model: String) {
        require(backend != DecisionBackend.NONE) { "NONE has no profile" }
        store.edit {
            it[Keys.baseUrl(backend)] = baseUrl.trim().removeSuffix("/")
            it[Keys.model(backend)] = model.trim()
        }
    }

    /** 写入或轮换密钥：加密落盘并把代数 +1；同一次 edit 内更新，避免半提交状态。 */
    suspend fun setApiKey(backend: DecisionBackend, apiKey: String) {
        require(backend != DecisionBackend.NONE) { "NONE has no key slot" }
        val encrypted = cipher.encrypt(apiKey.trim())
        store.edit {
            it[Keys.keyCiphertext(backend)] = encrypted.ciphertext
            it[Keys.keyIv(backend)] = encrypted.iv
            it[Keys.keyGeneration(backend)] = (it[Keys.keyGeneration(backend)] ?: 0) + 1
        }
    }

    /** 撤销密钥：清除密文并把代数 +1；之后的解析立即返回 null（fail-safe）。 */
    suspend fun revokeApiKey(backend: DecisionBackend) {
        store.edit {
            it.remove(Keys.keyCiphertext(backend))
            it.remove(Keys.keyIv(backend))
            it[Keys.keyGeneration(backend)] = (it[Keys.keyGeneration(backend)] ?: 0) + 1
        }
    }

    /** 解析当前密钥明文；未配置或解密失败（Keystore 丢失等）返回 null。 */
    suspend fun resolveApiKey(backend: DecisionBackend): String? {
        val prefs = store.data.first()
        val ciphertext = prefs[Keys.keyCiphertext(backend)]?.takeIf(String::isNotBlank)
        val iv = prefs[Keys.keyIv(backend)]?.takeIf(String::isNotBlank)
        if (ciphertext == null || iv == null) return null
        return try {
            cipher.decrypt(EncryptedSecret(ciphertext, iv))
        } catch (e: Exception) {
            null
        }
    }
}
