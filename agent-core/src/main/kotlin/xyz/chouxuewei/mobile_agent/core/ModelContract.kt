package xyz.chouxuewei.mobile_agent.core

data class ModelRequest(
    val instruction: String,
    val observation: Observation,
    val recentResults: List<ActionResult>,
)

sealed interface Decision {
    data class Execute(val action: Action) : Decision
    data class Completed(val summary: String) : Decision
    data class NeedsUser(val reason: String) : Decision
}

interface ModelGateway {
    suspend fun decide(request: ModelRequest): Decision
}

/**
 * 模型连接参数保持厂商无关。apiKey 不参与 toString，避免调试日志意外输出密钥。
 */
class ModelConfig(
    val baseUrl: String,
    val model: String?,
    val apiKey: String,
    val reasoningEffortField: String = "reasoning_effort",
) {
    override fun toString(): String =
        "ModelConfig(baseUrl=$baseUrl, model=$model, reasoningEffortField=$reasoningEffortField, apiKey=***)"
}

sealed interface ModelProbeResult {
    data class Connected(
        val model: String,
        val responseSummary: String,
        val screenshotIncluded: Boolean,
    ) : ModelProbeResult

    data class Failed(val reason: String) : ModelProbeResult
}

/** 创建已配置的模型网关，并提供不会触发设备动作的连接测试。 */
interface ModelGatewayFactory {
    fun create(config: ModelConfig): ModelGateway
    suspend fun probe(config: ModelConfig, observation: Observation?): ModelProbeResult
}
