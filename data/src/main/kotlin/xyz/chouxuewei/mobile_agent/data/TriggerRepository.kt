package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.NotificationMatch
import xyz.chouxuewei.mobile_agent.core.TriggerKind
import xyz.chouxuewei.mobile_agent.core.TriggerRepeat
import xyz.chouxuewei.mobile_agent.core.TriggerSpec

private val Context.triggerDataStore by preferencesDataStore("triggers")

/** 触发器定义与运行状态持久化；一条 JSON 数组存全量，更新走整体重写。 */
class TriggerRepository(context: Context) {
    private val store = context.applicationContext.triggerDataStore
    private val triggersKey = stringPreferencesKey("trigger_specs_json")
    private val json = Json { ignoreUnknownKeys = true }

    val specs: Flow<List<TriggerSpec>> = store.data.map { preferences ->
        preferences[triggersKey]?.let(::decode).orEmpty()
    }.distinctUntilChanged()

    suspend fun saveAll(specs: List<TriggerSpec>) {
        store.edit { it[triggersKey] = encode(specs) }
    }

    /** 单条更新：不存在则追加，返回更新后的完整列表。 */
    suspend fun upsert(spec: TriggerSpec, current: List<TriggerSpec>): List<TriggerSpec> {
        val updated = current.filterNot { it.id == spec.id } + spec
        saveAll(updated)
        return updated
    }

    private fun encode(specs: List<TriggerSpec>): String = buildJsonArray {
        specs.forEach { spec ->
            add(buildJsonObject {
                put("id", spec.id)
                put("name", spec.name)
                put("enabled", spec.enabled)
                put("kind", spec.kind.name)
                spec.scheduleAtEpochMs?.let { put("schedule_at", it) }
                spec.hour?.let { put("hour", it) }
                spec.minute?.let { put("minute", it) }
                put("repeat", spec.repeat.name)
                if (spec.weekdays.isNotEmpty()) putJsonArray("weekdays") { spec.weekdays.forEach { add(it) } }
                spec.notification?.let { match ->
                    put("notification", buildJsonObject {
                        match.packagePattern?.let { put("package", it) }
                        match.titlePattern?.let { put("title", it) }
                        match.textPattern?.let { put("text", it) }
                    })
                }
                put("interval_minutes", spec.intervalMinutes)
                put("instruction", spec.instruction)
                putJsonArray("tool_scope") { spec.toolScope.forEach { add(it) } }
                put("cooldown_minutes", spec.cooldownMinutes)
                put("max_runs_per_day", spec.maxRunsPerDay)
                put("notify_user", spec.notifyUser)
                spec.conversationId?.let { put("conversation_id", it) }
                spec.lastRunAt?.let { put("last_run_at", it) }
                spec.lastStatus?.let { put("last_status", it) }
                put("consecutive_failures", spec.consecutiveFailures)
            })
        }
    }.toString()

    private fun decode(raw: String): List<TriggerSpec> = runCatching {
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        array.mapNotNull { item ->
            runCatching {
                val obj = item.jsonObject
                val match = (obj["notification"] as? kotlinx.serialization.json.JsonObject)?.let { m ->
                    NotificationMatch(
                        packagePattern = m["package"]?.jsonPrimitive?.content,
                        titlePattern = m["title"]?.jsonPrimitive?.content,
                        textPattern = m["text"]?.jsonPrimitive?.content,
                    )
                }
                TriggerSpec(
                    id = obj["id"]!!.jsonPrimitive.content,
                    name = obj["name"]!!.jsonPrimitive.content,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    kind = TriggerKind.valueOf(obj["kind"]!!.jsonPrimitive.content),
                    scheduleAtEpochMs = obj["schedule_at"]?.jsonPrimitive?.longOrNull,
                    hour = obj["hour"]?.jsonPrimitive?.intOrNull,
                    minute = obj["minute"]?.jsonPrimitive?.intOrNull,
                    repeat = obj["repeat"]?.jsonPrimitive?.content
                        ?.let { runCatching { TriggerRepeat.valueOf(it) }.getOrNull() } ?: TriggerRepeat.NONE,
                    weekdays = (obj["weekdays"] as? JsonArray).orEmpty()
                        .mapNotNull { it.jsonPrimitive.intOrNull }.toSet(),
                    notification = match,
                    intervalMinutes = obj["interval_minutes"]?.jsonPrimitive?.intOrNull
                        ?: TriggerSpec.MIN_INTERVAL_MINUTES,
                    instruction = obj["instruction"]!!.jsonPrimitive.content,
                    toolScope = (obj["tool_scope"] as? JsonArray).orEmpty()
                        .mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }.toSet()
                        .ifEmpty { TriggerSpec.DEFAULT_SCOPE },
                    cooldownMinutes = obj["cooldown_minutes"]?.jsonPrimitive?.intOrNull ?: 5,
                    maxRunsPerDay = obj["max_runs_per_day"]?.jsonPrimitive?.intOrNull ?: 12,
                    notifyUser = obj["notify_user"]?.jsonPrimitive?.booleanOrNull ?: true,
                    conversationId = obj["conversation_id"]?.jsonPrimitive?.content,
                    lastRunAt = obj["last_run_at"]?.jsonPrimitive?.longOrNull,
                    lastStatus = obj["last_status"]?.jsonPrimitive?.content,
                    consecutiveFailures = obj["consecutive_failures"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
