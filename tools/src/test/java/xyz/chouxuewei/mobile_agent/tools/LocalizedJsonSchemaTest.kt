package xyz.chouxuewei.mobile_agent.tools

import java.util.Locale
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.ExecutionSession
import xyz.chouxuewei.mobile_agent.core.Observation

class LocalizedJsonSchemaTest {
    @Test
    fun schemasRemainValidJsonInChineseAndEnglish() {
        val previous = Locale.getDefault()
        try {
            listOf(Locale.SIMPLIFIED_CHINESE to "参数说明", Locale.ENGLISH to "Parameter description")
                .forEach { (locale, expected) ->
                    Locale.setDefault(locale)
                    val schema = localizedJsonSchema(
                        """{"type":"object","properties":{"value":{"type":"string","description":localizedText("参数说明", "Parameter description")}}}""",
                    )
                    val parsed = TOOL_JSON.parseToJsonElement(schema).jsonObject
                    val description = parsed["properties"]!!.jsonObject["value"]!!.jsonObject["description"]!!.jsonPrimitive.content
                    assertEquals(expected, description)
                    assertFalse(schema.contains("localizedText("))

                    // 这些提供者覆盖单行结构和多行批量结构，初始化时也会执行完整 JSON 校验。
                    (NetworkToolProvider().definitions + DeviceToolProvider(FakeDeviceGateway).definitions)
                        .forEach { TOOL_JSON.parseToJsonElement(it.inputSchema).jsonObject }
                }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private object FakeDeviceGateway : DeviceGateway {
        override suspend fun openSession(mode: ExecutionMode): DeviceResult<ExecutionSession> =
            DeviceResult.Unsupported("test")

        override suspend fun observe(sessionId: String): DeviceResult<Observation> =
            DeviceResult.Unsupported("test")

        override suspend fun execute(
            sessionId: String,
            observationId: String?,
            action: Action,
        ): ActionResult = ActionResult.Unsupported("test")

        override suspend fun closeSession(sessionId: String): DeviceResult<Unit> =
            DeviceResult.Success(Unit)
    }
}
