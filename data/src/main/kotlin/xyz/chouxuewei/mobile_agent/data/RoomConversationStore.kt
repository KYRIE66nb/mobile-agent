package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

class RoomConversationStore internal constructor(private val database: AgentDatabase) : ConversationStore {
    constructor(context: Context) : this(DatabaseProvider.get(context))
    private val dao = database.conversations()
    override fun observeConversations() = dao.observeConversations().map { it.map(ConversationEntity::record) }
    override fun observeMessages(conversationId: String) = dao.observeMessages(conversationId).map { it.map(MessageEntity::record) }
    override suspend fun createConversation(): Conversation {
        val c = Conversation(UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
        dao.save(c.entity()); return c
    }
    override suspend fun conversation(id: String) = dao.conversation(id)?.record()
    override suspend fun rename(id: String, title: String) { require(title.isNotBlank()); dao.rename(id, title.trim().take(100)) }
    override suspend fun setPinned(id: String, pinned: Boolean) {
        check(dao.setPinned(id, pinned) == 1) { "找不到要置顶的对话" }
    }
    override suspend fun deleteConversation(id: String) = database.withTransaction {
        // 运行中的任务仍可能继续回写消息，必须先结束回复再允许级联删除。
        check(dao.activeCount(id) == 0) { "这段对话正在回复，请停止后再删除" }
        check(dao.deleteConversation(id) == 1) { "找不到要删除的对话" }
    }
    override suspend fun saveDraft(
        id: String,
        text: String,
        attachments: List<AttachmentRef>,
        reasoningEffort: String?,
    ) = dao.draft(id, text, encodeAttachments(attachments), reasoningEffort)
    override suspend fun messages(id: String) = dao.messages(id).map(MessageEntity::record)

    override suspend fun enqueue(id: String, text: String, attachments: List<AttachmentRef>): Message = database.withTransaction {
        val c = requireNotNull(dao.conversation(id))
        val history = dao.messages(id)
        // 为回复预留相邻序号，即使多个补充已排队，回复仍位于对应用户消息之后。
        val m = Message(UUID.randomUUID().toString(), id, (history.maxOfOrNull { it.sequence } ?: 0) + 2,
            MessageRole.USER, text, MessageStatus.QUEUED, System.currentTimeMillis(), attachments)
        dao.save(m.entity())
        val firstTitle = text.take(28).ifBlank { if (attachments.any(AttachmentRef::isImage)) "图片对话" else "新对话" }
        dao.save(c.copy(title = if (history.isEmpty() && c.title == "新对话") firstTitle else c.title,
            draft = "", attachments = "[]", reasoningEffort = null, updatedAt = m.createdAt))
        m
    }
    override suspend fun beginRun(id: String, triggerId: String, model: String): Run = database.withTransaction {
        check(dao.activeCount(id) == 0) { "这段对话已有正在执行的回复" }
        val trigger = dao.messages(id).first { it.id == triggerId }
        check(trigger.status == MessageStatus.QUEUED.name)
        dao.save(trigger.copy(status = MessageStatus.COMPLETE.name, version = trigger.version + 1))
        val now = System.currentTimeMillis()
        val reply = Message(UUID.randomUUID().toString(), id, trigger.sequence + 1, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, now)
        val run = Run(UUID.randomUUID().toString(), id, triggerId, reply.id, RunStatus.GENERATING, model, now)
        dao.save(reply.entity()); dao.save(run.entity()); run
    }
    override suspend fun setRunModel(run: Run, model: String) = dao.save(run.copy(model=model).entity())
    override suspend fun updateReply(run: Run, text: String, assistantSteps: List<AssistantStep>) =
        dao.reply(run.replyMessageId, text, encodeAssistantSteps(assistantSteps))
    override suspend fun finishRun(
        run: Run,
        text: String,
        assistantSteps: List<AssistantStep>,
        reasoningDurationMillis: Long?,
        status: RunStatus,
        error: String?,
    ) = database.withTransaction {
        val messageStatus = when (status) {
            RunStatus.SUCCEEDED -> MessageStatus.COMPLETE
            RunStatus.CANCELLED -> MessageStatus.CANCELLED
            RunStatus.INTERRUPTED -> MessageStatus.INTERRUPTED
            else -> MessageStatus.FAILED
        }
        dao.finishMessage(
            run.replyMessageId,
            text,
            encodeAssistantSteps(assistantSteps),
            reasoningDurationMillis,
            messageStatus.name,
            error,
        )
        dao.save(run.copy(status = status, finishedAt = System.currentTimeMillis(), error = error).entity())
    }
    override suspend fun recoverInterrupted() = database.withTransaction {
        val now = System.currentTimeMillis()
        dao.interruptRuns(now); dao.interruptMessages(); dao.interruptToolCalls(now)
    }
    override suspend fun snapshot(id: String) = dao.snapshot(id)?.record()
    override suspend fun publishSnapshot(snapshot: ContextSnapshot): Boolean = database.withTransaction {
        val prefix = dao.messages(snapshot.conversationId).filter { it.sequence <= snapshot.boundary }
        if (prefix.isEmpty() || prefix.any { it.status in listOf("QUEUED", "GENERATING") } ||
            prefix.associate { it.id to it.version } != snapshot.sourceVersions) return@withTransaction false
        if ((dao.snapshot(snapshot.conversationId)?.boundary ?: -1) > snapshot.boundary) return@withTransaction false
        currentCoroutineContext().ensureActive()
        dao.save(snapshot.entity()); true
    }
    override suspend fun searchMessages(query: String, conversationId: String?, limit: Int): List<HistoryMessageMatch> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        val terms = normalized.split(Regex("[\\s，。！？、：;；]+"))
            .filter { it.length >= 2 } + normalized.windowed(2).filter { it.all(Char::isLetterOrDigit) }
        val titles = dao.conversations().associate { it.id to it.title }
        return dao.searchableMessages(conversationId).map { entity ->
            val score = (if (entity.text.contains(normalized, true)) 100 else 0) +
                terms.count { entity.text.contains(it, true) }
            entity to score
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MessageEntity, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit.coerceIn(1, 20))
            .map { (entity, _) -> HistoryMessageMatch(
                entity.id, entity.conversationId, titles[entity.conversationId].orEmpty(),
                MessageRole.valueOf(entity.role), entity.text, entity.createdAt,
            ) }
    }
    override suspend fun messagesByIds(ids: List<String>) =
        if (ids.isEmpty()) emptyList() else dao.messagesByIds(ids.take(20)).map(MessageEntity::record)
    override suspend fun referencedAttachmentUris(): Set<String> =
        (dao.conversationAttachmentJson() + dao.messageAttachmentJson())
            .flatMap(::decodeAttachmentsSafely)
            .mapTo(linkedSetOf(), AttachmentRef::uri)
    override fun observeToolCalls(conversationId: String) =
        dao.observeToolCalls(conversationId).map { values -> values.map(ToolCallEntity::record) }
    override suspend fun toolCalls(conversationId: String) = dao.toolCalls(conversationId).map(ToolCallEntity::record)
    override suspend fun toolCall(id: String) = dao.toolCall(id)?.record()
    override suspend fun saveToolCall(call: ToolCallRecord) { dao.save(call.entity()) }
    override suspend fun updateToolCall(call: ToolCallRecord) { dao.save(call.entity()) }
    override suspend fun expireToolResults(toolIds: Set<String>, replacement: String) {
        if (toolIds.isNotEmpty()) dao.expireToolResults(toolIds.toList(), replacement, System.currentTimeMillis())
    }
}

