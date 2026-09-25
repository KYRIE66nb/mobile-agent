package xyz.chouxuewei.mobile_agent.core

const val DEFAULT_SINGLE_RUN_MAX_STEPS = 99
const val MIN_SINGLE_RUN_MAX_STEPS = 1
const val MAX_SINGLE_RUN_MAX_STEPS = 999

/**
 * 单轮最大步骤限制连续工具调用轮数。集中校验可以避免设置页、持久化层和运行时采用不同范围。
 */
fun requireValidSingleRunMaxSteps(value: Int): Int {
    require(value in MIN_SINGLE_RUN_MAX_STEPS..MAX_SINGLE_RUN_MAX_STEPS) {
        "单轮最大步骤需要在 $MIN_SINGLE_RUN_MAX_STEPS 到 $MAX_SINGLE_RUN_MAX_STEPS 之间"
    }
    return value
}
