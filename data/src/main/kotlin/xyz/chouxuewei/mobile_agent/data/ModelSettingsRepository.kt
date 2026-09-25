package xyz.chouxuewei.mobile_agent.data

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.chouxuewei.mobile_agent.core.ContextPolicy
import xyz.chouxuewei.mobile_agent.core.DEFAULT_CONTEXT_WINDOW_TOKENS
import xyz.chouxuewei.mobile_agent.core.DEFAULT_MAX_OUTPUT_TOKENS
import xyz.chouxuewei.mobile_agent.core.ModelConfig

private val Context.modelSettingsDataStore by preferencesDataStore(name = "model_settings")

data class ModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val outputReserve: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val reasoningEffortField: String = "reasoning_effort",
    val reasoningEfforts: List<String> = DEFAULT_REASONING_EFFORTS,
    val selectedReasoningEffort: String? = REASONING_EFFORT_OFF,
)

data class ModelSettings(
    val models: List<ModelProfile> = emptyList(),
    val selectedModelId: String? = null,
) {
    val selectedModel: ModelProfile?
        get() = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
}

data class ResolvedModelConfiguration(
    val profileId: String,
    val profileName: String,
    val config: ModelConfig,
    val policy: ContextPolicy,
)

val DEFAULT_REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
/** 内置“关闭”选项对应 OpenAI 兼容协议的 none；null 仍表示不发送该参数。 */
const val REASONING_EFFORT_OFF = "none"
const val MAX_MODEL_PROFILES = 20

/**
 * 多个模型配置统一存入一个 DataStore 快照；每个 API Key 单独加密后再进入 JSON，明文不落盘。
 * 旧版单模型字段只用于无损迁移，写入模型列表后不再参与后续读取。
 */
