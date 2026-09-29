package xyz.chouxuewei.mobile_agent.data

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 对话备份编解码：五张表的实体行原样进 JSON，id 保持不变——
 * 导入按主键冲突忽略合并，重复导入幂等；附件仅存 URI 引用不随包携带。
 */
internal object ConversationBackup {
    const val FORMAT = "mobile-agent-backup"
    const val VERSION = 1

    data class Bundle(
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
        val runs: List<RunEntity>,
        val toolCalls: List<ToolCallEntity>,
        val snapshots: List<SnapshotEntity>,
    )

    fun encode(bundle: Bundle): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("exportedAt", System.currentTimeMillis())
        putJsonArray("conversations") { bundle.conversations.forEach { add(encodeConversation(it)) } }
        putJsonArray("messages") { bundle.messages.forEach { add(encodeMessage(it)) } }
        putJsonArray("runs") { bundle.runs.forEach { add(encodeRun(it)) } }
        putJsonArray("tool_calls") { bundle.toolCalls.forEach { add(encodeToolCall(it)) } }
        putJsonArray("snapshots") { bundle.snapshots.forEach { add(encodeSnapshot(it)) } }
    }.toString()

    fun decode(raw: String): Bundle {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw IllegalArgumentException(localizedText("备份文件不是有效的 JSON", "The backup file is not valid JSON."))
        }
        require(root["format"]?.jsonPrimitive?.contentOrNull == FORMAT) {
            localizedText("不是本应用的备份文件", "Not a backup file of this app.")
        }
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
        require(version in 1..VERSION) { localizedText("备份版本 $version 高于当前应用支持的 $VERSION", "Backup version $version is newer than supported $VERSION.") }
        return Bundle(
            conversations = rows(root, "conversations", ::decodeConversation),
            messages = rows(root, "messages", ::decodeMessage),
            runs = rows(root, "runs", ::decodeRun),
            toolCalls = rows(root, "tool_calls", ::decodeToolCall),
            snapshots = rows(root, "snapshots", ::decodeSnapshot),
        )
    }

    private fun <T> rows(root: JsonObject, key: String, decodeRow: (JsonObject) -> T): List<T> =
        (root[key] as? JsonArray).orEmpty().mapNotNull { row ->
            runCatching { decodeRow(row.jsonObject) }.getOrNull()
        }

    private fun encodeConversation(e: ConversationEntity) = buildJsonObject {
        put("id", e.id); put("title", e.title); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
        put("draft", e.draft); put("attachments", e.attachments); put("reasoningEffort", e.reasoningEffort)
        put("pinned", e.pinned)
    }

    private fun decodeConversation(o: JsonObject) = ConversationEntity(
        id = o["id"]!!.jsonPrimitive.content,
        title = o["title"]!!.jsonPrimitive.content,
        createdAt = o["createdAt"]!!.jsonPrimitive.longOrNull ?: 0L,
        updatedAt = o["updatedAt"]!!.jsonPrimitive.longOrNull ?: 0L,
        draft = o["draft"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        attachments = o["attachments"]?.jsonPrimitive?.contentOrNull ?: "[]",
        reasoningEffort = o["reasoningEffort"]?.jsonPrimitive?.contentOrNull,
        pinned = o["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private fun encodeMessage(e: MessageEntity) = buildJsonObject {
        put("id", e.id); put("conversationId", e.conversationId); put("sequence", e.sequence)
        put("role", e.role); put("text", e.text); put("status", e.status); put("createdAt", e.createdAt)
        put("attachments", e.attachments); put("version", e.version); put("error", e.error)
        put("reasoning", e.reasoning); put("reasoningDurationMillis", e.reasoningDurationMillis)
    }

    private fun decodeMessage(o: JsonObject) = MessageEntity(
        id = o["id"]!!.jsonPrimitive.content,
        conversationId = o["conversationId"]!!.jsonPrimitive.content,
        sequence = o["sequence"]!!.jsonPrimitive.longOrNull ?: 0L,
        role = o["role"]!!.jsonPrimitive.content,
        text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        status = o["status"]!!.jsonPrimitive.content,
        createdAt = o["createdAt"]!!.jsonPrimitive.longOrNull ?: 0L,
        attachments = o["attachments"]?.jsonPrimitive?.contentOrNull ?: "[]",
        version = o["version"]?.jsonPrimitive?.longOrNull ?: 1L,
        error = o["error"]?.jsonPrimitive?.contentOrNull,
        reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        reasoningDurationMillis = o["reasoningDurationMillis"]?.jsonPrimitive?.longOrNull,
    )

    private fun encodeRun(e: RunEntity) = buildJsonObject {
        put("id", e.id); put("conversationId", e.conversationId)
        put("triggerMessageId", e.triggerMessageId); put("replyMessageId", e.replyMessageId)
        put("status", e.status); put("model", e.model)
        put("startedAt", e.startedAt); put("finishedAt", e.finishedAt); put("error", e.error)
    }

    private fun decodeRun(o: JsonObject) = RunEntity(
        id = o["id"]!!.jsonPrimitive.content,
        conversationId = o["conversationId"]!!.jsonPrimitive.content,
        triggerMessageId = o["triggerMessageId"]!!.jsonPrimitive.content,
        replyMessageId = o["replyMessageId"]!!.jsonPrimitive.content,
        status = o["status"]!!.jsonPrimitive.content,
        model = o["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        startedAt = o["startedAt"]!!.jsonPrimitive.longOrNull ?: 0L,
        finishedAt = o["finishedAt"]?.jsonPrimitive?.longOrNull,
        error = o["error"]?.jsonPrimitive?.contentOrNull,
    )

    private fun encodeToolCall(e: ToolCallEntity) = buildJsonObject {
        put("id", e.id); put("conversationId", e.conversationId); put("runId", e.runId)
        put("replyMessageId", e.replyMessageId); put("toolId", e.toolId)
        put("argumentsJson", e.argumentsJson); put("status", e.status)
        put("result", e.result); put("displaySummary", e.displaySummary); put("error", e.error)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }

    private fun decodeToolCall(o: JsonObject) = ToolCallEntity(
        id = o["id"]!!.jsonPrimitive.content,
        conversationId = o["conversationId"]!!.jsonPrimitive.content,
        runId = o["runId"]!!.jsonPrimitive.content,
        replyMessageId = o["replyMessageId"]!!.jsonPrimitive.content,
        toolId = o["toolId"]!!.jsonPrimitive.content,
        argumentsJson = o["argumentsJson"]?.jsonPrimitive?.contentOrNull ?: "{}",
        status = o["status"]!!.jsonPrimitive.content,
        result = o["result"]?.jsonPrimitive?.contentOrNull,
        displaySummary = o["displaySummary"]?.jsonPrimitive?.contentOrNull,
        error = o["error"]?.jsonPrimitive?.contentOrNull,
        createdAt = o["createdAt"]!!.jsonPrimitive.longOrNull ?: 0L,
        updatedAt = o["updatedAt"]!!.jsonPrimitive.longOrNull ?: 0L,
    )

    private fun encodeSnapshot(e: SnapshotEntity) = buildJsonObject {
        put("id", e.id); put("conversationId", e.conversationId); put("boundary", e.boundary)
        put("sourceVersions", e.sourceVersions); put("summary", e.summary); put("model", e.model)
        put("inputTokensBefore", e.inputTokensBefore); put("inputTokensAfter", e.inputTokensAfter)
        put("createdAt", e.createdAt); put("formatVersion", e.formatVersion)
    }

    private fun decodeSnapshot(o: JsonObject) = SnapshotEntity(
        id = o["id"]!!.jsonPrimitive.content,
        conversationId = o["conversationId"]!!.jsonPrimitive.content,
        boundary = o["boundary"]!!.jsonPrimitive.longOrNull ?: 0L,
        sourceVersions = o["sourceVersions"]?.jsonPrimitive?.contentOrNull ?: "{}",
        summary = o["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        model = o["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        inputTokensBefore = o["inputTokensBefore"]?.jsonPrimitive?.intOrNull ?: 0,
        inputTokensAfter = o["inputTokensAfter"]?.jsonPrimitive?.intOrNull ?: 0,
        createdAt = o["createdAt"]!!.jsonPrimitive.longOrNull ?: 0L,
        formatVersion = o["formatVersion"]?.jsonPrimitive?.intOrNull ?: 1,
    )
}
