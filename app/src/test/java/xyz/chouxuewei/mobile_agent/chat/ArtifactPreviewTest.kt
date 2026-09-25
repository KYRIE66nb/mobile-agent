package xyz.chouxuewei.mobile_agent.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactPreviewTest {
    @Test
    fun routesSupportedImagesAndHtmlToVisualPreview() {
        assertEquals(ArtifactPreviewKind.IMAGE, artifactPreviewKind("image/jpeg", "photo.jpg"))
        assertEquals(ArtifactPreviewKind.IMAGE, artifactPreviewKind("image/jpg", "photo.jpg"))
        assertEquals(ArtifactPreviewKind.IMAGE, artifactPreviewKind("image/png", "photo.png"))
        assertEquals(ArtifactPreviewKind.IMAGE, artifactPreviewKind("image/gif", "motion.gif"))
        assertEquals(ArtifactPreviewKind.IMAGE, artifactPreviewKind("image/svg+xml", "vector.svg"))
        assertEquals(ArtifactPreviewKind.HTML, artifactPreviewKind("text/html; charset=utf-8", "page.html"))
        assertEquals(ArtifactPreviewKind.HTML, artifactPreviewKind("application/octet-stream", "page.htm"))
        assertEquals(ArtifactPreviewKind.TEXT, artifactPreviewKind("text/plain", "notes.txt"))
    }

    @Test
    fun htmlPreviewOnlyAllowsMemoryResourceSchemes() {
        assertTrue(isAllowedPreviewResourceScheme("data"))
        assertTrue(isAllowedPreviewResourceScheme("ABOUT"))
        assertFalse(isAllowedPreviewResourceScheme("https"))
        assertFalse(isAllowedPreviewResourceScheme("file"))
        assertFalse(isAllowedPreviewResourceScheme("content"))
        assertFalse(isAllowedPreviewResourceScheme(null))
    }

    @Test
    fun htmlSandboxInsertsGuardBeforeOriginalResources() {
        val original = "<html><head><title>demo</title></head><body><img src=\"https://example.com/a.png\"></body></html>"
        val document = sandboxHtmlDocument(original, "#FF000000", "#FFFFFFFF")

        val policyPosition = document.indexOf("Content-Security-Policy")
        val remoteResourcePosition = document.indexOf("https://example.com")
        assertTrue(policyPosition >= 0)
        assertTrue(policyPosition < remoteResourcePosition)
        assertTrue(document.contains("form-action 'none'"))
        assertTrue(document.contains("<title>demo</title>"))
    }
}