class ModelSettingsRepository(
    context: Context,
    private val debugDefaults: ModelConfig? = null,
) {
    private val dataStore = context.applicationContext.modelSettingsDataStore
    private val secretCipher = SecureSecretCipher("mobile_agent_model_api_key")

    val settings: Flow<ModelSettings> = dataStore.data.map(::settingsFrom).distinctUntilChanged()

    suspend fun seedDebugDefaults() {
        val snapshot = dataStore.data.first()
        if (snapshot[Keys.MODEL_PROFILES] != null) return
        val legacy = legacyProfile(snapshot)
        val defaults = debugDefaults
        val debugProfile = if (legacy == null && defaults != null && defaults.baseUrl.isNotBlank()) {
            val encrypted = defaults.apiKey.takeIf(String::isNotBlank)?.let(secretCipher::encrypt)
            StoredModelProfile(
                id = DEFAULT_PROFILE_ID,
                name = defaults.model?.takeIf(String::isNotBlank)?.trim() ?: localizedText("默认模型", "Default model"),
                baseUrl = normalizeBaseUrl(defaults.baseUrl),
                model = defaults.model?.trim().orEmpty(),
                apiKeyCiphertext = encrypted?.ciphertext,
                apiKeyIv = encrypted?.iv,
                contextWindow = DEFAULT_CONTEXT_WINDOW_TOKENS,
                outputReserve = DEFAULT_MAX_OUTPUT_TOKENS,
                reasoningEffortField = defaults.reasoningEffortField,
                reasoningEfforts = DEFAULT_REASONING_EFFORTS,
                selectedReasoningEffort = REASONING_EFFORT_OFF,
            )
        } else null
        val initial = legacy ?: debugProfile ?: return
        dataStore.edit { values ->
            if (values[Keys.MODEL_PROFILES] == null) {
                values[Keys.MODEL_PROFILES] = encodeProfiles(listOf(initial))
                values[Keys.SELECTED_MODEL_ID] = initial.id
            }
        }
    }

    /** 保存一个模型配置；profileId 为空时创建新配置并将其设为当前模型。 */
    suspend fun saveModel(
        profileId: String?,
        name: String,
        baseUrl: String,
        model: String,
        newApiKey: String?,
        contextPolicy: ContextPolicy,
        reasoningEffortField: String,
        reasoningEfforts: List<String>,
    ): String {
        contextPolicy.validate()
        val id = profileId ?: UUID.randomUUID().toString()
        val normalizedName = name.trim()
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        val normalizedModel = model.trim()
        val normalizedReasoningField = reasoningEffortField.trim()
        val normalizedEfforts = normalizeReasoningEfforts(reasoningEfforts)
        require(normalizedName.isNotEmpty()) { localizedText("请填写配置名称", "Enter a configuration name.") }
        require(normalizedName.length <= 80) { localizedText("配置名称过长", "The configuration name is too long.") }
        require(normalizedModel.isNotEmpty()) { localizedText("请填写模型 ID", "Enter a model ID.") }
        require(normalizedModel.length <= 200) { localizedText("模型 ID 过长", "The model ID is too long.") }
        require(newApiKey == null || newApiKey.length <= 4_096) { localizedText("API 密钥过长", "The API key is too long.") }
        require(normalizedReasoningField.isEmpty() ||
            normalizedReasoningField.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,99}"))) {
            localizedText("思考强度字段只能包含字母、数字、下划线、点和短横线", "The reasoning effort field may contain only letters, numbers, underscores, periods, and hyphens.")
        }
        require(normalizedEfforts.all {
            it.length <= 40 && it.matches(Regex("[A-Za-z0-9_.-]+"))
        }) { localizedText("思考强度只能包含字母、数字、下划线、点和短横线", "Reasoning effort may contain only letters, numbers, underscores, periods, and hyphens.") }
        val encrypted = newApiKey?.takeIf(String::isNotBlank)?.let(secretCipher::encrypt)
        dataStore.edit { values ->
            val profiles = storedProfiles(values).toMutableList()
            val index = profiles.indexOfFirst { it.id == id }
            val existing = profiles.getOrNull(index)
            require(profiles.none { it.id != id && it.name.equals(normalizedName, ignoreCase = true) }) {
                localizedText("已经存在同名模型配置", "A model configuration with this name already exists.")
            }
            require(existing != null || profiles.size < MAX_MODEL_PROFILES) {
                localizedText("最多保存 $MAX_MODEL_PROFILES 个模型配置", "You can save up to $MAX_MODEL_PROFILES model configurations.")
            }
            val ciphertext = encrypted?.ciphertext ?: existing?.apiKeyCiphertext
            val iv = encrypted?.iv ?: existing?.apiKeyIv
            require(!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) { localizedText("请填写 API 密钥", "Enter an API key.") }
            val selectedEffort = when {
                existing == null -> REASONING_EFFORT_OFF
                existing.selectedReasoningEffort == null -> null
                existing.selectedReasoningEffort.equals(REASONING_EFFORT_OFF, ignoreCase = true) ->
                    REASONING_EFFORT_OFF
                existing.selectedReasoningEffort in normalizedEfforts -> existing.selectedReasoningEffort
                else -> REASONING_EFFORT_OFF
            }
            val updated = StoredModelProfile(
                id = id,
                name = normalizedName,
                baseUrl = normalizedUrl,
                model = normalizedModel,
                apiKeyCiphertext = ciphertext,
                apiKeyIv = iv,
                contextWindow = contextPolicy.windowTokens,
                outputReserve = contextPolicy.outputReserve,
                reasoningEffortField = normalizedReasoningField,
                reasoningEfforts = normalizedEfforts,
                selectedReasoningEffort = selectedEffort,
            )
            if (index >= 0) profiles[index] = updated else profiles += updated
            values[Keys.MODEL_PROFILES] = encodeProfiles(profiles)
            val selected = values[Keys.SELECTED_MODEL_ID]
            if (profileId == null || profiles.none { it.id == selected }) values[Keys.SELECTED_MODEL_ID] = id
        }
        return id
    }

    suspend fun deleteModel(id: String) {
        dataStore.edit { values ->
            val profiles = storedProfiles(values)
            require(profiles.any { it.id == id }) { localizedText("找不到要删除的模型配置", "The model configuration to delete was not found.") }
            val remaining = profiles.filterNot { it.id == id }
            values[Keys.MODEL_PROFILES] = encodeProfiles(remaining)
            if (values[Keys.SELECTED_MODEL_ID] == id) {
                val replacement = remaining.firstOrNull()
                if (replacement == null) values.remove(Keys.SELECTED_MODEL_ID)
                else values[Keys.SELECTED_MODEL_ID] = replacement.id
            }
        }
    }

    suspend fun setSelectedModel(id: String) {
        dataStore.edit { values ->
            require(storedProfiles(values).any { it.id == id }) { localizedText("所选模型已不存在", "The selected model no longer exists.") }
            values[Keys.SELECTED_MODEL_ID] = id
        }
    }

    /** 思考强度随模型配置保存，切换回来时恢复该模型上次使用的等级。 */
    suspend fun setSelectedReasoningEffort(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)?.let {
            if (it.equals(REASONING_EFFORT_OFF, ignoreCase = true)) REASONING_EFFORT_OFF else it
        }
        dataStore.edit { values ->
            val profiles = storedProfiles(values).toMutableList()
            val selectedId = selectedProfileId(values, profiles)
            val index = profiles.indexOfFirst { it.id == selectedId }
            require(index >= 0) { localizedText("请先配置模型", "Configure a model first.") }
            val profile = profiles[index]
            require(
                normalized == null ||
                    normalized == REASONING_EFFORT_OFF ||
                    normalized in profile.reasoningEfforts,
            ) { localizedText("所选思考强度已不可用", "The selected reasoning effort is no longer available.") }
            profiles[index] = profile.copy(selectedReasoningEffort = normalized)
            values[Keys.MODEL_PROFILES] = encodeProfiles(profiles)
        }
    }

    /** 从旧版会话字段迁移一次；当前模型已有选择时绝不覆盖。 */
    suspend fun migrateLegacyReasoningEffort(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return
        dataStore.edit { values ->
            val profiles = storedProfiles(values).toMutableList()
            val selectedId = selectedProfileId(values, profiles)
            val index = profiles.indexOfFirst { it.id == selectedId }
            if (index >= 0) {
                val profile = profiles[index]
                if (profile.selectedReasoningEffort == null && normalized in profile.reasoningEfforts) {
                    profiles[index] = profile.copy(selectedReasoningEffort = normalized)
                    values[Keys.MODEL_PROFILES] = encodeProfiles(profiles)
                }
            }
        }
    }

    /** 兼容现有调用：预算更新作用于当前模型。 */
    suspend fun saveContextPolicy(window: Int, outputReserve: Int) {
        val policy = ContextPolicy(window, outputReserve).also { it.validate() }
        seedDebugDefaults()
        dataStore.edit { values ->
            val profiles = storedProfiles(values).toMutableList()
            val selectedId = selectedProfileId(values, profiles)
            val index = profiles.indexOfFirst { it.id == selectedId }
            require(index >= 0) { localizedText("请先配置模型", "Configure a model first.") }
            profiles[index] = profiles[index].copy(
                contextWindow = policy.windowTokens,
                outputReserve = policy.outputReserve,
            )
            values[Keys.MODEL_PROFILES] = encodeProfiles(profiles)
        }
    }

    suspend fun contextPolicy(): ContextPolicy {
        seedDebugDefaults()
        val values = dataStore.data.first()
        val profiles = storedProfiles(values)
        val profile = profiles.firstOrNull { it.id == selectedProfileId(values, profiles) }
        return (profile?.policy() ?: ContextPolicy()).also { it.validate() }
    }

    suspend fun loadConfig(): ModelConfig {
        seedDebugDefaults()
        val values = dataStore.data.first()
        return configFrom(selectedProfile(values))
    }

    /** 一轮请求从同一个 DataStore 快照读取当前模型及其预算，切换不会影响已发出的请求。 */
    suspend fun loadChatConfiguration(modelProfileId: String? = null): Pair<ModelConfig, ContextPolicy> {
        val resolved = resolveChatConfiguration(modelProfileId)
        return resolved.config to resolved.policy
    }

    /** 同时返回稳定的模型配置 ID 和显示名称，供真实用量按模型归档。 */
    suspend fun resolveChatConfiguration(modelProfileId: String? = null): ResolvedModelConfiguration {
        seedDebugDefaults()
        val values = dataStore.data.first()
        val profile = if (modelProfileId == null) {
            selectedProfile(values)
        } else {
            storedProfiles(values).firstOrNull { it.id == modelProfileId }
                ?: error(localizedText("发送时选择的模型已不存在，请重新选择后发送", "The selected model no longer exists. Select another model before sending."))
        }
        return ResolvedModelConfiguration(
            profileId = profile.id,
            profileName = profile.name,
            config = configFrom(profile),
            policy = profile.policy().also { it.validate() },
        )
    }

    private fun settingsFrom(values: Preferences): ModelSettings {
        val stored = storedProfiles(values)
        val selectedId = selectedProfileId(values, stored)
        return ModelSettings(stored.map(StoredModelProfile::asPublic), selectedId)
    }

    private fun selectedProfile(values: Preferences): StoredModelProfile {
        val profiles = storedProfiles(values)
        val selectedId = selectedProfileId(values, profiles)
        return profiles.firstOrNull { it.id == selectedId } ?: error(localizedText("尚未配置模型", "No model is configured."))
    }

    private fun selectedProfileId(values: Preferences, profiles: List<StoredModelProfile>): String? {
        val selected = values[Keys.SELECTED_MODEL_ID]
        return selected?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id
    }

    private fun configFrom(profile: StoredModelProfile): ModelConfig {
        val ciphertext = profile.apiKeyCiphertext?.takeIf(String::isNotBlank)
            ?: error(localizedText("“${profile.name}”尚未配置 API 密钥", "No API key is configured for “${profile.name}”."))
        val iv = profile.apiKeyIv?.takeIf(String::isNotBlank)
            ?: error(localizedText("“${profile.name}”保存的 API 密钥不完整，请重新填写", "The saved API key for “${profile.name}” is incomplete. Enter it again."))
        val apiKey = try {
            secretCipher.decrypt(EncryptedSecret(ciphertext, iv))
        } catch (error: Exception) {
            throw IllegalStateException(localizedText("无法读取“${profile.name}”的 API 密钥，请重新填写", "Could not read the API key for “${profile.name}”. Enter it again."), error)
        }
        return ModelConfig(
            baseUrl = profile.baseUrl,
            model = profile.model,
            apiKey = apiKey,
            reasoningEffortField = profile.reasoningEffortField,
        )
    }

    private fun storedProfiles(values: Preferences): List<StoredModelProfile> {
        values[Keys.MODEL_PROFILES]?.let { encoded -> return decodeProfiles(encoded) }
        return listOfNotNull(legacyProfile(values))
    }

    private fun legacyProfile(values: Preferences): StoredModelProfile? {
        val baseUrl = values[Keys.LEGACY_BASE_URL]?.takeIf(String::isNotBlank) ?: return null
        val model = values[Keys.LEGACY_MODEL].orEmpty()
        val efforts = decodeReasoningEfforts(values[Keys.LEGACY_REASONING_EFFORTS])
        return StoredModelProfile(
            id = DEFAULT_PROFILE_ID,
            name = model.ifBlank { localizedText("默认模型", "Default model") },
            baseUrl = baseUrl,
            model = model,
            apiKeyCiphertext = values[Keys.LEGACY_API_KEY_CIPHERTEXT],
            apiKeyIv = values[Keys.LEGACY_API_KEY_IV],
            contextWindow = values[Keys.LEGACY_CONTEXT_WINDOW] ?: DEFAULT_CONTEXT_WINDOW_TOKENS,
            outputReserve = values[Keys.LEGACY_OUTPUT_RESERVE] ?: DEFAULT_MAX_OUTPUT_TOKENS,
            reasoningEffortField = values[Keys.LEGACY_REASONING_EFFORT_FIELD] ?: "reasoning_effort",
            reasoningEfforts = efforts,
            selectedReasoningEffort = values[Keys.LEGACY_SELECTED_REASONING_EFFORT]
                ?.takeIf { it in efforts }
                ?: REASONING_EFFORT_OFF,
        )
    }

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            localizedText("服务地址必须以 http:// 或 https:// 开头", "The service URL must begin with http:// or https://.")
        }
        require(normalized.length <= 2_000) { localizedText("服务地址过长", "The service URL is too long.") }
        return normalized
    }

    private object Keys {
        val MODEL_PROFILES = stringPreferencesKey("model_profiles_v2")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")

        val LEGACY_CONTEXT_WINDOW = intPreferencesKey("context_window")
        val LEGACY_OUTPUT_RESERVE = intPreferencesKey("output_reserve")
        val LEGACY_BASE_URL = stringPreferencesKey("model_base_url")
        val LEGACY_MODEL = stringPreferencesKey("model_id")
        val LEGACY_API_KEY_CIPHERTEXT = stringPreferencesKey("model_api_key_ciphertext")
        val LEGACY_API_KEY_IV = stringPreferencesKey("model_api_key_iv")
        val LEGACY_REASONING_EFFORT_FIELD = stringPreferencesKey("reasoning_effort_field")
        val LEGACY_REASONING_EFFORTS = stringPreferencesKey("reasoning_efforts")
        val LEGACY_SELECTED_REASONING_EFFORT = stringPreferencesKey("selected_reasoning_effort")
    }

    private companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}

