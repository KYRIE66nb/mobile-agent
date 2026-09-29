package xyz.chouxuewei.mobile_agent.core

/**
 * 专用决策结果的本地下限策略：服务只提供"建议"，最终裁决在这里收口。
 * 规则刻意简单且可测：低置信一律不采纳；block 需要比 allow 更高的置信度；
 * 任何失败/非法输出都落到安全的 confirm（交互走原审批、无人值守拒绝）。
 */
object DecisionPolicy {

    /** 采纳 allow/导航候选所需的最小置信度。 */
    const val ADOPT_CONFIDENCE = 0.80

    /** 采纳 block 所需的最小置信度（阻断是更重的断言）。 */
    const val BLOCK_CONFIDENCE = 0.90

    /** 服务拒绝继续（navigation 的 none 选项）对应的保留选项键。 */
    const val OPTION_NONE = "none"

    /** 导航目标已达成对应的保留选项键。 */
    const val OPTION_GOAL_MET = "goal_met"

    data class GateEvaluation(
        val verdict: GateVerdict,
        /** 审计终态：allow/confirm/block/fallback。 */
        val outcome: String,
        val fallbackReason: String? = null,
        val confidence: Double? = null,
    )

    /** 把服务选择映射成 GateVerdict；任何不确定都收拢到 Confirm。 */
    fun evaluateGate(outcome: DecisionOutcome): GateEvaluation = when (outcome) {
        is DecisionOutcome.Failed -> GateEvaluation(
            verdict = GateVerdict.Confirm(failureReason(outcome)),
            outcome = "fallback",
            fallbackReason = outcome.kind.wireName,
        )

        is DecisionOutcome.Accepted -> {
            val c = outcome.choice
            when {
                c.choice == "allow" && confident(c.confidence, ADOPT_CONFIDENCE) ->
                    GateEvaluation(GateVerdict.Allow, outcome = "allow", confidence = c.confidence)

                c.choice == "block" && confident(c.confidence, BLOCK_CONFIDENCE) ->
                    GateEvaluation(
                        GateVerdict.Block(
                            localizedText("专用决策后端判定该动作高风险", "The dedicated decision backend judged this action high-risk")
                        ),
                        outcome = "block",
                        confidence = c.confidence,
                    )

                else -> GateEvaluation(
                    verdict = GateVerdict.Confirm(
                        localizedText(
                            "专用决策后端未能给出可信的放行/拒绝裁决",
                            "The dedicated decision backend did not reach a confident allow/block verdict",
                        )
                    ),
                    outcome = "confirm",
                    fallbackReason = when {
                        c.choice == "allow" || c.choice == "block" -> "low_confidence"
                        else -> "unexpected_option"
                    },
                    confidence = c.confidence,
                )
            }
        }
    }

    sealed interface NavEvaluation {
        /** 采纳候选动作，给出候选 id。 */
        data class Adopt(val candidateId: String, val confidence: Double?) : NavEvaluation
        /** 目标已达成，快路径完成。 */
        object GoalMet : NavEvaluation
        /** 放弃快路径回退规划；reason 写入审计。 */
        data class Fallback(val reason: String) : NavEvaluation
    }

    /**
     * 导航选择评估：只返回本地构造的候选 id，服务不直接产生动作。
     * 选择不在候选集内、低置信或调用失败都回退规划（非阻断——规划才是默认路径）。
     */
    fun evaluateNavigation(
        outcome: DecisionOutcome,
        candidateIds: Set<String>,
    ): NavEvaluation = when (outcome) {
        is DecisionOutcome.Failed -> NavEvaluation.Fallback(outcome.kind.wireName)

        is DecisionOutcome.Accepted -> {
            val c = outcome.choice
            when {
                c.choice == OPTION_GOAL_MET && confident(c.confidence, ADOPT_CONFIDENCE) ->
                    NavEvaluation.GoalMet

                c.choice == OPTION_NONE -> NavEvaluation.Fallback("service_none")

                c.choice in candidateIds && confident(c.confidence, ADOPT_CONFIDENCE) ->
                    NavEvaluation.Adopt(c.choice, c.confidence)

                c.choice in candidateIds -> NavEvaluation.Fallback("low_confidence")
                else -> NavEvaluation.Fallback("invalid_candidate")
            }
        }
    }

    private fun confident(confidence: Double?, threshold: Double): Boolean =
        confidence != null && confidence >= threshold

    private fun failureReason(outcome: DecisionOutcome.Failed): String = localizedText(
        "专用决策后端暂不可用（${outcome.kind.wireName}），请确认后再执行",
        "The dedicated decision backend is unavailable (${outcome.kind.wireName}); confirm before proceeding",
    )
}
