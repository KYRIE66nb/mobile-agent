package xyz.chouxuewei.mobile_agent.core

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AgentRunRequest(
    val taskId: String,
    val instruction: String,
    val mode: ExecutionMode,
    val maxSteps: Int = 99,
    val stepTimeoutMs: Long = 120_000,
) {
    init {
        require(taskId.isNotBlank()) { localizedText("任务 ID 不能为空", "Task ID cannot be empty.") }
        require(instruction.isNotBlank()) { localizedText("任务指令不能为空", "Task instructions cannot be empty.") }
        require(maxSteps in 1..999) { localizedText("步骤上限必须在 1..100 之间", "The step limit must be between 1 and 100.") }
        require(stepTimeoutMs in 100..300_000) { localizedText("单步超时必须在 100..300000 毫秒之间", "The per-step timeout must be between 100 and 300000 milliseconds.") }
    }
}

sealed interface AgentRunResult {
    data class Completed(val summary: String, val stepsExecuted: Int) : AgentRunResult
    data class WaitingUser(val reason: String, val stepsExecuted: Int) : AgentRunResult
    data class Failed(val reason: String, val stepsExecuted: Int) : AgentRunResult
    data class Cancelled(val stepsExecuted: Int) : AgentRunResult
    data class Busy(val runningTaskId: String) : AgentRunResult
}

data class AgentRunState(
    val taskId: String? = null,
    val instruction: String = "",
    val mode: ExecutionMode? = null,
    val status: TaskStatus? = null,
    val stepsExecuted: Int = 0,
    val message: String = localizedText("尚未运行任务", "No task has run yet"),
    val observation: Observation? = null,
) {
    val isRunning: Boolean
        get() = status == TaskStatus.CREATED || status == TaskStatus.RUNNING
}

/**
 * 单任务 Agent 循环。每次模型决策最多下发一个动作，动作后必须回到 observe 获取新识别结果。
 * 状态由 Runner 统一发布；前台服务只负责协程生命周期和把用户停止转换为取消。
 */
