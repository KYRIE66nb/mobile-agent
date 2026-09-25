package xyz.chouxuewei.mobile_agent.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationMigrationTest {
    @Test fun migrationPreservesTasksAndRestoresChatDraftPartialReplyAndSnapshot() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, instruction TEXT NOT NULL, status TEXT NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, error TEXT)")
            db.execSQL("CREATE TABLE steps (taskId TEXT NOT NULL, stepIndex INTEGER NOT NULL, observationId TEXT NOT NULL, actionJson TEXT, resultJson TEXT, createdAtEpochMillis INTEGER NOT NULL, PRIMARY KEY(taskId,stepIndex), FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_steps_taskId ON steps(taskId)")
            db.execSQL("INSERT INTO tasks VALUES ('old-task','保留旧任务','SUCCEEDED',1,NULL)")
            db.execSQL("INSERT INTO steps VALUES ('old-task',0,'old-observation',NULL,NULL,1)")
            db.version=1
        }
        fun open()=Room.databaseBuilder(context,AgentDatabase::class.java,name)
            .addMigrations(
                CHAT_MIGRATION,
                REASONING_MIGRATION,
                COMPOSER_REASONING_MIGRATION,
                TOOL_CALL_MIGRATION,
                ARTIFACT_MIGRATION,
                CONVERSATION_PIN_MIGRATION,
            ).build()
        var db=open()
        try {
            assertEquals("保留旧任务",db.records().findTask("old-task")!!.instruction)
            assertEquals("old-observation",db.records().steps("old-task").single().observationId)
            var store=RoomConversationStore(db)
            val c=store.createConversation()
            store.setPinned(c.id, true)
            val m=store.enqueue(c.id,"请记住更正后的数字800",emptyList())
            val run=store.beginRun(c.id,m.id,"test")
            store.finishRun(run,"记住了",RunStatus.SUCCEEDED)
            val covered=store.messages(c.id)
            val snap=ContextSnapshot("s1",c.id,covered.last().sequence,covered.associate { it.id to it.version },"目标：测试摘要","test",1000,200,1)
            assertTrue(store.publishSnapshot(snap))
            val second=store.enqueue(c.id,"继续",emptyList())
            val secondRun=store.beginRun(c.id,second.id,"test")
            store.updateReply(secondRun,"部分回复中文")
            val attachment=AttachmentRef("content://fixture/file","清单.txt","text/plain")
            store.saveDraft(c.id,"未发送草稿",listOf(attachment))
            val queued=store.enqueue(c.id,"已排队补充",emptyList())
            store.saveDraft(c.id,"未发送草稿",listOf(attachment))
            db.close(); db=open(); store=RoomConversationStore(db)
            assertTrue(store.conversation(c.id)!!.pinned)
            assertEquals("未发送草稿",store.conversation(c.id)!!.draft)
            assertEquals(listOf(attachment),store.conversation(c.id)!!.attachments)
            assertEquals(snap,store.snapshot(c.id))
            assertEquals("部分回复中文",store.messages(c.id).first { it.id==secondRun.replyMessageId }.text)
            store.recoverInterrupted()
            assertEquals(MessageStatus.INTERRUPTED,store.messages(c.id).first { it.id==queued.id }.status)
            assertEquals(MessageStatus.INTERRUPTED,store.messages(c.id).first { it.id==secondRun.replyMessageId }.status)
            assertTrue(store.publishSnapshot(snap.copy(id="s2",createdAt=2))) // 边界之后追加不影响发布。
            assertFalse(store.publishSnapshot(snap.copy(id="stale",sourceVersions=mapOf(m.id to 999))))
            assertEquals("s2",store.snapshot(c.id)!!.id)
            assertEquals(5,store.messages(c.id).size)
            assertNotNull(db.records().findTask("old-task"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
