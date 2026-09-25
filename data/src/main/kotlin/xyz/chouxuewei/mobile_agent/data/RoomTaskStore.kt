package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.StepRecord
import xyz.chouxuewei.mobile_agent.core.TaskRecord
import xyz.chouxuewei.mobile_agent.core.TaskStatus
import xyz.chouxuewei.mobile_agent.core.TaskStore

/**
 * Room 是任务记录的唯一持久化所有者。核心层只看到 TaskStore，不依赖数据库实体。
 */
class RoomTaskStore(context: Context) : TaskStore {
    private val database = DatabaseProvider.get(context)
    private val records = database.records()

    override suspend fun saveTask(task: TaskRecord) {
        records.saveTask(task.toEntity())
    }

    override suspend fun findTask(id: String): TaskRecord? = records.findTask(id)?.toRecord()

    override suspend fun saveStep(step: StepRecord) {
        records.saveStep(step.toEntity())
    }

    override suspend fun steps(taskId: String): List<StepRecord> =
        records.steps(taskId).map { it.toRecord() }

    override fun observeRecentTasks(limit: Int) = records.observeRecentTasks(
        limit.also { require(it in 1..100) { "任务记录数量必须在 1..100 之间" } },
    ).map { tasks -> tasks.map { it.toRecord() } }

    private fun TaskRecord.toEntity() = TaskEntity(
        id = id,
        instruction = instruction,
        status = status.name,
        updatedAtEpochMillis = updatedAtEpochMillis,
        error = error,
    )

    private fun TaskEntity.toRecord() = TaskRecord(
        id = id,
        instruction = instruction,
        status = TaskStatus.valueOf(status),
        updatedAtEpochMillis = updatedAtEpochMillis,
        error = error,
    )

    private fun StepRecord.toEntity() = StepEntity(
        taskId = taskId,
        stepIndex = index,
        observationId = observationId,
        actionJson = action?.let(RecordCodec::encodeAction),
        resultJson = result?.let(RecordCodec::encodeResult),
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private fun StepEntity.toRecord() = StepRecord(
        taskId = taskId,
        index = stepIndex,
        observationId = observationId,
        action = actionJson?.let(RecordCodec::decodeAction),
        result = resultJson?.let(RecordCodec::decodeResult),
        createdAtEpochMillis = createdAtEpochMillis,
    )
}
