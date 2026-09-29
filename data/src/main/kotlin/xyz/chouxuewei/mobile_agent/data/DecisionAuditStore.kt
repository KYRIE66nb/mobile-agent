package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.DecisionAuditEvent

/**
 * 专用决策后端的审计存储：只落裁决元数据与请求哈希（不落请求原文、密钥或截图内容）。
 * 记录只增不改，供设置页回放核对；`prune` 用于按时间清理。
 */
class DecisionAuditStore internal constructor(private val dao: DecisionAuditDao) {
    constructor(context: Context) : this(DatabaseProvider.get(context).decisionAudits())

    suspend fun record(event: DecisionAuditEvent) {
        AgentLog.d("DecisionAudit") {
            "backend=${event.backend.wireName} mode=${event.mode.wireName} " +
                "purpose=${event.purpose.wireName} verdict=${event.verdict} " +
                "fallback=${event.fallbackReason} gen=${event.keyGeneration} " +
                "latency=${event.latencyMillis}ms hash=${event.requestHash}"
        }
        runCatching {
            dao.insert(
                DecisionRecordEntity(
                    backend = event.backend.name,
                    mode = event.mode.name,
                    purpose = event.purpose.name,
                    requestHash = event.requestHash,
                    requestId = event.requestId,
                    runId = event.runId,
                    toolCallId = event.toolCallId,
                    observationId = event.observationId,
                    candidateId = event.candidateId,
                    confidence = event.confidence,
                    probabilities = probabilitiesJson.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        JsonObject(event.probabilities.mapValues { (_, v) ->
                            kotlinx.serialization.json.JsonPrimitive(v)
                        }),
                    ),
                    requestedModel = event.requestedModel,
                    returnedModel = event.returnedModel,
                    usageInputTokens = event.usageInputTokens,
                    usageOutputTokens = event.usageOutputTokens,
                    verdict = event.verdict,
                    fallbackReason = event.fallbackReason,
                    latencyMillis = event.latencyMillis,
                    keyGeneration = event.keyGeneration,
                    schemaVersion = event.schemaVersion,
                    policyVersion = event.policyVersion,
                    createdAtEpochMillis = event.createdAtEpochMillis,
                ),
            )
        }
    }

    suspend fun recent(limit: Int = RECENT_LIMIT): List<DecisionAuditEvent> =
        dao.recent(limit).map { it.toEvent() }

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<DecisionAuditEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toEvent() } }

    suspend fun prune(olderThanEpochMillis: Long): Int = dao.deleteBefore(olderThanEpochMillis)

    private fun DecisionRecordEntity.toEvent(): DecisionAuditEvent =
        DecisionAuditEvent(
            backend = xyz.chouxuewei.mobile_agent.core.DecisionBackend.entries
                .firstOrNull { it.name == backend } ?: xyz.chouxuewei.mobile_agent.core.DecisionBackend.NONE,
            mode = xyz.chouxuewei.mobile_agent.core.DecisionMode.entries
                .firstOrNull { it.name == mode } ?: xyz.chouxuewei.mobile_agent.core.DecisionMode.SHADOW,
            purpose = xyz.chouxuewei.mobile_agent.core.DecisionPurpose.entries
                .firstOrNull { it.name == purpose } ?: xyz.chouxuewei.mobile_agent.core.DecisionPurpose.SAFETY_GATE,
            requestHash = requestHash,
            requestId = requestId,
            runId = runId,
            toolCallId = toolCallId,
            observationId = observationId,
            candidateId = candidateId,
            confidence = confidence,
            probabilities = decodeProbabilities(probabilities),
            requestedModel = requestedModel,
            returnedModel = returnedModel,
            usageInputTokens = usageInputTokens,
            usageOutputTokens = usageOutputTokens,
            verdict = verdict,
            fallbackReason = fallbackReason,
            latencyMillis = latencyMillis,
            keyGeneration = keyGeneration,
            schemaVersion = schemaVersion,
            policyVersion = policyVersion,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    private fun decodeProbabilities(raw: String): Map<String, Double> =
        runCatching {
            (Json.parseToJsonElement(raw) as? JsonObject)
                ?.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }
                ?.toMap()
        }.getOrNull() ?: emptyMap()

    companion object {
        const val RECENT_LIMIT = 50
        private val probabilitiesJson = Json
    }
}
