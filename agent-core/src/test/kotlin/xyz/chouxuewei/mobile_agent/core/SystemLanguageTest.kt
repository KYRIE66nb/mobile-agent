package xyz.chouxuewei.mobile_agent.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemLanguageTest {
    @Test
    fun `Chinese language variants use Chinese text`() {
        assertEquals("中文", localizedText("中文", "English", Locale.SIMPLIFIED_CHINESE))
        assertEquals("中文", localizedText("中文", "English", Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun `non-Chinese languages fall back to English`() {
        assertEquals("English", localizedText("中文", "English", Locale.ENGLISH))
        assertEquals("English", localizedText("中文", "English", Locale.JAPANESE))
    }
}

