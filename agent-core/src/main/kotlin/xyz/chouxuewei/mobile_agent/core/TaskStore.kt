package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.Flow

enum class TaskStatus { CREATED, RUNNING, WAITING_USER, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

data class TaskRecord(
    val id: String,
    val instruction: String,
    val status: TaskStatus,
    val updatedAtEpochMillis: Long,
    val error: String? = null,
)

data class StepRecord(
    val taskId: String,
    val index: Int,
    val observationId: String,
    val action: Action?,
    val result: ActionResult?,
    val createdAtEpochMillis: Long,
)

interface TaskStore {
    suspend fun saveTask(task: TaskRecord)
    suspend fun findTask(id: String): TaskRecord?
    suspend fun saveStep(step: StepRecord)
    suspend fun steps(taskId: String): List<StepRecord>
    fun observeRecentTasks(limit: Int = 20): Flow<List<TaskRecord>>
}
