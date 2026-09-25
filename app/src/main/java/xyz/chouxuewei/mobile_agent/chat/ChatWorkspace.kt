package xyz.chouxuewei.mobile_agent.chat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.attachments.IncomingShare

data class ComposerDraft(
    val text: String = "",
    val attachments: List<AttachmentRef> = emptyList(),
)

/** UI 状态独立于配色和 Activity；有序写入避免慢磁盘把旧草稿覆盖回去。 */
class ChatWorkspace(private val app: PrototypeApplication) {
    val current = MutableStateFlow<String?>(null)
    val drafts = MutableStateFlow<Map<String, ComposerDraft>>(emptyMap())
    val error = MutableStateFlow<String?>(null)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        app.applicationScope.launch {
            for (command in commands) try {
                command()
            } catch (failure: Exception) {
                error.value = userFacingMessage(failure, "更改未保存，请重试")
            }
        }
        dispatch {
            app.chatRuntime.ready()
            app.modelSettings.seedDebugDefaults()
            val remembered = app.appearance.currentConversation.first()?.let { app.conversations.conversation(it) }
            val conversation = remembered ?: app.conversations.observeConversations().first().firstOrNull() ?: app.conversations.createConversation()
            app.modelSettings.migrateLegacyReasoningEffort(conversation.reasoningEffort)
            activate(conversation)
        }
    }
    private fun dispatch(block: suspend () -> Unit) { commands.trySend(block) }
    private suspend fun activate(c: Conversation) {
        if (drafts.value[c.id] == null) {
            drafts.update {
                it + (c.id to ComposerDraft(c.draft, c.attachments))
            }
        }
        current.value = c.id
        app.appearance.setCurrentConversation(c.id)
    }
    fun select(id: String) = dispatch { app.conversations.conversation(id)?.let { activate(it) } }
    fun newConversation() = dispatch { activate(app.conversations.createConversation()) }
    fun edit(id: String, draft: ComposerDraft) {
        val previousAttachments = drafts.value[id]?.attachments.orEmpty().map(AttachmentRef::uri).toSet()
        drafts.update { it + (id to draft) }
        dispatch {
            // reasoningEffort 参数只为旧数据库结构保留；实际选择已迁到全局模型设置。
            app.conversations.saveDraft(id, draft.text, draft.attachments, null)
            app.attachments.release(draft.attachments)
            if (previousAttachments != draft.attachments.map(AttachmentRef::uri).toSet()) {
                app.attachments.cleanup()
            }
        }
    }
    fun send(id: String, reasoningEffort: String?, modelProfileId: String? = null) {
        val draft = drafts.value[id] ?: return
        val imageCount = draft.attachments.count(AttachmentRef::isImage)
        if (draft.text.isBlank() && imageCount == 0) return
        if (imageCount > 10) {
            error.value = "一次最多发送 10 张图片"
            return
        }
        val cleared = ComposerDraft()
        drafts.update { it + (id to cleared) }
        dispatch {
            try {
                app.chatRuntime.send(id, draft.text, draft.attachments, reasoningEffort, modelProfileId)
                app.attachments.cleanup()
            }
            catch (e: Exception) {
                if (drafts.value[id] == cleared) drafts.update { it + (id to draft) }
                throw e
            }
        }
    }
    fun sendTranscription(id: String, text: String, reasoningEffort: String?, modelProfileId: String?) {
        if (text.isBlank()) return
        val currentDraft = drafts.value[id] ?: ComposerDraft()
        val mergedText = listOf(currentDraft.text.trim(), text.trim())
            .filter(String::isNotBlank)
            .joinToString("\n")
        edit(id, currentDraft.copy(text = mergedText))
        send(id, reasoningEffort, modelProfileId)
    }
    fun sendTranscriptionToNewConversation(text: String, reasoningEffort: String?, modelProfileId: String?) {
        if (text.isBlank()) return
        val normalized = text.trim()
        dispatch {
            // 只在转写成功后创建会话；发送失败时保留文字草稿，避免语音内容丢失。
            val conversation = app.conversations.createConversation()
            activate(conversation)
            val pendingDraft = ComposerDraft(text = normalized)
            drafts.update { it + (conversation.id to pendingDraft) }
            app.conversations.saveDraft(conversation.id, normalized, emptyList(), null)
            app.chatRuntime.send(
                conversation.id,
                normalized,
                emptyList(),
                reasoningEffort,
                modelProfileId,
            )
            drafts.update { it + (conversation.id to ComposerDraft()) }
        }
    }
    fun acceptShare(targetId: String?, share: IncomingShare) {
        if (app.attachments.takeForDraft(share.id) == null) return
        dispatch {
            try {
                val conversation = if (targetId == null) {
                    app.conversations.createConversation()
                } else {
                    app.conversations.conversation(targetId) ?: error("找不到目标对话，请重新选择")
                }
                val existing = drafts.value[conversation.id]
                    ?: ComposerDraft(conversation.draft, conversation.attachments)
                val mergedText = listOf(existing.text.trim(), share.text.trim())
                    .filter(String::isNotBlank).joinToString("\n")
                val merged = ComposerDraft(
                    text = mergedText,
                    attachments = (existing.attachments + share.attachments).distinctBy(AttachmentRef::uri),
                )
                drafts.update { it + (conversation.id to merged) }
                app.conversations.saveDraft(conversation.id, merged.text, merged.attachments, null)
                app.attachments.release(share.attachments)
                activate(conversation)
            } catch (failure: Exception) {
                app.attachments.restore(share)
                throw failure
            }
        }
    }
    fun dismissShare(share: IncomingShare) {
        if (app.attachments.dismiss(share.id) == null) return
        app.applicationScope.launch { app.attachments.cleanup() }
    }
    fun stop(id: String) { app.applicationScope.launch { app.chatRuntime.stop(id) } }
    fun compact(id: String) { app.applicationScope.launch { app.chatRuntime.compact(id) } }
    fun rename(id: String, title: String) = dispatch { app.conversations.rename(id, title) }
    fun setPinned(id: String, pinned: Boolean) = dispatch { app.conversations.setPinned(id, pinned) }
    fun deleteConversation(id: String) = dispatch {
        require(id !in app.chatRuntime.active.value) { "这段对话正在回复，请停止后再删除" }
        val deletingCurrent = current.value == id
        app.conversations.deleteConversation(id)
        drafts.update { it - id }
        if (deletingCurrent) {
            val replacement = app.conversations.observeConversations().first().firstOrNull()
                ?: app.conversations.createConversation()
            activate(replacement)
        }
        // 删除记录已完成；没有其他运行时再立即回收失去引用的附件和产物文件。
        if (app.chatRuntime.active.value.isEmpty()) runCatching { app.cleanupStorage() }
    }
}
