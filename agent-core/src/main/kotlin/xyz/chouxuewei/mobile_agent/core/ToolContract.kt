package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.Flow

/** 用户按能力组选择授权方式；默认启用，并在每次实际调用前请求批准。 */
enum class ToolPermissionMode { REQUEST_APPROVAL, FULL_ACCESS }

data class ToolAccess(
    val enabled: Boolean = true,
    val permission: ToolPermissionMode = ToolPermissionMode.REQUEST_APPROVAL,
)

enum class ToolSideEffect { READ, LOCAL_WRITE, EXTERNAL_WRITE, DESTRUCTIVE }

/** SINGLE_MODEL_STEP 的结果只允许模型读取一次，随后由运行时替换为失效占位符。 */
enum class ToolResultLifetime { PERSISTENT, SINGLE_MODEL_STEP }

enum class ToolCallStatus {
    RECEIVED,
    WAITING_APPROVAL,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    DENIED,
    CANCELLED,
    INTERRUPTED,
}

enum class ToolAvailabilityState { AVAILABLE, NEEDS_PERMISSION, NEEDS_SETUP, DISABLED, UNSUPPORTED, DEGRADED }

data class ToolAvailability(
    val state: ToolAvailabilityState,
    val detail: String = "",
)

data class ToolCapability(
    val id: String,
    val title: String,
    val description: String,
    val toolCount: Int,
    val availability: ToolAvailability,
    /** false 表示工具本身就是向用户提问，不再套一层“是否允许提问”的授权。 */
    val supportsPermissionControl: Boolean = true,
)

/** inputSchema 使用 JSON Schema 字符串，避免核心层依赖某个 JSON 实现。 */
data class ToolDefinition(
    val id: String,
    val title: String,
    val description: String,
    val inputSchema: String,
    val sideEffect: ToolSideEffect,
    val providerId: String,
    /** 非空时由用户在实际调用前选择；即使能力设为完全授权也不能由模型代选。 */
    val userChoices: List<ToolInvocationChoice> = emptyList(),
    /** 授权弹窗使用的简短说明；模型仍使用 description 获取完整调用规则。 */
    val approvalDescription: String = title,
    val resultLifetime: ToolResultLifetime = ToolResultLifetime.PERSISTENT,
    /** 关闭后仍受能力开关控制，但不会进入通用工具授权流程。 */
    val requiresPermissionApproval: Boolean = true,
)

data class ToolInvocationChoice(
    val id: String,
    val title: String,
    val description: String,
    val argumentsJson: String,
    val requiresOverlayPermission: Boolean = false,
    /** 不阻止执行，但建议开启悬浮窗，以便离开 App 后仍能查看和停止当前操作。 */
    val recommendsOverlayPermission: Boolean = false,
)

data class RequestedToolCall(
    val id: String,
    val toolId: String,
    val argumentsJson: String,
)

