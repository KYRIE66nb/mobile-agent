package xyz.chouxuewei.mobile_agent

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.ThemePreference
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

@RunWith(AndroidJUnit4::class)
class ChatDisplayTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as PrototypeApplication
    private fun shell(command: String)=ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Test fun followsActualSystemNightModeButManualThemeWins() {
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        val old=shell("cmd uimode night")
        try {
            runBlocking { app.appearance.setTheme(ThemePreference.SYSTEM) }
            shell("cmd uimode night yes")
            compose.waitUntil(5000) { compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
            SystemClock.sleep(300)
            assertEquals(Color(0xFF151719),compose.onNodeWithTag("chat_root_SYSTEM").captureToImage().toPixelMap()[5,5])
            runBlocking { app.appearance.setTheme(ThemePreference.WARM) }
            shell("cmd uimode night no")
            compose.waitUntil(5000) { compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO }
            assertEquals(Color(0xFFFAF7F0),compose.onNodeWithTag("chat_root_WARM").captureToImage().toPixelMap()[5,5])
            runBlocking { app.appearance.setTheme(ThemePreference.SYSTEM) }
            assertEquals(Color(0xFFFCFCFC),compose.onNodeWithTag("chat_root_SYSTEM").captureToImage().toPixelMap()[5,5])
        } finally { shell("cmd uimode night ${if(old.contains("yes")) "yes" else if(old.contains("auto")) "auto" else "no"}") }
    }

    @Test fun largeFontAndKeyboardKeepComposerAndTopNavigationVisible() {
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        val old=Settings.System.getFloat(app.contentResolver,Settings.System.FONT_SCALE,1f)
        try {
            shell("settings put system font_scale 1.5")
            compose.waitUntil(5000) { compose.activity.resources.configuration.fontScale>=1.49f }
            compose.onNodeWithTag("composer").performTextReplacement("大字体测试第一行\n第二行\n第三行\n第四行\n第五行\n第六行\n第七行可以滚动")
            compose.onNodeWithTag("composer").performClick()
            SystemClock.sleep(700)
            compose.onNodeWithTag("send").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("历史会话").assertIsDisplayed()
            val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(app.getExternalFilesDir(null),"chat-large-font-keyboard.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        } finally { shell("settings put system font_scale $old") }
    }
}
