package xyz.chouxuewei.mobile_agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullWindowDockTargetTest {
    @Test
    fun `continued outward drag selects matching edge`() {
        assertEquals(
            FullWindowDockTarget.LEFT,
            fullWindowDockTarget(rawX = 84f, minimumX = 100f, maximumX = 300f, threshold = 16f),
        )
        assertEquals(
            FullWindowDockTarget.RIGHT,
            fullWindowDockTarget(rawX = 316f, minimumX = 100f, maximumX = 300f, threshold = 16f),
        )
    }

    @Test
    fun `touching boundary or moving vertically does not collapse`() {
        assertNull(fullWindowDockTarget(rawX = 100f, minimumX = 100f, maximumX = 300f, threshold = 16f))
        assertNull(fullWindowDockTarget(rawX = 300f, minimumX = 100f, maximumX = 300f, threshold = 16f))
        assertNull(fullWindowDockTarget(rawX = 170f, minimumX = 100f, maximumX = 300f, threshold = 16f))
    }
}
