package xyz.chouxuewei.mobile_agent.tools

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.core.ArtifactStatus
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.StorageCleanupResult
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext

@RunWith(AndroidJUnit4::class)
class ImageRenderToolTest {
    @Test
    fun rendersSvgToPngAndAnimatedGif() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artifacts = MemoryArtifactStore()
        val provider = ImageRenderToolProvider(context, artifacts)
        val execution = ToolExecutionContext(
            conversationId = "render-test",
            runId = "run",
            replyMessageId = "reply",
            userRequest = "生成测试图",
            toolCallRecordId = "tool-call",
        )
        val createdFiles = mutableListOf<File>()
        try {
            val pngResult = provider.execute(
                RequestedToolCall(
                    id = "png",
                    toolId = "image_render",
                    argumentsJson = buildJsonObject {
                        put("name", "render-check.png")
                        put("format", "png")
                        put("svg", RED_SVG)
                        put("width", 48)
                        put("height", 32)
                    }.toString(),
                ),
                execution,
            )
            assertFalse(pngResult.isError)
            val png = artifacts.values.value.last()
            createdFiles += File(png.storagePath)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(png.storagePath))
            assertEquals(48, bitmap.width)
            assertEquals(32, bitmap.height)
            bitmap.recycle()

            val gifResult = provider.execute(
                RequestedToolCall(
                    id = "gif",
                    toolId = "image_render",
                    argumentsJson = buildJsonObject {
                        put("name", "render-check.gif")
                        put("format", "gif")
                        put("width", 32)
                        put("height", 32)
                        putJsonArray("frames") {
                            add(buildJsonObject {
                                put("svg", RED_SVG)
                                put("duration_ms", 80)
                            })
                            add(buildJsonObject {
                                put("svg", BLUE_SVG)
                                put("duration_ms", 120)
                            })
                        }
                    }.toString(),
                ),
                execution,
            )
            assertFalse(gifResult.isError)
            val gif = artifacts.values.value.last()
            createdFiles += File(gif.storagePath)
            assertEquals("image/gif", gif.mimeType)
            assertEquals("GIF89a", File(gif.storagePath).inputStream().use { input ->
                val header = ByteArray(6)
                check(input.read(header) == header.size)
                String(header, Charsets.US_ASCII)
            })
            assertTrue(gif.sizeBytes > 20)
            val gifDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(gif.storagePath)))
            assertTrue(gifDrawable is AnimatedImageDrawable)
        } finally {
            createdFiles.forEach(File::delete)
        }
    }

    private class MemoryArtifactStore : ArtifactStore {
        val values = MutableStateFlow<List<Artifact>>(emptyList())
        override fun observeArtifacts(conversationId: String): Flow<List<Artifact>> = values
        override suspend fun artifacts(conversationId: String): List<Artifact> =
            values.value.filter { it.conversationId == conversationId && it.status == ArtifactStatus.AVAILABLE }

        override suspend fun artifact(id: String): Artifact? = values.value.firstOrNull { it.id == id }
        override suspend fun saveArtifact(artifact: Artifact) {
            values.value = values.value + artifact
        }

        override suspend fun deleteArtifact(id: String): Boolean {
            val before = values.value.size
            values.value = values.value.filterNot { it.id == id }
            return values.value.size != before
        }

        override suspend fun cleanup() = StorageCleanupResult()
    }

    private companion object {
        const val RED_SVG =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="#ff0000"/></svg>"""
        const val BLUE_SVG =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="#0000ff"/></svg>"""
    }
}
