package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.core.ArtifactStatus
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.StorageCleanupResult

class RoomArtifactStore internal constructor(
    context: Context,
    private val database: AgentDatabase,
) : ArtifactStore {
    constructor(context: Context) : this(context, DatabaseProvider.get(context))

    private val dao = database.artifacts()
    private val generatedRoot = File(context.applicationContext.filesDir, "generated-tools").canonicalFile

    override fun observeArtifacts(conversationId: String) =
        dao.observeAvailable(conversationId).map { values -> values.map(ArtifactEntity::record) }

    override suspend fun artifacts(conversationId: String) =
        dao.available(conversationId).map(ArtifactEntity::record)

    override suspend fun artifact(id: String) = dao.artifact(id)?.record()

    override suspend fun saveArtifact(artifact: Artifact) {
        require(artifact.status == ArtifactStatus.AVAILABLE) { "只能登记可用产物" }
        require(isInsideGeneratedRoot(File(artifact.storagePath))) { "产物文件不在受控目录中" }
        dao.save(artifact.entity())
    }

    override suspend fun deleteArtifact(id: String): Boolean {
        val artifact = dao.artifact(id) ?: return false
        if (artifact.status != ArtifactStatus.AVAILABLE.name) return false
        val file = File(artifact.storagePath)
        require(isInsideGeneratedRoot(file)) { "产物文件不在受控目录中" }
        withContext(Dispatchers.IO) {
            if (file.exists() && !file.delete()) error("文件删除失败")
        }
        return dao.markDeleted(id, System.currentTimeMillis()) > 0
    }

    override suspend fun cleanup(): StorageCleanupResult = withContext(Dispatchers.IO) {
        generatedRoot.mkdirs()
        val available = dao.allAvailable()
        val registered = available.mapTo(hashSetOf()) { File(it.storagePath).canonicalPath }
        val now = System.currentTimeMillis()
        var filesDeleted = 0
        var bytesFreed = 0L

        // 崩溃可能发生在原子改名前后：临时文件和未登记的正式文件都不应永久残留。
        generatedRoot.walkBottomUp().filter(File::isFile).forEach { file ->
            val isTemporary = file.name.contains(".tmp-")
            if (isTemporary || file.canonicalPath !in registered) {
                val size = file.length()
                if (file.delete()) {
                    filesDeleted++
                    bytesFreed += size
                }
            }
        }
        available.filter { !File(it.storagePath).isFile }.forEach { missing ->
            dao.markDeleted(missing.id, now)
        }
        generatedRoot.walkBottomUp().filter { it.isDirectory && it != generatedRoot }
            .forEach { directory -> directory.delete() }
        StorageCleanupResult(filesDeleted, bytesFreed)
    }

    private fun isInsideGeneratedRoot(file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical != generatedRoot && canonical.path.startsWith(generatedRoot.path + File.separator)
    }
}

private fun ArtifactEntity.record() = Artifact(
    id, conversationId, runId, replyMessageId, sourceToolCallId, name, mimeType,
    sizeBytes, contentUri, storagePath, ArtifactStatus.valueOf(status), createdAt, updatedAt,
)

private fun Artifact.entity() = ArtifactEntity(
    id, conversationId, runId, replyMessageId, sourceToolCallId, name, mimeType,
    sizeBytes, contentUri, storagePath, status.name, createdAt, updatedAt,
)
