package xyz.chouxuewei.mobile_agent.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "artifacts",
    indices = [Index("conversationId"), Index("replyMessageId"), Index("sourceToolCallId")],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ArtifactEntity(
    @androidx.room.PrimaryKey val id: String,
    val conversationId: String,
    val runId: String,
    val replyMessageId: String,
    val sourceToolCallId: String?,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: String,
    val storagePath: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
internal interface ArtifactDao {
    @Query("SELECT * FROM artifacts WHERE conversationId=:conversationId AND status='AVAILABLE' ORDER BY createdAt, rowid")
    fun observeAvailable(conversationId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE conversationId=:conversationId AND status='AVAILABLE' ORDER BY createdAt, rowid")
    suspend fun available(conversationId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE id=:id LIMIT 1")
    suspend fun artifact(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE status='AVAILABLE'")
    suspend fun allAvailable(): List<ArtifactEntity>

    @Upsert
    suspend fun save(artifact: ArtifactEntity)

    @Query("UPDATE artifacts SET status='DELETED', updatedAt=:updatedAt WHERE id=:id AND status='AVAILABLE'")
    suspend fun markDeleted(id: String, updatedAt: Long): Int
}
