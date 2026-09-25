package xyz.chouxuewei.mobile_agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 分两次独立 instrumentation 进程运行，中间由验收脚本 force-stop；不以 Activity 重建代替冷启动。 */
@RunWith(AndroidJUnit4::class)
class ChatColdStartTest {
    @Test fun seedOrVerifyColdRestart()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PrototypeApplication
        val marker=app.getSharedPreferences("cold-start-acceptance",0)
        app.chatRuntime.ready()
        if(InstrumentationRegistry.getArguments().getString("mode")=="seed") {
            val c=app.conversations.createConversation()
            app.conversations.rename(c.id,"冷启动验收（合成中断）")
            val m=app.conversations.enqueue(c.id,"合成的中断测试",emptyList())
            val run=app.conversations.beginRun(c.id,m.id,"synthetic-interrupted")
            app.conversations.updateReply(run,"已生成的部分中文")
            app.conversations.enqueue(c.id,"进程结束前的排队补充",emptyList())
            app.conversations.saveDraft(c.id,"冷启动草稿",listOf(AttachmentRef("content://fixture/cold","冷启动.txt","text/plain")))
            app.appearance.setCurrentConversation(c.id); app.appearance.setTheme(ThemePreference.WARM)
            assertTrue(marker.edit().putString("id",c.id).commit())
        } else {
            val id=requireNotNull(marker.getString("id",null))
            val c=requireNotNull(app.conversations.conversation(id))
            assertEquals("冷启动草稿",c.draft); assertEquals("冷启动.txt",c.attachments.single().name)
            assertEquals(id,app.appearance.currentConversation.first())
            assertEquals(ThemePreference.WARM,app.appearance.theme.first())
            val messages=app.conversations.messages(id)
            assertEquals(3,messages.size)
            assertEquals("已生成的部分中文",messages[1].text)
            assertEquals(MessageStatus.INTERRUPTED,messages[1].status)
            assertEquals(MessageStatus.INTERRUPTED,messages[2].status)
            assertTrue(app.chatRuntime.active.value.isEmpty())
        }
    }
}
