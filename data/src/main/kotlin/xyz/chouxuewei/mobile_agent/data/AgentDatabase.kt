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
    entities = [TaskEntity::class, StepEntity::class, ConversationEntity::class, MessageEntity::class, RunEntity::class, SnapshotEntity::class, ToolCallEntity::class, ArtifactEntity::class],
    version = 7,
    exportSchema = false,
)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun records(): AgentRecordDao
    abstract fun conversations(): ConversationDao
    abstract fun artifacts(): ArtifactDao
}
