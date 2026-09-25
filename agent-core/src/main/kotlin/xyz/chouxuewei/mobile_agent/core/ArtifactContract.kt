package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.Flow

enum class ArtifactStatus { AVAILABLE, DELETED }

data class StorageCleanupResult(
    val filesDeleted: Int = 0,
    val bytesFreed: Long = 0,
    val permissionsReleased: Int = 0,
) {
    operator fun plus(other: StorageCleanupResult) = StorageCleanupResult(
        filesDeleted + other.filesDeleted,
        bytesFreed + other.bytesFreed,
        permissionsReleased + other.permissionsReleased,
    )
}

/**
 * 工具生成的正式文件。模型只接收 id 与 contentUri，storagePath 仅供本地文件实现定位内容。
 */
data class Artifact(
    val id: String,
    val conversationId: String,
    val runId: String,
    val replyMessageId: String,
    val sourceToolCallId: String?,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: String,
    val storagePath: String,
    val status: ArtifactStatus = ArtifactStatus.AVAILABLE,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

/** 文件索引与物理删除由数据层共同负责，避免页面只删记录却遗留实际文件。 */
interface ArtifactStore {
    fun observeArtifacts(conversationId: String): Flow<List<Artifact>>
    suspend fun artifacts(conversationId: String): List<Artifact>
    suspend fun artifact(id: String): Artifact?
    suspend fun saveArtifact(artifact: Artifact)
    suspend fun deleteArtifact(id: String): Boolean
    /** 启动时修复缺失记录并清理由异常中断留下的临时或孤立文件。 */
    suspend fun cleanup(): StorageCleanupResult = StorageCleanupResult()
}
