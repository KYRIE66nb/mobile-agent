package xyz.chouxuewei.mobile_agent.prototype

import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.DecisionAuditEvent

/**
 * 决策审计事件的记录入口。
 * Task 7 将其替换为 Room 持久化；当前保留最近 50 条内存环形缓冲，
 * 供设置页的"最近决策记录"预览与测试连接结果展示，不落盘、不含敏感原文。
 */
class DecisionAuditRecorder {

    private val buffer = java.util.ArrayDeque<DecisionAuditEvent>()

    suspend fun record(event: DecisionAuditEvent) {
        synchronized(buffer) {
            while (buffer.size >= 50) buffer.removeFirst()
            buffer.addLast(event)
        }
        AgentLog.d("DecisionAudit") {
            "backend=${event.backend.wireName} mode=${event.mode.wireName} " +
                "purpose=${event.purpose.wireName} verdict=${event.verdict} " +
                "fallback=${event.fallbackReason} gen=${event.keyGeneration} " +
                "latency=${event.latencyMillis}ms hash=${event.requestHash}"
        }
    }

    fun recent(limit: Int = 50): List<DecisionAuditEvent> =
        synchronized(buffer) { buffer.toList() }.takeLast(limit)
}
