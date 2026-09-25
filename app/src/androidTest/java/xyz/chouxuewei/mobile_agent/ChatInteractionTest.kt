package xyz.chouxuewei.mobile_agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.chat.ComposerDraft
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

@RunWith(AndroidJUnit4::class)
class ChatInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as PrototypeApplication

    @Test fun readingHistoryAndComposerFocusSurviveThemeChange() {
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        val id=runBlocking {
            val c=app.conversations.createConversation()
            repeat(20) { n ->
                val m=app.conversations.enqueue(c.id,"本地测试用户$n",emptyList())
                val run=app.conversations.beginRun(c.id,m.id,"synthetic-ui-fixture")
                app.conversations.finishRun(run,"用于检查滚动位置的合成消息 $n",RunStatus.SUCCEEDED)
            }
            c.id
        }
        compose.runOnIdle { app.chatWorkspace.select(id) }
        compose.waitUntil(5000) { app.chatWorkspace.current.value==id }
        compose.onNodeWithTag("composer").performTextInput("保留焦点的草稿")
        compose.onNodeWithTag("messages").performScrollToIndex(4)
        val top=compose.onNodeWithText("本地测试用户2").fetchSemanticsNode().boundsInRoot.top
        runBlocking { app.appearance.setTheme(ThemePreference.PAPER) }
        compose.onNodeWithTag("chat_root_PAPER").assertExists()
        assertEquals(top,compose.onNodeWithText("本地测试用户2").fetchSemanticsNode().boundsInRoot.top,1f)
        compose.onNodeWithTag("composer").assertIsFocused().assertTextContains("保留焦点的草稿")
    }

    @Test fun realInFlightReplySurvivesThemeSwitchAndCanStop() {
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        val id=runBlocking {
            app.modelSettings.saveContextPolicy(8192,2048)
            app.conversations.createConversation().id
        }
        compose.runOnIdle { app.chatWorkspace.select(id) }
        compose.waitUntil(5000) { app.chatWorkspace.current.value==id }
        compose.onNodeWithTag("composer").performTextInput("请用中文写出三十条关于整理读书笔记的具体建议，每条一句话。")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10000) { id in app.chatRuntime.active.value }
        compose.runOnIdle { app.chatWorkspace.edit(id,ComposerDraft("执行中的未发送草稿")) }
        runBlocking { app.appearance.setTheme(ThemePreference.GRAPHITE) }
        compose.onNodeWithTag("chat_root_GRAPHITE").assertExists()
        compose.onNodeWithTag("composer").assertTextContains("执行中的未发送草稿")
        compose.onNodeWithContentDescription("停止回复").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { id !in app.chatRuntime.active.value }
        val replies=runBlocking { app.conversations.messages(id).filter { it.role==MessageRole.ASSISTANT } }
        assertEquals(1,replies.size)
        assertEquals(MessageStatus.CANCELLED,replies.single().status)
        assertEquals("执行中的未发送草稿",app.chatWorkspace.drafts.value[id]!!.text)
    }
}