internal fun encodeAttachments(values: List<AttachmentRef>): String = buildJsonArray { values.forEach {
    add(buildJsonObject {
        put("uri", it.uri)
        put("name", it.name)
        put("mimeType", it.mimeType)
        it.sizeBytes?.let { size -> put("sizeBytes", size) }
    })
} }.toString()
internal fun decodeAttachments(json: String): List<AttachmentRef> = Json.parseToJsonElement(json).jsonArray.map {
    val o = it.jsonObject
    AttachmentRef(
        o.getValue("uri").jsonPrimitive.content,
        o.getValue("name").jsonPrimitive.content,
        o["mimeType"]?.jsonPrimitive?.contentOrNull,
        o["sizeBytes"]?.jsonPrimitive?.longOrNull,
    )
}
private fun decodeAttachmentsSafely(json: String): List<AttachmentRef> =
    runCatching { decodeAttachments(json) }.getOrDefault(emptyList())
/** 复用现有 reasoning 列保存步骤数组；旧版本的纯文本自动作为一个步骤读取。 */
internal fun encodeReasoningSteps(values: List<String>): String =
    REASONING_STEPS_PREFIX + buildJsonArray { values.filter(String::isNotBlank).forEach(::add) }
internal fun decodeReasoningSteps(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    if (!value.startsWith(REASONING_STEPS_PREFIX)) return listOf(value)
    return runCatching {
        Json.parseToJsonElement(value.removePrefix(REASONING_STEPS_PREFIX))
            .jsonArray.map { it.jsonPrimitive.content }
    }.getOrElse { listOf(value) }.filter(String::isNotBlank)
}
private const val REASONING_STEPS_PREFIX = "reasoning-steps:v1:"