private data class StoredModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyCiphertext: String?,
    val apiKeyIv: String?,
    val contextWindow: Int,
    val outputReserve: Int,
    val reasoningEffortField: String,
    val reasoningEfforts: List<String>,
    val selectedReasoningEffort: String?,
) {
    fun asPublic() = ModelProfile(
        id = id,
        name = name,
        baseUrl = baseUrl,
        model = model,
        hasApiKey = !apiKeyCiphertext.isNullOrBlank() && !apiKeyIv.isNullOrBlank(),
        contextWindow = contextWindow,
        outputReserve = outputReserve,
        reasoningEffortField = reasoningEffortField,
        reasoningEfforts = reasoningEfforts,
        selectedReasoningEffort = selectedReasoningEffort,
    )

    fun policy() = ContextPolicy(contextWindow, outputReserve)
}

private fun encodeProfiles(profiles: List<StoredModelProfile>): String = buildJsonArray {
    profiles.forEach { profile ->
        add(buildJsonObject {
            put("id", profile.id)
            put("name", profile.name)
            put("baseUrl", profile.baseUrl)
            put("model", profile.model)
            profile.apiKeyCiphertext?.let { put("apiKeyCiphertext", it) }
            profile.apiKeyIv?.let { put("apiKeyIv", it) }
            put("contextWindow", profile.contextWindow)
            put("outputReserve", profile.outputReserve)
            put("reasoningEffortField", profile.reasoningEffortField)
            put("reasoningEfforts", buildJsonArray {
                profile.reasoningEfforts.forEach { effort -> add(JsonPrimitive(effort)) }
            })
            // JsonNull 表示用户明确选择“不指定”；缺少字段则是旧数据，读取时迁移为“关闭”。
            put(
                "selectedReasoningEffort",
                profile.selectedReasoningEffort?.let(::JsonPrimitive) ?: JsonNull,
            )
        })
    }
}.toString()

