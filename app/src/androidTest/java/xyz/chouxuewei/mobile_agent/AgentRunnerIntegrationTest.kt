package xyz.chouxuewei.mobile_agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.Decision
import xyz.chouxuewei.mobile_agent.core.AgentRunRequest
import xyz.chouxuewei.mobile_agent.core.AgentRunResult
import xyz.chouxuewei.mobile_agent.core.AgentRunner
import xyz.chouxuewei.mobile_agent.core.AppTarget
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.ModelGateway
import xyz.chouxuewei.mobile_agent.core.ModelRequest
import xyz.chouxuewei.mobile_agent.core.TaskStatus
import xyz.chouxuewei.mobile_agent.data.InMemoryTaskStore
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 确定性模型驱动真实虚拟屏，验证 Runner 与 Root 设备层的循环和资源释放。 */
@RunWith(AndroidJUnit4::class)
class AgentRunnerIntegrationTest {
    @Test
    fun opensCalculatorThenObservesAgainAndCompletes() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as PrototypeApplication
        val taskId = "runner-calculator-${System.currentTimeMillis()}"
        val store = InMemoryTaskStore()
        val model = object : ModelGateway {
            override suspend fun decide(request: ModelRequest): Decision =
                if (request.recentResults.isEmpty()) {
                    Decision.Execute(Action.OpenApp(AppTarget("com.miui.calculator")))
                } else {
                    Decision.Completed("计算器已打开")
                }
        }
        val runner = AgentRunner(model, app.deviceGateway, store)

        val result = withTimeout(60_000) {
            runner.run(
                AgentRunRequest(
                    taskId = taskId,
                    instruction = "打开计算器",
                    mode = ExecutionMode.VIRTUAL_DISPLAY,
                    maxSteps = 4,
                ),
            )
        }

        assertTrue(result.toString(), result is AgentRunResult.Completed)
        val steps = store.steps(taskId)
        assertTrue("模型没有下发打开计算器动作：$steps", steps.any { step ->
            val action = step.action
            action is Action.OpenApp && action.target == AppTarget("com.miui.calculator") &&
                step.result is ActionResult.Performed
        })
        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId)?.status)
    }
}
