package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
                    observationId = event.observationId,
                    candidateId = event.candidateId,
                    confidence = event.confidence,
                    verdict = event.verdict,
                    fallbackReason = event.fallbackReason,
                    latencyMillis = event.latencyMillis,
                    keyGeneration = event.keyGeneration,
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
            observationId = observationId,
            candidateId = candidateId,
            confidence = confidence,
            verdict = verdict,
            fallbackReason = fallbackReason,
            latencyMillis = latencyMillis,
            keyGeneration = keyGeneration,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    companion object {
        const val RECENT_LIMIT = 50
    }
}
