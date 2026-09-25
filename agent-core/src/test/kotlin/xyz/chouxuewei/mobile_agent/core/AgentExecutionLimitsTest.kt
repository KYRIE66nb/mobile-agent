package xyz.chouxuewei.mobile_agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentExecutionLimitsTest {
    @Test
    fun defaultAndBoundariesAreValid() {
        assertEquals(99, DEFAULT_SINGLE_RUN_MAX_STEPS)
        assertEquals(MIN_SINGLE_RUN_MAX_STEPS, requireValidSingleRunMaxSteps(MIN_SINGLE_RUN_MAX_STEPS))
        assertEquals(MAX_SINGLE_RUN_MAX_STEPS, requireValidSingleRunMaxSteps(MAX_SINGLE_RUN_MAX_STEPS))
    }

    @Test
    fun valuesOutsideSupportedRangeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { requireValidSingleRunMaxSteps(0) }
        assertThrows(IllegalArgumentException::class.java) { requireValidSingleRunMaxSteps(1_000) }
    }
}
