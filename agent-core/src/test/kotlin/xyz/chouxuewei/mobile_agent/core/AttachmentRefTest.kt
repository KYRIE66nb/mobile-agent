package xyz.chouxuewei.mobile_agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRefTest {
    @Test
    fun rasterAndGifImagesCanBeSentToModel() {
        assertTrue(attachment("image/png").isImage)
        assertTrue(attachment("image/gif").isImage)
    }

    @Test
    fun svgRemainsReadableSourceInsteadOfBitmapInput() {
        assertFalse(attachment("image/svg+xml").isImage)
    }

    private fun attachment(mimeType: String) = AttachmentRef(
        uri = "content://test/image",
        name = "image",
        mimeType = mimeType,
    )
}
