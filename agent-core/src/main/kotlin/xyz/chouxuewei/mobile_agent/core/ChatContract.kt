package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class MessageRole { USER, ASSISTANT }
enum class MessageStatus { QUEUED, COMPLETE, GENERATING, FAILED, CANCELLED, INTERRUPTED }
enum class RunStatus { GENERATING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
enum class ThemePreference { SYSTEM, PAPER, GRAPHITE, WARM }

data class AttachmentRef(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long? = null,
) {
    /** SVG 作为可读矢量源码处理；模型图片输入仍只走能够解码为位图的格式。 */
    val isImage: Boolean get() = mimeType?.startsWith("image/", ignoreCase = true) == true &&
        !mimeType.equals("image/svg+xml", ignoreCase = true)
}

/** 仅存在于单次模型请求中的图片内容，不写入数据库，避免重复保存大块 Base64。 */
data class ChatImage(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

fun interface ChatAttachmentLoader {
    suspend fun loadImage(attachment: AttachmentRef): ChatImage
}
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val draft: String = "",
    val attachments: List<AttachmentRef> = emptyList(),
    val reasoningEffort: String? = null,
    val pinned: Boolean = false,
)

/** 一次模型请求形成一个展示单元，保留思考、该轮工具调用和该轮正文的真实先后关系。 */
data class AssistantStep(
    val reasoning: String = "",
    val toolCallIds: List<String> = emptyList(),
    val text: String = "",
    val reasoningDurationMillis: Long? = null,
)

data class Message(
    val id: String,
    val conversationId: String,
    val sequence: Long,
    val role: MessageRole,
    val text: String,
    val status: MessageStatus,
    val createdAt: Long,
    val attachments: List<AttachmentRef> = emptyList(),
    val version: Long = 1,
    val error: String? = null,
    val reasoningSteps: List<String> = emptyList(),
    val reasoningDurationMillis: Long? = null,
    val assistantSteps: List<AssistantStep> = emptyList(),
)
data class Run(
    val id: String,
    val conversationId: String,
    val triggerMessageId: String,
    val replyMessageId: String,
    val status: RunStatus,
    val model: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val error: String? = null,
)
data class ContextSnapshot(
    val id: String,
    val conversationId: String,
    val boundary: Long,
    val sourceVersions: Map<String, Long>,
    val summary: String,
    val model: String,
    val inputTokensBefore: Int,
    val inputTokensAfter: Int,
    val createdAt: Long,
    val formatVersion: Int = 1,
)

data class HistoryMessageMatch(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: MessageRole,
    val text: String,
    val createdAt: Long,
)

/** 事务边界由存储实现；排队、开始执行和发布摘要不能分成互相竞争的页面写入。 */
interface ConversationStore {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun createConversation(): Conversation
    suspend fun conversation(id: String): Conversation?
    suspend fun rename(id: String, title: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun deleteConversation(id: String)
    suspend fun saveDraft(
        id: String,
        text: String,
        attachments: List<AttachmentRef>,
        reasoningEffort: String? = null,
    )
    suspend fun messages(id: String): List<Message>
    suspend fun enqueue(id: String, text: String, attachments: List<AttachmentRef>): Message
    suspend fun beginRun(id: String, triggerId: String, model: String): Run
    suspend fun setRunModel(run: Run, model: String)
    suspend fun updateReply(run: Run, text: String, assistantSteps: List<AssistantStep> = emptyList())
    suspend fun finishRun(
        run: Run,
        text: String,
        assistantSteps: List<AssistantStep>,
        reasoningDurationMillis: Long?,
        status: RunStatus,
        error: String? = null,
    )
    /** 兼容不需要记录思考内容的内部调用。 */
    suspend fun finishRun(run: Run, text: String, status: RunStatus, error: String? = null) =
        finishRun(run, text, emptyList(), null, status, error)
    suspend fun recoverInterrupted()
    suspend fun snapshot(id: String): ContextSnapshot?
    suspend fun publishSnapshot(snapshot: ContextSnapshot): Boolean
    /** 历史工具使用的只读入口；具体存储负责限定数量和排序。 */
    suspend fun searchMessages(query: String, conversationId: String?, limit: Int): List<HistoryMessageMatch> = emptyList()
    suspend fun messagesByIds(ids: List<String>): List<Message> = emptyList()
    /** 返回草稿和历史消息仍在使用的附件 URI，供应用清理自己导入的孤立文件。 */
    suspend fun referencedAttachmentUris(): Set<String> = emptySet()
    fun observeToolCalls(conversationId: String): Flow<List<ToolCallRecord>> = flowOf(emptyList())
    suspend fun toolCalls(conversationId: String): List<ToolCallRecord> = emptyList()
    suspend fun toolCall(id: String): ToolCallRecord? = null
    suspend fun saveToolCall(call: ToolCallRecord) = Unit
    suspend fun updateToolCall(call: ToolCallRecord) = Unit
    /** 单步临时结果在异常退出后也必须失效，避免旧节点或截图说明重新进入上下文。 */
    suspend fun expireToolResults(toolIds: Set<String>, replacement: String) = Unit
}

data class ChatTurn(
    val role: String,
    val content: String,
    val toolCalls: List<RequestedToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val attachmentRefs: List<AttachmentRef> = emptyList(),
    val images: List<ChatImage> = emptyList(),
    /** 仅供本地压缩和追溯使用，不发送给模型服务。 */
    val sourceToolCallId: String? = null,
)
data class ChatRequest(
    val messages: List<ChatTurn>,
    val maxOutputTokens: Int,
    val reasoningEffort: String? = null,
    val tools: List<ToolDefinition> = emptyList(),
)
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : ModelEvent
    data class ToolCall(val call: RequestedToolCall) : ModelEvent
    data class Completed(val reason: String) : ModelEvent
    data class Error(val message: String) : ModelEvent
}
fun interface ChatModelGateway { fun stream(request: ChatRequest): Flow<ModelEvent> }

/**
 * 新安装使用一组可直接运行的初始预算；用户仍可按模型服务的真实限制覆盖。
 * 上下文总量包含输入和最大输出，因此最大输出会从输入预算中预留。
 */
const val DEFAULT_CONTEXT_WINDOW_TOKENS = 180_000
const val DEFAULT_MAX_OUTPUT_TOKENS = 40_000

/** 聊天页显示的上下文额度；服务返回 usage 时 exact=true，否则为本地近似值。 */
data class ContextUsage(
    val inputTokens: Int,
    val outputReserve: Int,
    val windowTokens: Int,
    val exact: Boolean,
    val modelProfileId: String? = null,
    val compacted: Boolean = false,
) {
    val totalReservedTokens: Int get() =
        (inputTokens.toLong() + outputReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

data class ContextPolicy(
    val windowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val outputReserve: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) {
    // 文本和图片估算已经保留余量；这里只保留 1% 防止不同兼容服务的协议开销略有差异。
    val safetyTokens: Int get() = maxOf(512, windowTokens / 100)
    val inputBudget: Int get() = windowTokens - outputReserve - safetyTokens
    fun validate() {
        require(windowTokens in 2048..2_000_000) { "请填写模型支持的上下文长度，至少为 2048 Token" }
        require(outputReserve in 256 until windowTokens && inputBudget >= 1024) { "最大输出过大，请减小数值或增加上下文长度" }
    }
}

// 展示层只消费这些轻量投影；真实执行状态以 ToolCallRecord 为准。
data class ToolSummary(val title: String, val detail: String, val state: String)