data class ToolCallRecord(
    val id: String,
    val conversationId: String,
    val runId: String,
    val replyMessageId: String,
    val toolId: String,
    val argumentsJson: String,
    val status: ToolCallStatus,
    val result: String? = null,
    val displaySummary: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

data class ToolExecutionContext(
    val conversationId: String,
    val runId: String,
    val replyMessageId: String,
    val userRequest: String,
    /** 生成产物时用于把文件卡片放回对应的工具步骤。 */
    val toolCallRecordId: String? = null,
)

data class ToolResult(
    /** 发送给模型的结构化或文本结果。 */
    val content: String,
    /** 聊天页面展示的简短、可读说明。 */
    val summary: String,
    val isError: Boolean = false,
    /** 仅随当前模型请求传递，不写入工具记录，避免截图以 Base64 形式长期落库。 */
    val images: List<ChatImage> = emptyList(),
)

interface ToolProvider {
    val id: String
    val title: String
    val description: String
    val definitions: List<ToolDefinition>
    suspend fun availability(): ToolAvailability =
        ToolAvailability(ToolAvailabilityState.AVAILABLE)
    suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult
    /** 将内部参数转换为授权弹窗可读摘要；不得回显密钥、完整文件内容或内部标识。 */
    fun approvalSummary(call: RequestedToolCall): String? = null
    /** Run 结束、失败或取消时释放该提供者为本轮创建的临时资源。 */
    suspend fun finish(context: ToolExecutionContext) = Unit
}

class ToolRegistry(providers: List<ToolProvider>) {
    private val providers = providers.toList()
    private val providersByTool = buildMap {
        this@ToolRegistry.providers.forEach { provider ->
            provider.definitions.forEach { definition ->
                check(definition.providerId == provider.id) {
                    "工具 ${definition.id} 的能力组与提供者不一致"
                }
                check(put(definition.id, provider) == null) { "工具 ID 重复：${definition.id}" }
            }
        }
    }
    val definitions: List<ToolDefinition> = this.providers.flatMap(ToolProvider::definitions)
    val capabilityPlaceholders: List<ToolCapability> = this.providers.map { provider ->
        ToolCapability(
            provider.id,
            provider.title,
            provider.description,
            provider.definitions.size,
            ToolAvailability(ToolAvailabilityState.NEEDS_SETUP, "正在检查可用性"),
            provider.definitions.any(ToolDefinition::requiresPermissionApproval),
        )
    }

    fun definition(id: String): ToolDefinition? = definitions.firstOrNull { it.id == id }

    fun capabilityTitle(id: String): String? = providers.firstOrNull { it.id == id }?.title

    fun approvalSummary(call: RequestedToolCall): String? =
        providersByTool[call.toolId]?.approvalSummary(call)

    suspend fun capabilities(): List<ToolCapability> = providers.map { provider ->
        ToolCapability(
            provider.id,
            provider.title,
            provider.description,
            provider.definitions.size,
            provider.availability(),
            provider.definitions.any(ToolDefinition::requiresPermissionApproval),
        )
    }

    suspend fun availableDefinitions(): List<ToolDefinition> = providers.flatMap { provider ->
        if (provider.availability().state.isUsable()) provider.definitions else emptyList()
    }

    suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult {
        val provider = providersByTool[call.toolId]
            ?: return ToolResult("{\"error\":\"未知工具\"}", "未知工具 ${call.toolId}", true)
        val availability = provider.availability()
        if (!availability.state.isUsable()) {
            return ToolResult(
                "{\"error\":\"${availability.detail.ifBlank { availability.state.name }}\"}",
                availability.detail.ifBlank { "工具当前不可用" },
                true,
            )
        }
        return provider.execute(call, context)
    }

    /** 清理是兜底动作，单个提供者失败不能阻止其它提供者释放资源。 */
    suspend fun finish(context: ToolExecutionContext) {
        providers.forEach { provider -> runCatching { provider.finish(context) } }
    }
}

interface ToolPermissionStore {
    val accesses: Flow<Map<String, ToolAccess>>
    suspend fun access(capabilityId: String): ToolAccess
    suspend fun setEnabled(capabilityId: String, enabled: Boolean)
    suspend fun setPermission(capabilityId: String, mode: ToolPermissionMode)
}

private fun ToolAvailabilityState.isUsable() =
    this == ToolAvailabilityState.AVAILABLE || this == ToolAvailabilityState.DEGRADED

data class ToolApprovalRequest(
    val callId: String,
    val conversationId: String,
    val toolId: String,
    val capabilityTitle: String,
    val actionTitle: String,
    val description: String,
    val argumentsJson: String,
    val argumentsSummary: String? = null,
    val choices: List<ToolInvocationChoice> = emptyList(),
    val requiresPermissionApproval: Boolean = true,
)

data class ToolApprovalDecision(
    val allowed: Boolean,
    val choiceId: String? = null,
    val permanentlyAllowCapability: Boolean = false,
)
