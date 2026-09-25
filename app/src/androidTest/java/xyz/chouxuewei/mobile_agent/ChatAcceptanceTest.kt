package xyz.chouxuewei.mobile_agent

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.chat.ComposerDraft
import xyz.chouxuewei.mobile_agent.ui.theme.resolveTheme

/** 只发送此文件中的合成测试文字，不截图上传、不调用设备或 Root 工具。 */
@RunWith(AndroidJUnit4::class)
class ChatAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as PrototypeApplication

    @Test fun realMultiturnOnNonRootAndThreeThemeScreenshots() {
        assertEquals("user",Build.TYPE)
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        // 8192 是本测试主动限定的预算，不声称是远端模型最大窗口。
        runBlocking { app.modelSettings.saveContextPolicy(8192,1024) }
        val previousId=app.chatWorkspace.current.value
        compose.runOnIdle { app.chatWorkspace.newConversation() }
        compose.waitForIdle()
        var id=""
        compose.waitUntil(10000) { app.chatWorkspace.current.value?.takeIf { it!=previousId }?.also { id=it }!=null }
        compose.onNodeWithTag("composer").performTextInput("请记住这个测试约定：项目代号是青竹，预算是800元。只回复一句确认，不调用工具。")
        compose.onNodeWithTag("send").performClick()
        awaitReply(id,1)
        compose.onNodeWithTag("composer").performTextInput("刚才约定的项目代号和预算分别是什么？请简短回答。")
        compose.onNodeWithTag("send").performClick()
        awaitReply(id,2)
        val history=runBlocking { app.conversations.messages(id) }
        val answer=history.last { it.role==MessageRole.ASSISTANT }
        assertEquals(answer.error,MessageStatus.COMPLETE,answer.status)
        assertTrue(answer.text.contains("青竹")); assertTrue(answer.text.contains("800"))
        compose.onNodeWithTag("composer").performImeActionSafely()
        for(theme in listOf(ThemePreference.PAPER,ThemePreference.GRAPHITE,ThemePreference.WARM)) {
            runBlocking { app.appearance.setTheme(theme) }
            compose.onNodeWithTag("chat_root_${theme.name}").assertExists()
            compose.waitForIdle()
            capture("chat-${theme.name.lowercase()}.png")
            assertEquals(history,runBlocking { app.conversations.messages(id) })
        }
        assertUninitialized("deviceGateway\$delegate")
        assertUninitialized("controller\$delegate")
        assertUninitialized("agentRunner\$delegate")
    }

    @Test fun draftAttachmentsAndConversationSurviveThemeAndActivityRecreation() {
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        val id=app.chatWorkspace.current.value!!
        val draft=ComposerDraft("这是未发送的多行草稿\n第二行仍应保留",listOf(AttachmentRef("content://fixture/read-only","未读取的附件.txt","text/plain")))
        compose.runOnIdle { app.chatWorkspace.edit(id,draft) }
        compose.onNodeWithTag("composer").assertTextContains(draft.text)
        runBlocking { app.appearance.setTheme(ThemePreference.WARM) }
        compose.onNodeWithTag("chat_root_WARM").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("composer").assertTextContains(draft.text)
        assertEquals(id,app.chatWorkspace.current.value)
        assertEquals(draft,app.chatWorkspace.drafts.value[id])
        runBlocking { withTimeout(5000) { while(app.conversations.conversation(id)?.draft!=draft.text) delay(25) } }
        assertEquals(draft.attachments,runBlocking { app.conversations.conversation(id) }!!.attachments)
        compose.runOnIdle { app.chatWorkspace.edit(id,ComposerDraft()) }
    }

    @Test fun systemResolutionAndManualPriority() {
        assertEquals(ThemePreference.PAPER,resolveTheme(ThemePreference.SYSTEM,false))
        assertEquals(ThemePreference.GRAPHITE,resolveTheme(ThemePreference.SYSTEM,true))
        assertEquals(ThemePreference.WARM,resolveTheme(ThemePreference.WARM,true))
        assertEquals(ThemePreference.PAPER,resolveTheme(ThemePreference.PAPER,true))
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithTag("menu_settings").performClick()
        compose.onNodeWithTag("appearance").performClick()
        compose.onNodeWithTag("theme_GRAPHITE").performClick()
        // 点击先异步写 DataStore，再由 Flow 刷新根主题；不能把点击返回当作持久化已完成。
        compose.waitUntil(5000) { compose.onAllNodesWithTag("chat_root_GRAPHITE").fetchSemanticsNodes().size==1 }
        compose.onNodeWithTag("chat_root_GRAPHITE").assertExists()
    }

    private fun awaitReply(id: String,count: Int) {
        compose.waitUntil(180000) { runBlocking { app.conversations.messages(id).count { it.role==MessageRole.ASSISTANT && it.status!=MessageStatus.GENERATING }>=count } }
        val last=runBlocking { app.conversations.messages(id).last { it.role==MessageRole.ASSISTANT } }
        assertEquals(last.error,MessageStatus.COMPLETE,last.status)
    }
    private fun capture(name: String) {
        SystemClock.sleep(500) // 等待系统栏和输入法的独立窗口完成重绘，而不只等待 Compose 空闲。
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val screenshot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file=File(app.getExternalFilesDir(null),name)
        file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it)) }
        screenshot.recycle()
    }
    private fun assertUninitialized(field: String) {
        val lazy=app.javaClass.getDeclaredField(field).apply { isAccessible=true }.get(app) as Lazy<*>
        assertFalse("聊天不应初始化 $field",lazy.isInitialized())
    }
    private fun SemanticsNodeInteraction.performImeActionSafely() {
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { !ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.isVisible(WindowInsetsCompat.Type.ime()) }
        SystemClock.sleep(500)
        compose.waitForIdle()
    }
}
