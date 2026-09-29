package xyz.chouxuewei.mobile_agent.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * 低风险导航快路径：observe 的瞬态观察 → 本地构造候选 → 专用后端在候选间选择 →
 * 走调用方注入的统一执行通道（与模型发起的工具调用共用权限/审批/观察校验/历史记录）。
 * 服务只返回候选 id 或保留选项；任何低置信、非法、失败、无进展都回退给主规划器。
 * SHADOW 模式只记录一次裁决然后立即回退，不执行任何动作。
 */
/** ChatRuntime 与 app 层之间的快路径能力包：快照读取、provider 惰性解析、审计汇聚。 */
class FastPathSupport(
    val settings: suspend () -> DecisionSettingsSnapshot,
    val resolveProvider: suspend (DecisionBackend) -> DecisionProvider?,
    val audit: suspend (DecisionAuditEvent) -> Unit = {},
)

class FastPathController(
    private val providerResolver: suspend () -> DecisionProvider?,
    private val backend: DecisionBackend,
    private val mode: DecisionMode,
    private val keyGeneration: Int,
    private val audit: suspend (DecisionAuditEvent) -> Unit = {},
    private val maxSteps: Int = 8,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Result {
        /** 服务判定目标已达成。 */
        data object Completed : Result
        /** 回退主规划器；reason 供审计与诊断。 */
        data class Fallback(val reason: String) : Result
    }

    /**
     * @param goal 导航目标（来自 device_observe 的 navigation_goal）
     * @param sessionId 当前设备会话
     * @param initialObservation 触发快路径那次 observe 的瞬态观察
     * @param execute 统一执行通道：ChatRuntime 注入 executeToolCall；返回 ToolResult
     */
    suspend fun run(
        goal: String,
        sessionId: String,
        userRequest: String,
        initialObservation: JsonObject,
        runId: String? = null,
        execute: suspend (toolId: String, args: JsonObject) -> ToolResult,
    ): Result {
        val constraints = userConstraints(userRequest)
        // 用户约束与快路径动作面冲突时本地直接回退，连选择请求都不发。
        if (!constraintsPermitAutoAction(constraints)) return Result.Fallback("user_constraint")
        var observation = ObservationLiteCodec.parse(initialObservation)
            ?: return Result.Fallback("invalid_observation")
        var lastCandidateId: String? = null
        var lastRevision = observation.revision
        var observeRetries = 1

        repeat(maxSteps) { step ->
            coroutineContext.ensureActive()
            val candidates = ActionCandidateBuilder.build(observation)
            if (candidates.isEmpty()) return Result.Fallback("no_candidates")
            val request = navigationDecisionRequest(
                goal, observation, candidates, constraints, requestIds(), keyGeneration,
            )
            val startedAt = clock()
            val outcome = try {
                providerResolver()?.choose(request)
                    ?: DecisionOutcome.Failed(DecisionFailureKind.NOT_CONFIGURED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DecisionOutcome.Failed(DecisionFailureKind.NETWORK, detail = error.message.orEmpty().take(120))
            }
            val evaluated = DecisionPolicy.evaluateNavigation(outcome, candidates.map { it.id }.toSet())
            val accepted = (outcome as? DecisionOutcome.Accepted)?.choice
            audit(
                DecisionAuditEvent(
                    backend = backend,
                    mode = mode,
                    purpose = DecisionPurpose.NAVIGATION,
                    requestHash = request.stableHash(),
                    requestId = request.requestId,
                    runId = runId,
                    observationId = observation.observationId,
                    candidateId = (evaluated as? DecisionPolicy.NavEvaluation.Adopt)?.candidateId,
                    confidence = accepted?.confidence,
                    probabilities = accepted?.probabilities ?: emptyMap(),
                    requestedModel = accepted?.requestModel,
                    returnedModel = accepted?.modelEcho,
                    usageInputTokens = accepted?.usageInputTokens,
                    usageOutputTokens = accepted?.usageOutputTokens,
                    verdict = when (evaluated) {
                        is DecisionPolicy.NavEvaluation.Adopt -> "adopt"
                        is DecisionPolicy.NavEvaluation.GoalMet -> "goal_met"
                        is DecisionPolicy.NavEvaluation.Fallback -> "fallback"
                    },
                    fallbackReason = (evaluated as? DecisionPolicy.NavEvaluation.Fallback)?.reason,
                    latencyMillis = (clock() - startedAt).coerceAtLeast(0),
                    keyGeneration = keyGeneration,
                    createdAtEpochMillis = clock(),
                )
            )
            when (evaluated) {
                is DecisionPolicy.NavEvaluation.GoalMet -> return Result.Completed
                is DecisionPolicy.NavEvaluation.Fallback -> return Result.Fallback(evaluated.reason)
                is DecisionPolicy.NavEvaluation.Adopt -> {
                    // SHADOW 只留痕不执行；执行走统一通道（审批/观察校验/记录不变）。
                    if (mode == DecisionMode.SHADOW) return Result.Fallback("shadow_mode")
                    val candidate = candidates.first { it.id == evaluated.candidateId }
                    // 同一观察下重复选同一候选 = 无进展，回退。
                    if (candidate.id == lastCandidateId && observation.revision == lastRevision) {
                        return Result.Fallback("no_progress")
                    }
                    val args = buildJsonObject {
                        put("session_id", sessionId)
                        candidate.arguments.forEach { (k, v) -> put(k, v) }
                    }
                    val result = execute("device_action", args)
                    if (result.isError) {
                        // 观察过期/失配：先重识别一次再执行；其它失败直接回退。
                        val stale = result.content.contains("observation", ignoreCase = true) ||
                            result.content.contains("识别")
                        if (stale && observeRetries > 0) {
                            observeRetries--
                            val refreshed = execute("device_observe", buildJsonObject {
                                put("session_id", sessionId)
                                put("limit", DecisionLimits.MAX_NAV_NODES)
                            })
                            val parsed = refreshed.ephemeral?.let(ObservationLiteCodec::parse)
                            if (refreshed.isError || parsed == null) return Result.Fallback("refresh_failed")
                            observation = parsed
                            return@repeat
                        }
                        return Result.Fallback("execute_failed")
                    }
                    lastCandidateId = candidate.id
                    lastRevision = observation.revision
                    // 执行成功后该观察已失效：重新识别以验证推进效果。
                    val refreshed = execute("device_observe", buildJsonObject {
                        put("session_id", sessionId)
                        put("limit", DecisionLimits.MAX_NAV_NODES)
                    })
                    val parsed = refreshed.ephemeral?.let(ObservationLiteCodec::parse)
                    if (refreshed.isError || parsed == null) return Result.Fallback("refresh_failed")
                    // 前台包名意外变化 = 屏幕切换出导航预期，交回规划器。
                    val expected = candidate.expectedPackage
                    if (expected != null && parsed.foregroundPackage != null &&
                        parsed.foregroundPackage != expected
                    ) {
                        return Result.Fallback("screen_switched")
                    }
                    observation = parsed
                }
            }
        }
        return Result.Fallback("max_steps")
    }
}
