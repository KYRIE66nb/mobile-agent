package xyz.chouxuewei.mobile_agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 用合成历史验证真实的前端配置 → 手动压缩 → 查看摘要/原文路径。 */
@RunWith(AndroidJUnit4::class)
class ChatContextUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as PrototypeApplication

    @Test fun configureBudgetThenManuallyCompactAndInspectSources() {
        compose.waitUntil(10000) { app.chatWorkspace.current.value!=null }
        val c=runBlocking {
            app.conversations.createConversation().also { conversation ->
                app.conversations.rename(conversation.id,"手动压缩验收（合成记录）")
                repeat(4) { n ->
                    val m=app.conversations.enqueue(conversation.id,if(n==0) "更正：项目是青竹，预算不是500元而是800元。必须中文。" else "继续讨论第${n+1}步",emptyList())
                    val run=app.conversations.beginRun(conversation.id,m.id,"synthetic-fixture-not-model")
                    app.conversations.finishRun(run,"合成验收材料：整理资料、保留来源、每周复盘。方案未执行，预算遵循用户要求。",RunStatus.SUCCEEDED)
                }
            }
        }
        compose.runOnIdle { app.chatWorkspace.select(c.id) }
        compose.waitUntil(10000) { app.chatWorkspace.current.value==c.id }
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithTag("menu_settings").performClick()
        compose.onNodeWithText("模型服务 →",substring=true).performClick()
        // 这些是本次验收主动限定的预算，不宣称为远端服务的最大能力。
        compose.onNodeWithTag("model_window").performScrollTo().performTextReplacement("16384")
        compose.onNodeWithTag("model_output").performScrollTo().performTextReplacement("6144")
        // 设置位于独立的 ModalBottomSheet 窗口，关闭其焦点窗口的键盘，而不是主 Activity 的键盘。
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("保存模型").performScrollTo().performClick()
        compose.waitUntil(5000) {
            runBlocking { app.modelSettings.settings.first().selectedModel?.outputReserve == 6144 }
        }
        assertEquals(ContextPolicy(16384,6144),runBlocking { app.modelSettings.contextPolicy() })
        pressBack()
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithText("压缩上下文",useUnmergedTree=true).performClick()
        compose.waitUntil(180000) { runBlocking { app.conversations.snapshot(c.id)!=null } || app.chatRuntime.notices.value[c.id]?.contains("未完成")==true }
        val snapshot=runBlocking { app.conversations.snapshot(c.id) }
        assertNotNull(app.chatRuntime.notices.value[c.id],snapshot)
        assertEquals(8,runBlocking { app.conversations.messages(c.id).size })
        compose.onNodeWithContentDescription("会话菜单").performClick()
        compose.onNodeWithText("查看上下文摘要").performClick()
        compose.onNodeWithText("覆盖到消息序号",substring=true).assertExists()
        compose.onAllNodesWithText("查看原文",substring=true)[0].performScrollTo().performClick()
        compose.onNode(hasText("更正：项目是青竹，预算不是500元而是800元。必须中文。") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("返回摘要").performClick()
        compose.onNodeWithText("关闭",useUnmergedTree=true).performClick()
    }
}
