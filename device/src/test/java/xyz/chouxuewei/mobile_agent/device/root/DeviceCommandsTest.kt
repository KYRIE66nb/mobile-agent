package xyz.chouxuewei.mobile_agent.device.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceCommandsTest {
    @Test fun browserTasksOnAnotherDisplayAreDetectedEvenWhenNotResumed() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{123 visible=false}
                * Hist #0: ActivityRecord{a u0 com.android.browser/.BrowserActivity t80}
            Display #7 (activities from top to bottom):
              topResumedActivity=ActivityRecord{b u0 com.android.browser/.BrowserActivity t81}
        """.trimIndent()
        assertEquals(setOf(0, 7), DeviceCommands.browserDisplays(dump))
    }

    @Test fun unrelatedPackageDoesNotBlockBrowser() {
        assertEquals(emptySet<Int>(), DeviceCommands.browserDisplays("""
            Display #0 (activities from top to bottom):
              topResumedActivity=ActivityRecord{b u0 com.android.browser.fake/.BrowserActivity t81}
        """.trimIndent()))
    }

    @Test fun packageParserCanValidateAnotherFixedTestApp() {
        val dump = """
            Display #0 (activities from top to bottom):
            Display #9 (activities from top to bottom):
              topResumedActivity=ActivityRecord{b u0 com.miui.calculator/.cal.CalculatorActivity t91}
        """.trimIndent()
        assertEquals(setOf(9), DeviceCommands.packageDisplays(dump, "com.miui.calculator"))
    }

    @Test fun resumedSummaryIsNotAssignedToLastVirtualDisplay() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Hist #0: ActivityRecord{a u0 com.miui.calculator/.cal.CalculatorActivity t90}
            Display #20 (activities from top to bottom):
              * Hist #0: ActivityRecord{b u0 com.android.browser/.BrowserActivity t91}
              Resumed activities in task display areas (from top to bottom):
              Resumed: ActivityRecord{a u0 com.miui.calculator/.cal.CalculatorActivity t90}
        """.trimIndent()
        assertEquals(setOf(0), DeviceCommands.packageDisplays(dump, "com.miui.calculator"))
    }

    @Test fun visibleWindowIsAssignedToItsWindowManagerDisplay() {
        val dump = """
            Display: mDisplayId=0 (organized)
              mCurrentFocus=Window{a u0 xyz.chouxuewei.mobile_agent/xyz.chouxuewei.mobile_agent.MainActivity}
            Display: mDisplayId=21
              mCurrentFocus=Window{b u0 com.android.browser/com.android.browser.BrowserActivity}
        """.trimIndent()
        assertEquals(setOf(21), DeviceCommands.packageWindowDisplays(dump, "com.android.browser"))
    }

    @Test fun unrecognizedTaskDumpCannotBeTreatedAsNoConflicts() {
        assertThrows(IllegalStateException::class.java) { DeviceCommands.browserDisplays("Permission denied") }
    }
}
