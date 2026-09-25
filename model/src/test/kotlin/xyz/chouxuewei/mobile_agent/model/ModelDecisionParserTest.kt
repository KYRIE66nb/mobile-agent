package xyz.chouxuewei.mobile_agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.Decision
import xyz.chouxuewei.mobile_agent.core.Viewport

class ModelDecisionParserTest {
    @Test
    fun parsesAllowedActionFromCodeBlock() {
        val decision = ModelDecisionParser.parse(
            """```json
                {"type":"tap","x":120,"y":300}
                ```""".trimIndent(),
            Viewport(720, 1280),
        )

        val action = (decision as Decision.Execute).action as Action.Tap
        assertEquals(120, action.x)
        assertEquals(300, action.y)
    }

    @Test
    fun rejectsCoordinateOutsideCurrentViewport() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDecisionParser.parse(
                """{"type":"tap","x":720,"y":300}""",
                Viewport(720, 1280),
            )
        }
    }

    @Test
    fun rejectsInvalidPackageName() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDecisionParser.parse(
                """{"type":"open_app","package_name":"gallery"}""",
                Viewport(720, 1280),
            )
        }
    }

    @Test
    fun parsesRealApplicationPackageName() {
        val decision = ModelDecisionParser.parse(
            """{"type":"open_app","package_name":"com.android.settings"}""",
            Viewport(720, 1280),
        )

        val action = (decision as Decision.Execute).action as Action.OpenApp
        assertEquals("com.android.settings", action.target.packageName)
    }

    @Test
    fun acceptsActionFieldAliasWithAnyValidPackageName() {
        val decision = ModelDecisionParser.parse(
            """{"action":"open_app","package_name":"com.example.anyapp"}""",
            Viewport(720, 1280),
        )

        val action = (decision as Decision.Execute).action as Action.OpenApp
        assertEquals("com.example.anyapp", action.target.packageName)
    }
}
