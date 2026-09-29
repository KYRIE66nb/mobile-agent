package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DatabaseProvider {
    @Volatile private var instance: AgentDatabase? = null
    fun get(context: Context): AgentDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "mobile-agent.db")
            .addMigrations(
                CHAT_MIGRATION,
                REASONING_MIGRATION,
                COMPOSER_REASONING_MIGRATION,
                TOOL_CALL_MIGRATION,
                ARTIFACT_MIGRATION,
                CONVERSATION_PIN_MIGRATION,
                DECISION_AUDIT_MIGRATION,
            )
            .build().also { instance = it }
    }
}

/** 只添加聊天表，原 tasks/steps 的结构和数据完全不动；不启用破坏性回退。 */
val CHAT_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, draft TEXT NOT NULL, attachments TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, attachments TEXT NOT NULL, version INTEGER NOT NULL, error TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_conversationId_sequence ON messages(conversationId, sequence)")
        db.execSQL("CREATE TABLE IF NOT EXISTS runs (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, triggerMessageId TEXT NOT NULL, replyMessageId TEXT NOT NULL, status TEXT NOT NULL, model TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, error TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runs_conversationId ON runs(conversationId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS context_snapshots (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, boundary INTEGER NOT NULL, sourceVersions TEXT NOT NULL, summary TEXT NOT NULL, model TEXT NOT NULL, inputTokensBefore INTEGER NOT NULL, inputTokensAfter INTEGER NOT NULL, createdAt INTEGER NOT NULL, formatVersion INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_context_snapshots_conversationId ON context_snapshots(conversationId)")
    }
}

/** 思考流与最终正文分别保存，页面折叠或进程重启后仍可恢复。 */
val REASONING_MIGRATION = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoningDurationMillis INTEGER")
    }
}

/** 每个会话保存输入区选择的思考强度，重新打开应用后恢复原选择。 */
val COMPOSER_REASONING_MIGRATION = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT")
    }
}

/** 工具调用先于执行落盘，进程中断后保留事实但不自动重放外部动作。 */
val TOOL_CALL_MIGRATION = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tool_calls (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, runId TEXT NOT NULL, replyMessageId TEXT NOT NULL, toolId TEXT NOT NULL, argumentsJson TEXT NOT NULL, status TEXT NOT NULL, result TEXT, displaySummary TEXT, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_calls_conversationId ON tool_calls(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_calls_runId ON tool_calls(runId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_calls_replyMessageId ON tool_calls(replyMessageId)")
    }
}

/** 产物表只保存索引与稳定 URI，文件正文继续留在应用受控目录。 */
val ARTIFACT_MIGRATION = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS artifacts (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, runId TEXT NOT NULL, replyMessageId TEXT NOT NULL, sourceToolCallId TEXT, name TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, contentUri TEXT NOT NULL, storagePath TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_conversationId ON artifacts(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_replyMessageId ON artifacts(replyMessageId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_sourceToolCallId ON artifacts(sourceToolCallId)")
    }
}

/** 置顶只改变历史列表顺序，不修改会话内容或最近更新时间。 */
val CONVERSATION_PIN_MIGRATION = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}

/** 专用决策后端的审计表：只存裁决元数据与请求哈希，不落请求原文/密钥。 */
val DECISION_AUDIT_MIGRATION = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS decision_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, backend TEXT NOT NULL, mode TEXT NOT NULL, purpose TEXT NOT NULL, requestHash TEXT NOT NULL, requestId TEXT NOT NULL, runId TEXT, toolCallId TEXT, observationId TEXT, candidateId TEXT, confidence REAL, probabilities TEXT NOT NULL, requestedModel TEXT, returnedModel TEXT, usageInputTokens INTEGER, usageOutputTokens INTEGER, verdict TEXT NOT NULL, fallbackReason TEXT, latencyMillis INTEGER NOT NULL, keyGeneration INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, policyVersion INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decision_records_createdAtEpochMillis ON decision_records(createdAtEpochMillis)")
    }
}
