package xyz.chouxuewei.mobile_agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.*

class DeviceExecutionModeTest {
    @Test
    fun malformedCallWithoutModeSafelyFallsBackToBackground() {
        assertEquals(ExecutionMode.VIRTUAL_DISPLAY, resolveDeviceExecutionMode(buildJsonObject {}))
    }

    @Test
    fun explicitBackgroundUsesVirtualDisplay() {
        val args = buildJsonObject { put("mode", "virtual") }
        assertEquals(ExecutionMode.VIRTUAL_DISPLAY, resolveDeviceExecutionMode(args))
    }

    @Test
    fun explicitForegroundUsesMainDisplay() {
        val args = buildJsonObject { put("mode", "main") }
        assertEquals(ExecutionMode.MAIN_DISPLAY, resolveDeviceExecutionMode(args))
    }

    @Test
    fun unknownModeFallsBackToBackground() {
        val args = buildJsonObject { put("mode", "unexpected") }
        assertEquals(ExecutionMode.VIRTUAL_DISPLAY, resolveDeviceExecutionMode(args))
    }

    @Test
    fun openingDeviceDoesNotForceAUserModeChoice() {
        val definition = DeviceToolProvider(UnusedGateway).definitions.single { it.id == "device_open" }
        assertTrue(definition.userChoices.isEmpty())
        assertTrue(definition.inputSchema.contains("\"required\":[\"mode\"]"))
        assertFalse(definition.inputSchema.contains("\"default\":"))
        assertTrue(definition.description.contains("任务目标"))
        assertTrue(definition.description.contains("打开或切换应用"))
    }

    private object UnusedGateway : DeviceGateway {
        override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> = error("unused")
        override suspend fun observe(sessionId: String): DeviceResult<Observation> = error("unused")
        override suspend fun execute(sessionId: String, observationId: String?, action: Action): ActionResult = error("unused")
        override suspend fun closeSession(sessionId: String): DeviceResult<Unit> = error("unused")
    }
}
