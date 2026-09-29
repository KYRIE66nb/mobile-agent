package xyz.chouxuewei.mobile_agent.core

import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * 用专用决策后端（Laya/Jev）实现的 DecisionGate。
 * 服务只返回裁决建议，最终 GateVerdict 由 DecisionPolicy 在本地下限收口；
 * SHADOW 模式照跑裁决并留痕，但放行与否完全交给既有路径（delegate）。
 * provider 每次调用经 resolver 惰性解析：密钥撤销/配置变更在下一次裁决立即生效。
 * 取消直接向上传播，不落入审计也不被吞掉。
 */
class SystemOneDecisionGate(
    private val backend: DecisionBackend,
    private val providerResolver: suspend () -> DecisionProvider?,
    private val mode: DecisionMode,
    private val keyGeneration: suspend () -> Int,
    private val purpose: DecisionPurpose = DecisionPurpose.SAFETY_GATE,
    /** 既有路径的裁决来源：safetyGateEnabled 时为 LlmDecisionGate，否则 null。 */
    private val delegate: DecisionGate? = null,
    private val audit: suspend (DecisionAuditEvent) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
) : DecisionGate {

    override suspend fun gate(request: GateRequest): GateVerdict {
        val generation = keyGeneration()
        val decisionRequest = safetyGateDecisionRequest(request, requestIds(), generation)
        val startedAt = clock()
        val outcome = try {
            providerResolver()?.choose(decisionRequest)
                ?: DecisionOutcome.Failed(DecisionFailureKind.NOT_CONFIGURED, detail = "provider unavailable")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DecisionOutcome.Failed(DecisionFailureKind.NETWORK, detail = error.message.orEmpty().take(120))
        }
        val evaluated = DecisionPolicy.evaluateGate(outcome)
        audit(
            DecisionAuditEvent(
                backend = backend,
                mode = mode,
                purpose = purpose,
                requestHash = decisionRequest.stableHash(),
                confidence = evaluated.confidence,
                verdict = evaluated.outcome,
                fallbackReason = evaluated.fallbackReason,
                latencyMillis = (clock() - startedAt).coerceAtLeast(0),
                keyGeneration = generation,
                createdAtEpochMillis = clock(),
            )
        )
        return when (mode) {
            // 只记录不改变当前行为：交给既有闸（或它缺席时的常规审批路径）。
            DecisionMode.SHADOW -> delegate?.gate(request) ?: GateVerdict.Allow
            DecisionMode.ENFORCE -> evaluated.verdict
        }
    }
}

/** 请求内容的稳定哈希（SHA-256 前 16 hex），审计不存原文。 */
internal fun DecisionChoiceRequest.stableHash(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(
        (requestId + "|" + purpose.wireName + "|" + instructions + "|" + state.toString() +
            "|" + options.entries.joinToString("|") { "${it.key}=${it.value}" })
            .toByteArray(Charsets.UTF_8)
    )
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
}
