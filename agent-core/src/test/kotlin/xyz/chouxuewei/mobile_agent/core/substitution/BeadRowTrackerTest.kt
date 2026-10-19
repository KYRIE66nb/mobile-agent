package xyz.chouxuewei.mobile_agent.core.substitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeadRowTrackerTest {
    private val L = DotState.LIT
    private val E = DotState.EMPTY
    private val U = DotState.UNKNOWN

    @Test fun `readable cells survive an expired earlier cell`() {
        val tracker = BeadRowTracker(400)
        assertEquals(4, tracker.update(listOf(L, L, L, L), 0))

        // The first cell is unreadable, but the last cell visibly becomes empty.
        assertNull(tracker.update(listOf(U, L, L, E), 500))
        assertEquals(3, tracker.update(listOf(L, L, L, U), 600))
    }

    @Test fun `retained cells expire from their last actual sighting`() {
        val tracker = BeadRowTracker(400)
        tracker.update(listOf(L, L, L, L), 0)
        assertNull(tracker.update(listOf(U, L, L, E), 500))

        assertEquals(3, tracker.update(listOf(L, L, L, U), 900))
        assertNull(tracker.update(listOf(L, L, L, U), 901))
    }

    @Test fun `remembering cells from an unreadable row still rejects holes`() {
        val tracker = BeadRowTracker(400)
        tracker.update(listOf(L, L, L, L), 0)
        assertNull(tracker.update(listOf(U, L, E, L), 500))

        assertNull(tracker.update(listOf(L, L, U, L), 600))
    }
}
