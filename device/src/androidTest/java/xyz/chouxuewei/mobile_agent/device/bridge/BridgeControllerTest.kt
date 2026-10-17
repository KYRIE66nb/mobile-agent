package xyz.chouxuewei.mobile_agent.device.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.ExecutionSession
import xyz.chouxuewei.mobile_agent.core.Observation

@RunWith(AndroidJUnit4::class)
class BridgeControllerTest {
    @Test
    fun pauseDrainsAcceptedActionAndRejectsSubsequentActions() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var executions = 0
        val gateway = object : DeviceGateway {
            override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> =
                DeviceResult.Unsupported("unused")
            override suspend fun observe(sessionId: String): DeviceResult<Observation> =
                DeviceResult.Unsupported("unused")
            override suspend fun closeSession(sessionId: String): DeviceResult<Unit> =
                DeviceResult.Success(Unit)
            override suspend fun execute(sessionId: String, observationId: String?, action: Action): ActionResult {
                executions++
                entered.complete(Unit)
                finish.await()
                return ActionResult.Performed()
            }
        }
        val controller = BridgeController(gateway)
        val action = BridgeController.Request("POST", "/action", JSONObject()
            .put("session_id", "session")
            .put("action", JSONObject().put("kind", "press_key").put("key", "home")))
        val accepted = async(start = CoroutineStart.UNDISPATCHED) { controller.handle(action) }
        entered.await()
        val paused = async(start = CoroutineStart.UNDISPATCHED) {
            controller.handle(BridgeController.Request("POST", "/screen/pause", JSONObject()))
        }
        assertFalse("Pause must wait for the accepted action", paused.isCompleted)
        finish.complete(Unit)
        assertEquals(200, accepted.await().status)
        assertEquals(200, paused.await().status)
        assertEquals(409, controller.handle(action).status)
        assertEquals(1, executions)
    }
}
