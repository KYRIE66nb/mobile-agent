package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xyz.chouxuewei.mobile_agent.core.ModelUsageRecord

private val Context.modelUsageDataStore by preferencesDataStore(name = "model_usage")

data class ModelUsageSummary(
    val modelProfileId: String,
    val modelName: String,
    val modelId: String,
    val measuredRequests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val updatedAt: Long,
) {
    val totalTokens: Long get() = inputTokens.saturatedPlus(outputTokens)
}

/**
 * 只累计模型服务明确返回的 usage。没有 usage 的请求不使用本地估算补齐，避免把费用信息伪装成精确值。
 * 模型配置 ID 是归档主键；名称或远端模型 ID 修改后，后续请求会刷新显示信息但保留累计值。
 */
class ModelUsageRepository(context: Context) {
    private val dataStore = context.applicationContext.modelUsageDataStore

    val usage: Flow<List<ModelUsageSummary>> = dataStore.data
        .map { values -> decodeModelUsage(values[Keys.MODEL_USAGE]).sortedByDescending { it.updatedAt } }
        .distinctUntilChanged()

    suspend fun record(record: ModelUsageRecord) {
        if (record.inputTokens == null && record.outputTokens == null) return
        dataStore.edit { values ->
            val updated = decodeModelUsage(values[Keys.MODEL_USAGE]).record(record)
            values[Keys.MODEL_USAGE] = encodeModelUsage(updated)
        }
    }

    private object Keys {
        val MODEL_USAGE = stringPreferencesKey("model_usage_v1")
    }
}

internal fun List<ModelUsageSummary>.record(record: ModelUsageRecord): List<ModelUsageSummary> {
    val profileId = record.modelProfileId
        ?: "model:${record.modelId.ifBlank { record.modelName }}"
    val existing = firstOrNull { it.modelProfileId == profileId }
    val input = record.inputTokens?.toLong()?.coerceAtLeast(0L) ?: 0L
    val output = record.outputTokens?.toLong()?.coerceAtLeast(0L) ?: 0L
    val updated = ModelUsageSummary(
        modelProfileId = profileId,
        modelName = record.modelName.ifBlank { record.modelId.ifBlank { "未命名模型" } },
        modelId = record.modelId,
        measuredRequests = (existing?.measuredRequests ?: 0L).saturatedPlus(1L),
        inputTokens = (existing?.inputTokens ?: 0L).saturatedPlus(input),
        outputTokens = (existing?.outputTokens ?: 0L).saturatedPlus(output),
        updatedAt = record.recordedAt,
    )
    return filterNot { it.modelProfileId == profileId } + updated
}

internal fun encodeModelUsage(items: List<ModelUsageSummary>): String = buildJsonArray {
    items.forEach { item ->
        add(buildJsonObject {
            put("modelProfileId", item.modelProfileId)
            put("modelName", item.modelName)
            put("modelId", item.modelId)
            put("measuredRequests", item.measuredRequests)
            put("inputTokens", item.inputTokens)
            put("outputTokens", item.outputTokens)
            put("updatedAt", item.updatedAt)
        })
    }
}.toString()

internal fun decodeModelUsage(value: String?): List<ModelUsageSummary> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(value).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            val profileId = item["modelProfileId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ModelUsageSummary(
                modelProfileId = profileId,
                modelName = item["modelName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                modelId = item["modelId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                measuredRequests = item["measuredRequests"]?.jsonPrimitive?.longOrNull
                    ?.coerceAtLeast(0L) ?: 0L,
                inputTokens = item["inputTokens"]?.jsonPrimitive?.longOrNull
                    ?.coerceAtLeast(0L) ?: 0L,
                outputTokens = item["outputTokens"]?.jsonPrimitive?.longOrNull
                    ?.coerceAtLeast(0L) ?: 0L,
                updatedAt = item["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }.getOrDefault(emptyList())
}

private fun Long.saturatedPlus(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
