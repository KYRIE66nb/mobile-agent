package xyz.chouxuewei.mobile_agent.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
internal data class TaskEntity(
    @androidx.room.PrimaryKey val id: String,
    val instruction: String,
    val status: String,
    val updatedAtEpochMillis: Long,
    val error: String?,
)

@Entity(
    tableName = "steps",
    primaryKeys = ["taskId", "stepIndex"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
internal data class StepEntity(
    val taskId: String,
    val stepIndex: Int,
    val observationId: String,
    val actionJson: String?,
    val resultJson: String?,
    val createdAtEpochMillis: Long,
)

/** 专用决策后端的审计记录；只存裁决与元数据，不存请求原文/密钥/截图。 */
@Entity(
    tableName = "decision_records",
    indices = [Index("createdAtEpochMillis")],
)
internal data class DecisionRecordEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backend: String,
    val mode: String,
    val purpose: String,
    val requestHash: String,
    val requestId: String,
    val runId: String?,
    val toolCallId: String?,
    val observationId: String?,
    val candidateId: String?,
    val confidence: Double?,
    /** 服务端返回的有限概率分布，JSON 序列化的小 map。 */
    val probabilities: String,
    val requestedModel: String?,
    val returnedModel: String?,
    val usageInputTokens: Int?,
    val usageOutputTokens: Int?,
    val verdict: String,
    val fallbackReason: String?,
    val latencyMillis: Long,
    val keyGeneration: Int,
    val schemaVersion: Int,
    val policyVersion: Int,
    val createdAtEpochMillis: Long,
)

@Dao
internal interface DecisionAuditDao {
    @androidx.room.Insert
    suspend fun insert(record: DecisionRecordEntity): Long

    @Query("SELECT * FROM decision_records ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DecisionRecordEntity>

    @Query("SELECT * FROM decision_records ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DecisionRecordEntity>>

    @Query("DELETE FROM decision_records WHERE createdAtEpochMillis < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM decision_records")
    suspend fun count(): Int
}

@Dao
internal interface AgentRecordDao {
    @Upsert
    suspend fun saveTask(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun findTask(id: String): TaskEntity?

    @Upsert
    suspend fun saveStep(step: StepEntity)

    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun steps(taskId: String): List<StepEntity>

    @Query("SELECT * FROM tasks ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    fun observeRecentTasks(limit: Int): Flow<List<TaskEntity>>
}

@Database(
    entities = [TaskEntity::class, StepEntity::class, ConversationEntity::class, MessageEntity::class, RunEntity::class, SnapshotEntity::class, ToolCallEntity::class, ArtifactEntity::class, DecisionRecordEntity::class],
    version = 8,
    exportSchema = false,
)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun records(): AgentRecordDao
    abstract fun conversations(): ConversationDao
    abstract fun artifacts(): ArtifactDao
    abstract fun decisionAudits(): DecisionAuditDao
}
