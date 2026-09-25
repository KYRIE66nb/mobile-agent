package xyz.chouxuewei.mobile_agent.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val draft: String,
    val attachments: String,
    val reasoningEffort: String?,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean,
)

@Entity(tableName = "messages", indices = [Index(value = ["conversationId", "sequence"], unique = true)], foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
internal data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sequence: Long,
    val role: String,
    val text: String,
    val status: String,
    val createdAt: Long,
    val attachments: String,
    val version: Long,
    val error: String?,
    @ColumnInfo(defaultValue = "''") val reasoning: String,
    val reasoningDurationMillis: Long?,
)

@Entity(tableName = "runs", indices = [Index("conversationId")], foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
internal data class RunEntity(@PrimaryKey val id: String, val conversationId: String, val triggerMessageId: String, val replyMessageId: String, val status: String, val model: String, val startedAt: Long, val finishedAt: Long?, val error: String?)

@Entity(tableName = "context_snapshots", indices = [Index("conversationId")], foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
internal data class SnapshotEntity(@PrimaryKey val id: String, val conversationId: String, val boundary: Long, val sourceVersions: String, val summary: String, val model: String, val inputTokensBefore: Int, val inputTokensAfter: Int, val createdAt: Long, val formatVersion: Int)

@Entity(
    tableName = "tool_calls",
    indices = [Index("conversationId"), Index("runId"), Index("replyMessageId")],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
internal data class ToolCallEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val runId: String,
    val replyMessageId: String,
    val toolId: String,
    val argumentsJson: String,
    val status: String,
    val result: String?,
    val displaySummary: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
internal interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id=:id") suspend fun conversation(id: String): ConversationEntity?
    @Query("SELECT * FROM conversations") suspend fun conversations(): List<ConversationEntity>
    @Upsert suspend fun save(conversation: ConversationEntity)
    @Query("UPDATE conversations SET draft=:text, attachments=:attachments, reasoningEffort=:reasoningEffort WHERE id=:id")
    suspend fun draft(id: String, text: String, attachments: String, reasoningEffort: String?)
    @Query("UPDATE conversations SET title=:title WHERE id=:id") suspend fun rename(id: String, title: String)
    @Query("UPDATE conversations SET pinned=:pinned WHERE id=:id") suspend fun setPinned(id: String, pinned: Boolean): Int
    @Query("DELETE FROM conversations WHERE id=:id") suspend fun deleteConversation(id: String): Int
    @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY sequence") fun observeMessages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY sequence") suspend fun messages(id: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE (:conversationId IS NULL OR conversationId=:conversationId) AND status NOT IN ('QUEUED','GENERATING') ORDER BY createdAt DESC LIMIT 1000")
    suspend fun searchableMessages(conversationId: String?): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id IN (:ids)") suspend fun messagesByIds(ids: List<String>): List<MessageEntity>
    @Query("SELECT attachments FROM conversations") suspend fun conversationAttachmentJson(): List<String>
    @Query("SELECT attachments FROM messages") suspend fun messageAttachmentJson(): List<String>
    @Upsert suspend fun save(message: MessageEntity)
    @Upsert suspend fun save(run: RunEntity)
    @Query("SELECT COUNT(*) FROM runs WHERE conversationId=:id AND status='GENERATING'") suspend fun activeCount(id: String): Int
    @Query("UPDATE messages SET text=:text, reasoning=:reasoning, version=version+1 WHERE id=:id")
    suspend fun reply(id: String, text: String, reasoning: String)
    @Query("UPDATE messages SET text=:text, reasoning=:reasoning, reasoningDurationMillis=:reasoningDurationMillis, status=:status, error=:error, version=version+1 WHERE id=:id")
    suspend fun finishMessage(
        id: String,
        text: String,
        reasoning: String,
        reasoningDurationMillis: Long?,
        status: String,
        error: String?,
    )
    @Query("UPDATE runs SET status='INTERRUPTED', finishedAt=:now, error='上次运行意外中断，内容未重新发送' WHERE status='GENERATING'") suspend fun interruptRuns(now: Long)
    @Query("UPDATE messages SET status='INTERRUPTED', error='上次运行意外中断，内容未重新发送', version=version+1 WHERE status IN ('QUEUED','GENERATING')") suspend fun interruptMessages()
    @Query("SELECT * FROM context_snapshots WHERE conversationId=:id ORDER BY createdAt DESC, rowid DESC LIMIT 1") suspend fun snapshot(id: String): SnapshotEntity?
    @Upsert suspend fun save(snapshot: SnapshotEntity)
    @Query("SELECT * FROM tool_calls WHERE conversationId=:id ORDER BY createdAt, rowid")
    fun observeToolCalls(id: String): Flow<List<ToolCallEntity>>
    @Query("SELECT * FROM tool_calls WHERE conversationId=:id ORDER BY createdAt, rowid")
    suspend fun toolCalls(id: String): List<ToolCallEntity>
    @Query("SELECT * FROM tool_calls WHERE id=:id LIMIT 1")
    suspend fun toolCall(id: String): ToolCallEntity?
    @Upsert suspend fun save(call: ToolCallEntity)
    @Query("UPDATE tool_calls SET status='INTERRUPTED', error='上次操作意外中断，未自动重试', updatedAt=:now WHERE status IN ('RECEIVED','WAITING_APPROVAL','EXECUTING')")
    suspend fun interruptToolCalls(now: Long)
    @Query("UPDATE tool_calls SET result=:replacement, updatedAt=:now WHERE toolId IN (:toolIds) AND result IS NOT NULL AND result != :replacement")
    suspend fun expireToolResults(toolIds: List<String>, replacement: String, now: Long)
}
