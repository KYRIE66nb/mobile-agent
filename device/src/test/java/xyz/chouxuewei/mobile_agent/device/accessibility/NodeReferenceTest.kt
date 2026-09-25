package xyz.chouxuewei.mobile_agent.device.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NodeReferenceTest {
    @Test fun sameNodeIdentityProducesSameOpaqueReference() {
        assertEquals(reference(), reference())
    }

    @Test fun changedWindowResourceOrBoundsInvalidatesReference() {
        val original = reference()

        assertNotEquals(original, reference(windowId = 8))
        assertNotEquals(original, reference(viewId = "search_input"))
        assertNotEquals(original, reference(left = 101))
    }

    private fun reference(
        windowId: Int = 7,
        viewId: String? = "query",
        left: Int = 100,
    ) = NodeReference.create(
        windowId = windowId,
        viewId = viewId,
        packageName = "example.app",
        className = "android.widget.EditText",
        left = left,
        top = 200,
        right = 800,
        bottom = 320,
    )
}
