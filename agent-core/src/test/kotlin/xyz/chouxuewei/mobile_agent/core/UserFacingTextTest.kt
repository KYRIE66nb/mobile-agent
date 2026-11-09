package xyz.chouxuewei.mobile_agent.core

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserFacingTextTest {
    private lateinit var originalLocale: Locale

    @Before fun useEnglish() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test fun httpErrorsRetainFriendlyExplanationAndSafeDiagnostics() {
        val cases = listOf(
            401 to "The API key is invalid or lacks access.",
            403 to "The API key is invalid or lacks access.",
            404 to "The service or model was not found.",
            408 to "The request timed out.",
            413 to "This message is too large.",
            429 to "Too many requests or insufficient account quota.",
            500 to "The model service is temporarily unavailable.",
            503 to "The model service is temporarily unavailable.",
            599 to "The model service is temporarily unavailable.",
        )
        cases.forEach { (status, explanation) ->
            listOf("(HTTP $status)", "(HTTP $status · server_busy)", "(HTTP $status · 1302)").forEach { diagnostic ->
                val message = userFacingMessage(
                    "java.lang.IllegalStateException: failed $diagnostic\n" +
                        "{\"error\":{\"message\":\"private-server-detail\"}}"
                )
                assertTrue(message, message.startsWith(explanation))
                assertTrue(message, message.endsWith(diagnostic))
                assertFalse(message, message.contains("IllegalStateException"))
                assertFalse(message, message.contains("private-server-detail"))
            }
        }
    }

    @Test fun chineseHttpExplanationAlsoRetainsDiagnostics() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val message = userFacingMessage("模型请求失败 (HTTP 429 · 1302)")
        assertTrue(message, message.startsWith("请求过于频繁或账户额度不足"))
        assertTrue(message, message.endsWith("(HTTP 429 · 1302)"))
    }

    @Test fun httpDiagnosticsRemainStableAcrossRepeatedFormatting() {
        listOf(
            "(HTTP 401)",
            "(HTTP 403 · permission_denied)",
            "(HTTP 404 · model.not-found)",
            "(HTTP 408 · request_timeout)",
            "(HTTP 413 · payload_too_large)",
            "(HTTP 429 · 1302)",
            "(HTTP 503 · server_busy)",
        ).forEach { diagnostic ->
            val formatted = userFacingMessage("模型请求失败 $diagnostic")
            assertTrue(formatted, formatted.endsWith(diagnostic))
            assertEquals(formatted, userFacingMessage(formatted))
            assertEquals(formatted, userFacingMessage(userFacingMessage(formatted)))
        }
    }

    @Test fun unsafeBusinessCodesAreNotShown() {
        listOf("", "unsafe code", "secret\nbody", "<script>", "x".repeat(65)).forEach { unsafeCode ->
            assertEquals(
                "The model service is temporarily unavailable. Please try again later. (HTTP 503)",
                userFacingMessage("java.lang.Exception: private-server-detail (HTTP 503 · $unsafeCode)"),
            )
        }
    }

    @Test fun maximumLengthSafeBusinessCodeIsRetained() {
        val diagnostic = "(HTTP 503 · ${"x".repeat(64)})"
        assertTrue(userFacingMessage("模型请求失败 $diagnostic").endsWith(diagnostic))
    }

    @Test fun unstructuredHttpFailureKeepsOnlyStatusAndExplanation() {
        assertEquals(
            "The API key is invalid or lacks access. Check Model settings. (HTTP 401)",
            userFacingMessage(IllegalStateException("HTTP 401 response contained private-server-detail")),
        )
    }
}