class AgentRunner(
    private val model: ModelGateway,
    private val device: DeviceGateway,
    private val store: TaskStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var activeTaskId: String? = null
    private val mutableState = MutableStateFlow(AgentRunState())
    val state = mutableState.asStateFlow()

    suspend fun run(request: AgentRunRequest): AgentRunResult {
        if (!running.compareAndSet(false, true)) {
            return AgentRunResult.Busy(activeTaskId ?: "unknown")
        }
        activeTaskId = request.taskId
        publish(request, TaskStatus.CREATED, 0, localizedText("正在准备手机操作", "Preparing phone operation"), observation = null)
        var session: ExecutionSession? = null
        try {
            saveTask(request, TaskStatus.CREATED)
            publish(request, TaskStatus.CREATED, 0, localizedText("正在启动手机操作", "Starting phone operation"))
            val opened = device.openSession(request.mode)
            if (opened !is DeviceResult.Success) {
                return persist(request, AgentRunResult.Failed(opened.reason(), 0))
            }
            session = opened.value
            saveTask(request, TaskStatus.RUNNING)
            publish(request, TaskStatus.RUNNING, 0, localizedText("手机操作已开始", "Phone operation started"))

            var outcome = runLoop(request, session)
            val closeResult = device.closeSession(session.id)
            session = null
            if (closeResult !is DeviceResult.Success) {
                outcome = AgentRunResult.Failed(
                    userFacingMessage(closeResult.reason(), localizedText("手机操作未能正常结束", "Phone operation did not end normally")),
                    outcome.steps(),
                )
            }
            return persist(request, outcome)
        } catch (_: CancellationException) {
            val result = AgentRunResult.Cancelled(mutableState.value.stepsExecuted)
            withContext(NonCancellable) {
                saveTask(request, TaskStatus.CANCELLED, localizedText("任务已取消", "Task cancelled"))
            }
            publish(request, TaskStatus.CANCELLED, result.stepsExecuted, localizedText("任务已取消", "Task cancelled"))
            return result
        } catch (error: Exception) {
            return persist(
                request,
                AgentRunResult.Failed(
                    userFacingMessage(error, localizedText("任务未完成，请重试", "The task was not completed. Please try again.")),
                    mutableState.value.stepsExecuted,
                ),
            )
        } finally {
            withContext(NonCancellable) {
                session?.let { device.closeSession(it.id) }
            }
            activeTaskId = null
            running.set(false)
        }
    }

    private suspend fun runLoop(
        request: AgentRunRequest,
        session: ExecutionSession,
    ): AgentRunResult {
        val recentResults = mutableListOf<ActionResult>()
        repeat(request.maxSteps) { stepIndex ->
            val outcome = try {
                withTimeout(request.stepTimeoutMs) {
                    runStep(request, session, stepIndex, recentResults)
                }
            } catch (_: TimeoutCancellationException) {
                AgentRunResult.Failed(
                    localizedText(
                        "手机操作超过 ${request.stepTimeoutMs} 毫秒未完成，已完成 $stepIndex 个动作",
                        "Phone operation did not finish within ${request.stepTimeoutMs} ms; $stepIndex actions completed.",
                    ),
                    stepIndex,
                )
            }
            if (outcome != null) return outcome
        }
        return AgentRunResult.Failed(localizedText("已达到操作次数上限，任务仍未完成", "The action limit was reached before the task completed"), request.maxSteps)
    }

    private suspend fun runStep(
        request: AgentRunRequest,
        session: ExecutionSession,
        stepIndex: Int,
        recentResults: MutableList<ActionResult>,
    ): AgentRunResult? {
        publish(request, TaskStatus.RUNNING, stepIndex, localizedText("正在识别手机界面", "Inspecting the phone screen"))
        val observed = device.observe(session.id)
        if (observed !is DeviceResult.Success) {
            return AgentRunResult.Failed(
                userFacingMessage(observed.reason(), localizedText("无法识别手机界面，请重试", "Could not inspect the phone screen. Please try again.")),
                stepIndex,
            )
        }
        val observation = observed.value
        publish(
            request,
            TaskStatus.RUNNING,
            stepIndex,
            localizedText("正在分析下一步操作", "Analyzing the next action"),
            observation,
        )
        val decision = model.decide(
            ModelRequest(request.instruction, observation, recentResults.takeLast(4)),
        )
        // 用户停止或单步超时后，不允许把已经返回的模型动作继续下发给设备。
        currentCoroutineContext().ensureActive()
        return when (decision) {
            is Decision.Completed -> AgentRunResult.Completed(decision.summary, stepIndex)
            is Decision.NeedsUser -> AgentRunResult.WaitingUser(decision.reason, stepIndex)
            is Decision.Execute -> {
                publish(
                    request,
                    TaskStatus.RUNNING,
                    stepIndex,
                    decision.action.progressText(),
                )
                val result = device.execute(session.id, observation.id, decision.action)
                store.saveStep(
                    StepRecord(
                        taskId = request.taskId,
                        index = stepIndex,
                        observationId = observation.id,
                        action = decision.action,
                        result = result,
                        createdAtEpochMillis = now(),
                    ),
                )
                if (result !is ActionResult.Performed) {
                    AgentRunResult.Failed(
                        userFacingMessage(result.reason(), localizedText("手机操作未完成，请重试", "Phone operation was not completed. Please try again.")),
                        stepIndex + 1,
                    )
                } else {
                    recentResults += result
                    publish(request, TaskStatus.RUNNING, stepIndex + 1, decision.action.completedText())
                    null
                }
            }
        }
    }

    private suspend fun persist(request: AgentRunRequest, result: AgentRunResult): AgentRunResult {
        when (result) {
            is AgentRunResult.Completed -> {
                saveTask(request, TaskStatus.SUCCEEDED)
                publish(request, TaskStatus.SUCCEEDED, result.stepsExecuted, result.summary)
            }
            is AgentRunResult.WaitingUser -> {
                saveTask(request, TaskStatus.WAITING_USER, result.reason)
                publish(request, TaskStatus.WAITING_USER, result.stepsExecuted, result.reason)
            }
            is AgentRunResult.Failed -> {
                saveTask(request, TaskStatus.FAILED, result.reason)
                publish(request, TaskStatus.FAILED, result.stepsExecuted, result.reason)
            }
            is AgentRunResult.Cancelled -> {
                saveTask(request, TaskStatus.CANCELLED, localizedText("任务已取消", "Task cancelled"))
                publish(request, TaskStatus.CANCELLED, result.stepsExecuted, localizedText("任务已取消", "Task cancelled"))
            }
            is AgentRunResult.Busy -> Unit
        }
        return result
    }

    private fun publish(
        request: AgentRunRequest,
        status: TaskStatus,
        stepsExecuted: Int,
        message: String,
        observation: Observation? = mutableState.value.observation,
    ) {
        mutableState.value = AgentRunState(
            taskId = request.taskId,
            instruction = request.instruction,
            mode = request.mode,
            status = status,
            stepsExecuted = stepsExecuted,
            message = message,
            observation = observation,
        )
    }

    private suspend fun saveTask(request: AgentRunRequest, status: TaskStatus, error: String? = null) {
        store.saveTask(TaskRecord(request.taskId, request.instruction, status, now(), error))
    }

    private fun AgentRunResult.steps(): Int = when (this) {
        is AgentRunResult.Completed -> stepsExecuted
        is AgentRunResult.WaitingUser -> stepsExecuted
        is AgentRunResult.Failed -> stepsExecuted
        is AgentRunResult.Cancelled -> stepsExecuted
        is AgentRunResult.Busy -> 0
    }

    private fun DeviceResult<*>.reason(): String = when (this) {
        is DeviceResult.Success -> ""
        is DeviceResult.Unsupported -> reason
        is DeviceResult.SessionExpired -> reason
        is DeviceResult.Failure -> reason
    }

    private fun ActionResult.reason(): String = when (this) {
        is ActionResult.Performed -> detail
        is ActionResult.Unsupported -> reason
        is ActionResult.SessionExpired -> reason
        is ActionResult.ObservationMismatch -> reason
        is ActionResult.TargetMismatch -> reason
        is ActionResult.Failure -> reason
    }

    private fun Action.progressText(): String = when (this) {
        is Action.Tap -> localizedText("正在点击目标位置", "Tapping target position")
        is Action.LongPress -> localizedText("正在长按目标位置", "Long-pressing target position")
        is Action.Swipe -> localizedText("正在滑动屏幕", "Swiping the screen")
        is Action.InputText -> localizedText("输入文本", "Enter text")
        is Action.PerformNodeAction -> localizedText("正在操作界面元素", "Operating a screen element")
        is Action.MultiStrokeGesture -> localizedText("正在执行复杂触控", "Running complex touch gesture")
        is Action.PressKey -> localizedText("正在执行系统导航", "Running system navigation")
        is Action.OpenApp -> localizedText("正在打开应用", "Opening app")
        is Action.Wait -> localizedText("正在等待界面响应", "Waiting for the screen to respond")
        Action.EnableNodeAccess -> localizedText("正在准备界面识别", "Preparing screen inspection")
    }

    private fun Action.completedText(): String = when (this) {
        is Action.Tap -> localizedText("已点击目标位置", "Target position tapped")
        is Action.LongPress -> localizedText("已长按目标位置", "Target position long-pressed")
        is Action.Swipe -> localizedText("已滑动屏幕", "Screen swiped")
        is Action.InputText -> localizedText("已输入文本", "Text entered")
        is Action.PerformNodeAction -> localizedText("已操作界面元素", "Screen element operated")
        is Action.MultiStrokeGesture -> localizedText("已完成复杂触控", "Complex touch gesture completed")
        is Action.PressKey -> localizedText("已完成系统导航", "System navigation completed")
        is Action.OpenApp -> localizedText("已打开应用", "App opened")
        is Action.Wait -> localizedText("界面已响应", "The screen responded")
        Action.EnableNodeAccess -> localizedText("界面识别已准备完成", "Screen inspection is ready")
    }
}
