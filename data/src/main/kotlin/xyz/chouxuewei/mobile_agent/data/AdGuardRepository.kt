package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.AdGuardAction
import xyz.chouxuewei.mobile_agent.core.AdGuardDefaults
import xyz.chouxuewei.mobile_agent.core.AdGuardRule

private val Context.adGuardDataStore by preferencesDataStore("ad_guard")

data class PersistedAdGuard(
    val enabled: Boolean,
    val showToast: Boolean,
    val rules: List<AdGuardRule>,
)

/** 广告守卫的开关与规则持久化；拦截事件只留在内存里，不落盘。 */
class AdGuardRepository(context: Context) {
    private val store = context.applicationContext.adGuardDataStore
    private val enabledKey = booleanPreferencesKey("enabled")
    private val showToastKey = booleanPreferencesKey("show_toast")
    private val rulesKey = stringPreferencesKey("rules_json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 未保存过规则时使用内置默认规则；保存过（包括清空）则忠实还原。 */
    val state: Flow<PersistedAdGuard> = store.data.map { preferences ->
        PersistedAdGuard(
            enabled = preferences[enabledKey] ?: false,
            showToast = preferences[showToastKey] ?: true,
            rules = preferences[rulesKey]?.let(::decodeRules) ?: AdGuardDefaults.rules(),
        )
    }.distinctUntilChanged()

    suspend fun save(enabled: Boolean, showToast: Boolean, rules: List<AdGuardRule>) {
        store.edit { preferences ->
            preferences[enabledKey] = enabled
            preferences[showToastKey] = showToast
            preferences[rulesKey] = encodeRules(rules)
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[enabledKey] = enabled }
    }

    private fun encodeRules(rules: List<AdGuardRule>): String = buildJsonArray {
        rules.forEach { rule ->
            add(buildJsonObject {
                put("id", rule.id)
                put("name", rule.name)
                put("enabled", rule.enabled)
                rule.packageScope?.let { put("package_scope", it) }
                put("action", rule.action.name)
                putJsonArray("match_texts") { rule.matchTexts.forEach { add(it) } }
                putJsonArray("context_texts") { rule.contextTexts.forEach { add(it) } }
                rule.classPattern?.let { put("class_pattern", it) }
                put("cooldown_ms", rule.cooldownMs)
            })
        }
    }.toString()

    private fun decodeRules(raw: String): List<AdGuardRule> = runCatching {
        json.parseToJsonElement(raw).let { element ->
            if (element !is kotlinx.serialization.json.JsonArray) return@runCatching emptyList()
            element.mapNotNull { item ->
                runCatching {
                    val obj = item.jsonObject
                    AdGuardRule(
                        id = obj["id"]!!.jsonPrimitive.content,
                        name = obj["name"]!!.jsonPrimitive.content,
                        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        packageScope = obj["package_scope"]?.jsonPrimitive?.content,
                        action = runCatching {
                            AdGuardAction.valueOf(obj["action"]?.jsonPrimitive?.content.orEmpty())
                        }.getOrDefault(AdGuardAction.CLICK_TEXT),
                        matchTexts = obj.stringList("match_texts"),
                        contextTexts = obj.stringList("context_texts"),
                        classPattern = obj["class_pattern"]?.jsonPrimitive?.content,
                        cooldownMs = obj["cooldown_ms"]?.jsonPrimitive?.longOrNull
                            ?: AdGuardRule.DEFAULT_COOLDOWN_MS,
                    )
                }.getOrNull()
            }
        }
    }.getOrDefault(AdGuardDefaults.rules())

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            .orEmpty()
}
