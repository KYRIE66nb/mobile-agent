package xyz.chouxuewei.mobile_agent.data

import org.junit.Assert.*
import org.junit.Test

class ConversationBackupTest {

    private fun bundle() = ConversationBackup.Bundle(
        conversations = listOf(
            ConversationEntity(
                id = "c1", title = "测试对话", createdAt = 1000L, updatedAt = 2000L,
                draft = "草稿", attachments = "[]", reasoningEffort = "high", pinned = true,
            ),
        ),
        messages = listOf(
            MessageEntity(
                id = "m1", conversationId = "c1", sequence = 2, role = "USER",
                text = "你好", status = "COMPLETE", createdAt = 1500L,
                attachments = "[]", version = 3, error = null,
                reasoning = "", reasoningDurationMillis = null,
            ),
            MessageEntity(
                id = "m2", conversationId = "c1", sequence = 3, role = "ASSISTANT",
                text = "回复\"引号\"与\n换行", status = "COMPLETE", createdAt = 1600L,
                attachments = "[{\"uri\":\"content://x\"}]", version = 1, error = "err",
                reasoning = "思考", reasoningDurationMillis = 500L,
            ),
        ),
        runs = listOf(
            RunEntity("r1", "c1", "m1", "m2", "COMPLETE", "glm-4.6", 1500L, 1600L, null),
        ),
        toolCalls = listOf(
            ToolCallEntity("t1", "c1", "r1", "m2", "file_read", "{\"path\":\"a\"}",
                "COMPLETE", "result", "摘要", null, 1550L, 1560L),
        ),
        snapshots = listOf(
            SnapshotEntity("s1", "c1", 3, "{\"m1\":1}", "总结", "glm-4.6", 8000, 300, 1700L, 1),
        ),
    )

    @Test fun `encode decode roundtrip preserves every entity field`() {
        val restored = ConversationBackup.decode(ConversationBackup.encode(bundle()))
        assertEquals(bundle(), restored)
    }

    @Test fun `decode rejects foreign json`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBackup.decode("""{"foo":1}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBackup.decode("not json")
        }
    }

    @Test fun `decode rejects newer version`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBackup.decode("""{"format":"mobile-agent-backup","version":999}""")
        }
    }

    @Test fun `decode tolerates unknown keys and missing optional rows`() {
        val raw = """{"format":"mobile-agent-backup","version":1,"future":"field",
            "conversations":[{"id":"c9","title":"t","createdAt":1,"updatedAt":2,"extra":true}]}"""
        val restored = ConversationBackup.decode(raw)
        assertEquals(1, restored.conversations.size)
        assertEquals("c9", restored.conversations.single().id)
        assertTrue(restored.messages.isEmpty() && restored.runs.isEmpty())
    }

    @Test fun `decode drops malformed rows but keeps valid ones`() {
        val raw = """{"format":"mobile-agent-backup","version":1,
            "conversations":[
                {"id":"ok","title":"t","createdAt":1,"updatedAt":2},
                {"title":"missing id"}
            ]}"""
        assertEquals(listOf("ok"), ConversationBackup.decode(raw).conversations.map { it.id })
    }
}