/** 新回复把每轮的思考、工具引用和正文一起保存；旧 reasoning 内容仍走上面的兼容读取。 */
internal fun encodeAssistantSteps(values: List<AssistantStep>): String =
    ASSISTANT_STEPS_PREFIX + buildJsonArray {
        values.forEach { step -> add(buildJsonObject {
            put("reasoning", step.reasoning)
            putJsonArray("toolCallIds") { step.toolCallIds.forEach(::add) }
            put("text", step.text)
            step.reasoningDurationMillis?.let { put("reasoningDurationMillis", it) }
        }) }
    }

internal fun decodeAssistantSteps(value: String): List<AssistantStep> {
    if (!value.startsWith(ASSISTANT_STEPS_PREFIX)) return emptyList()
    return runCatching {
        Json.parseToJsonElement(value.removePrefix(ASSISTANT_STEPS_PREFIX)).jsonArray.map { element ->
            val step = element.jsonObject
            AssistantStep(
                reasoning = step["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                toolCallIds = step["toolCallIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                text = step["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reasoningDurationMillis = step["reasoningDurationMillis"]?.jsonPrimitive?.longOrNull,
            )
        }
    }.getOrDefault(emptyList())
}
private const val ASSISTANT_STEPS_PREFIX = "assistant-steps:v2:"
private fun ConversationEntity.record() = Conversation(
    id, title, createdAt, updatedAt, draft, decodeAttachments(attachments), reasoningEffort, pinned,
)
private fun Conversation.entity() = ConversationEntity(
    id, title, createdAt, updatedAt, draft, encodeAttachments(attachments), reasoningEffort, pinned,
)
private fun MessageEntity.record(): Message {
    val hasAssistantSteps = reasoning.startsWith(ASSISTANT_STEPS_PREFIX)
    val steps = decodeAssistantSteps(reasoning)
    return Message(
        id, conversationId, sequence, MessageRole.valueOf(role), text, MessageStatus.valueOf(status),
        createdAt, decodeAttachments(attachments), version, error,
        if (hasAssistantSteps) steps.map(AssistantStep::reasoning).filter(String::isNotBlank)
        else decodeReasoningSteps(reasoning),
        reasoningDurationMillis,
        steps,
    )
}
private fun Message.entity() = MessageEntity(
    id, conversationId, sequence, role.name, text, status.name, createdAt,
    encodeAttachments(attachments), version, error,
    if (assistantSteps.isEmpty()) encodeReasoningSteps(reasoningSteps) else encodeAssistantSteps(assistantSteps),
    reasoningDurationMillis,
)
private fun Run.entity() = RunEntity(id, conversationId, triggerMessageId, replyMessageId, status.name, model, startedAt, finishedAt, error)
private fun SnapshotEntity.record() = ContextSnapshot(id, conversationId, boundary, Json.parseToJsonElement(sourceVersions).jsonObject.mapValues { it.value.jsonPrimitive.long }, summary, model, inputTokensBefore, inputTokensAfter, createdAt, formatVersion)
private fun ContextSnapshot.entity() = SnapshotEntity(id, conversationId, boundary, buildJsonObject { sourceVersions.forEach { (id, v) -> put(id, v) } }.toString(), summary, model, inputTokensBefore, inputTokensAfter, createdAt, formatVersion)
private fun ToolCallEntity.record() = ToolCallRecord(
    id, conversationId, runId, replyMessageId, toolId, argumentsJson, ToolCallStatus.valueOf(status),
    result, displaySummary, error, createdAt, updatedAt,
)
private fun ToolCallRecord.entity() = ToolCallEntity(
    id, conversationId, runId, replyMessageId, toolId, argumentsJson, status.name,
    result, displaySummary, error, createdAt, updatedAt,
)
