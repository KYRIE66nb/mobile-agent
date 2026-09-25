package xyz.chouxuewei.mobile_agent.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.StepRecord
import xyz.chouxuewei.mobile_agent.core.TaskRecord
import xyz.chouxuewei.mobile_agent.core.TaskStore

/** P2 调试使用的线程安全存储；P4 再替换为 Room，核心层接口不变。 */
class InMemoryTaskStore : TaskStore {
    private val tasks = ConcurrentHashMap<String, TaskRecord>()
    private val taskSteps = ConcurrentHashMap<String, MutableList<StepRecord>>()
    private val recentTasks = MutableStateFlow<List<TaskRecord>>(emptyList())

    override suspend fun saveTask(task: TaskRecord) {
        tasks[task.id] = task
        recentTasks.value = tasks.values.sortedByDescending(TaskRecord::updatedAtEpochMillis)
    }

    override suspend fun findTask(id: String): TaskRecord? = tasks[id]

    override suspend fun saveStep(step: StepRecord) {
        val steps = taskSteps.computeIfAbsent(step.taskId) { mutableListOf() }
        synchronized(steps) {
            steps.removeAll { it.index == step.index }
            steps += step
        }
    }

    override suspend fun steps(taskId: String): List<StepRecord> =
        taskSteps[taskId]?.let { steps -> synchronized(steps) { steps.sortedBy { it.index } } }.orEmpty()

    override fun observeRecentTasks(limit: Int) = recentTasks.map { it.take(limit) }
}
