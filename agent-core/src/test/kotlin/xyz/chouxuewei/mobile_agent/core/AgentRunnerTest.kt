package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerTest {
    @Test
    fun observesAgainAfterExactlyOneActionThenCompletes() = runBlocking {
        val events = mutableListOf<String>()
        val device = FakeDevice(events)
        val model = QueueModel(
            events,
            mutableListOf(Decision.Execute(Action.Wait(1)), Decision.Completed("完成")),
        )
        val store = FakeStore()
        val runner = AgentRunner(model, device, store) { 100L }

        val result = runner.run(AgentRunRequest("task-1", "测试任务", ExecutionMode.VIRTUAL_DISPLAY))

        assertEquals(AgentRunResult.Completed("完成", 1), result)
        assertEquals(
            listOf("open", "observe-0", "decide-observation-0", "execute-Wait", "observe-1", "decide-observation-1", "close"),
            events,
        )
        assertEquals(TaskStatus.SUCCEEDED, store.tasks.getValue("task-1").status)
        assertEquals(1, store.steps.getValue("task-1").size)
        assertEquals("observation-1", runner.state.value.observation?.id)
    }

    @Test
    fun actionFailureStopsWithoutAnotherObservation() = runBlocking {
        val events = mutableListOf<String>()
        val device = FakeDevice(events, ActionResult.Failure("注入失败"))
        val model = QueueModel(events, mutableListOf(Decision.Execute(Action.Tap(1, 1))))
        val store = FakeStore()

        val result = AgentRunner(model, device, store).run(
            AgentRunRequest("task-failed", "点击", ExecutionMode.MAIN_DISPLAY),
        )

        assertTrue(result is AgentRunResult.Failed && result.stepsExecuted == 1)
        assertEquals(listOf("open", "observe-0", "decide-observation-0", "execute-Tap", "close"), events)
        assertEquals(TaskStatus.FAILED, store.tasks.getValue("task-failed").status)
    }

    @Test
    fun needsUserDoesNotDispatchAnAction() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeStore()
        val runner = AgentRunner(
            QueueModel(events, mutableListOf(Decision.NeedsUser("请解锁"))),
            FakeDevice(events),
            store,
        )

        val result = runner.run(AgentRunRequest("task-wait", "继续", ExecutionMode.MAIN_DISPLAY))

        assertEquals(AgentRunResult.WaitingUser("请解锁", 0), result)
        assertTrue(events.none { it.startsWith("execute") })
        assertEquals(TaskStatus.WAITING_USER, store.tasks.getValue("task-wait").status)
    }

    @Test
    fun rejectsSecondTaskWhileFirstIsWaitingForModel() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = object : ModelGateway {
            override suspend fun decide(request: ModelRequest): Decision {
                entered.complete(Unit)
                release.await()
                return Decision.NeedsUser("测试结束")
            }
        }
        val runner = AgentRunner(model, FakeDevice(mutableListOf()), FakeStore())

        val first = async {
            runner.run(AgentRunRequest("task-running", "等待", ExecutionMode.MAIN_DISPLAY))
        }
        entered.await()
        val second = runner.run(AgentRunRequest("task-second", "并发", ExecutionMode.MAIN_DISPLAY))
        release.complete(Unit)

        assertEquals(AgentRunResult.Busy("task-running"), second)
        assertTrue(first.await() is AgentRunResult.WaitingUser)
    }

    @Test
    fun failsCurrentStepWhenModelExceedsTimeout() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeStore()
        val model = object : ModelGateway {
            override suspend fun decide(request: ModelRequest): Decision {
                delay(5_000)
                return Decision.Completed("不应到达")
            }
        }
        val runner = AgentRunner(model, FakeDevice(events), store)

        val result = runner.run(
            AgentRunRequest(
                taskId = "task-timeout",
                instruction = "等待模型",
                mode = ExecutionMode.MAIN_DISPLAY,
                stepTimeoutMs = 100,
            ),
        )

        assertTrue(result is AgentRunResult.Failed && result.reason.contains("超过 100 毫秒"))
        assertEquals(TaskStatus.FAILED, store.tasks.getValue("task-timeout").status)
        assertEquals(listOf("open", "observe-0", "close"), events)
    }

    @Test
    fun cancellationStopsBeforeDispatchAndPersistsCancelledStatus() = runBlocking {
        val events = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val model = object : ModelGateway {
            override suspend fun decide(request: ModelRequest): Decision {
                entered.complete(Unit)
                delay(5_000)
                return Decision.Execute(Action.Tap(1, 1))
            }
        }
        val store = FakeStore()
        val runner = AgentRunner(model, FakeDevice(events), store)

        val task = async {
            runner.run(AgentRunRequest("task-cancel", "停止任务", ExecutionMode.MAIN_DISPLAY))
        }
        entered.await()
        task.cancelAndJoin()

        assertEquals(TaskStatus.CANCELLED, store.tasks.getValue("task-cancel").status)
        assertEquals(TaskStatus.CANCELLED, runner.state.value.status)
        assertTrue(events.none { it.startsWith("execute") })
        assertEquals(listOf("open", "observe-0", "close"), events)
    }
}

private class QueueModel(
    private val events: MutableList<String>,
    private val decisions: MutableList<Decision>,
) : ModelGateway {
    override suspend fun decide(request: ModelRequest): Decision {
        events += "decide-${request.observation.id}"
        return decisions.removeAt(0)
    }
}

private class FakeDevice(
    private val events: MutableList<String>,
    private val actionResult: ActionResult = ActionResult.Performed("完成"),
) : DeviceGateway {
    private var observationIndex = 0

    override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> {
        events += "open"
        return DeviceResult.Success(ExecutionSession("session", mode, emptySet()))
    }

    override suspend fun observe(sessionId: String): DeviceResult<Observation> {
        val index = observationIndex++
        events += "observe-$index"
        return DeviceResult.Success(
            Observation(
                id = "observation-$index",
                sessionId = sessionId,
                capturedAtEpochMillis = index.toLong(),
                contentRevision = index.toLong(),
                viewport = Viewport(720, 1280),
                rotationDegrees = 0,
                foregroundPackage = null,
                screenshot = null,
                nodes = emptyList(),
            ),
        )
    }

    override suspend fun execute(
        sessionId: String,
        observationId: String?,
        action: Action,
    ): ActionResult {
        events += "execute-${action.javaClass.simpleName}"
        return actionResult
    }

    override suspend fun closeSession(sessionId: String): DeviceResult<Unit> {
        events += "close"
        return DeviceResult.Success(Unit)
    }
}

private class FakeStore : TaskStore {
    val tasks = mutableMapOf<String, TaskRecord>()
    val steps = mutableMapOf<String, MutableList<StepRecord>>()

    override suspend fun saveTask(task: TaskRecord) {
        tasks[task.id] = task
    }

    override suspend fun findTask(id: String): TaskRecord? = tasks[id]

    override suspend fun saveStep(step: StepRecord) {
        steps.getOrPut(step.taskId, ::mutableListOf) += step
    }

    override suspend fun steps(taskId: String): List<StepRecord> = steps[taskId].orEmpty()

    override fun observeRecentTasks(limit: Int) = flowOf(
        tasks.values.sortedByDescending(TaskRecord::updatedAtEpochMillis).take(limit),
    )
}
