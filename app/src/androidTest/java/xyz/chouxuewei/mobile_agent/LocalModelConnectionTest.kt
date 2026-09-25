package xyz.chouxuewei.mobile_agent

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.ModelConfig
import xyz.chouxuewei.mobile_agent.core.ModelProbeResult
import xyz.chouxuewei.mobile_agent.core.Observation
import xyz.chouxuewei.mobile_agent.core.Screenshot
import xyz.chouxuewei.mobile_agent.core.Viewport
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 真机经局域网访问模型，发送真实主屏截图，但不会执行模型返回的动作。 */
@RunWith(AndroidJUnit4::class)
class LocalModelConnectionTest {
    @Test
    fun phoneSendsRealScreenshotAndReceivesValidDecision() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
            as PrototypeApplication
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val observation = Observation(
            id = "instrumentation-screen",
            sessionId = "model-probe",
            capturedAtEpochMillis = System.currentTimeMillis(),
            contentRevision = 1,
            viewport = Viewport(bitmap.width, bitmap.height),
            rotationDegrees = 0,
            foregroundPackage = null,
            screenshot = Screenshot(bytes, "image/png", bitmap.width, bitmap.height),
            nodes = emptyList(),
        )

        val result = withTimeout(180_000) {
            app.modelGatewayFactory.probe(
                ModelConfig(BuildConfig.MODEL_BASE_URL, BuildConfig.MODEL_NAME, BuildConfig.MODEL_API_KEY),
                observation,
            )
        }
        assertTrue(
            if (result is ModelProbeResult.Failed) result.reason else result.toString(),
            result is ModelProbeResult.Connected && result.screenshotIncluded,
        )
    }
}