private fun decodeProfiles(value: String): List<StoredModelProfile> = runCatching {
    Json.parseToJsonElement(value).jsonArray.map { element ->
        val item = element.jsonObject
        StoredModelProfile(
            id = item.getValue("id").jsonPrimitive.content,
            name = item.getValue("name").jsonPrimitive.content,
            baseUrl = item.getValue("baseUrl").jsonPrimitive.content,
            model = item.getValue("model").jsonPrimitive.content,
            apiKeyCiphertext = item["apiKeyCiphertext"]?.jsonPrimitive?.contentOrNull,
            apiKeyIv = item["apiKeyIv"]?.jsonPrimitive?.contentOrNull,
            contextWindow = item["contextWindow"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONTEXT_WINDOW_TOKENS,
            outputReserve = item["outputReserve"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_OUTPUT_TOKENS,
            reasoningEffortField = item["reasoningEffortField"]?.jsonPrimitive?.contentOrNull ?: "reasoning_effort",
            reasoningEfforts = normalizeReasoningEfforts(
                item["reasoningEfforts"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: DEFAULT_REASONING_EFFORTS,
            ),
            selectedReasoningEffort = when (val selected = item["selectedReasoningEffort"]) {
                null -> REASONING_EFFORT_OFF
                JsonNull -> null
                else -> selected.jsonPrimitive.contentOrNull
            },
        )
    }
}.getOrDefault(emptyList())

private fun decodeReasoningEfforts(value: String?): List<String> =
    if (value == null) DEFAULT_REASONING_EFFORTS
    else normalizeReasoningEfforts(value.split(','))

private fun normalizeReasoningEfforts(values: List<String>): List<String> =
    values.map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot { it.equals(REASONING_EFFORT_OFF, ignoreCase = true) }
        .distinct()
