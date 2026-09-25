package xyz.chouxuewei.mobile_agent.core

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal class MemoryConversationStore : ConversationStore {
    val history = mutableListOf<Message>()
    val runs = mutableListOf<Run>()
    var saved: ContextSnapshot? = null
    var rejectPublication = false
    var conversation = Conversation("c", createdAt=1)
    override fun observeConversations() = flowOf(listOf(conversation))
    override fun observeMessages(conversationId: String) = flowOf(history.toList())
    override suspend fun createConversation() = conversation
    override suspend fun conversation(id: String) = conversation
    override suspend fun rename(id: String, title: String) { conversation=conversation.copy(title=title) }
    override suspend fun setPinned(id: String, pinned: Boolean) { conversation=conversation.copy(pinned=pinned) }
    override suspend fun deleteConversation(id: String) { history.clear() }
    override suspend fun saveDraft(id: String,text: String,attachments: List<AttachmentRef>,reasoningEffort: String?) {
        conversation=conversation.copy(draft=text,attachments=attachments,reasoningEffort=reasoningEffort)
    }
    override suspend fun messages(id: String) = history.toList()
    override suspend fun enqueue(id: String,text: String,attachments: List<AttachmentRef>): Message {
        val m=Message(UUID.randomUUID().toString(),id,(history.maxOfOrNull { it.sequence } ?: 0)+2,MessageRole.USER,text,MessageStatus.QUEUED,1,attachments)
        history.add(m); return m
    }
    override suspend fun beginRun(id: String,triggerId: String,model: String): Run {
        val index=history.indexOfFirst { it.id==triggerId }
        val user=history[index]; history[index]=user.copy(status=MessageStatus.COMPLETE,version=user.version+1)
        val reply=Message(UUID.randomUUID().toString(),id,user.sequence+1,MessageRole.ASSISTANT,"",MessageStatus.GENERATING,1)
        history.add(reply); history.sortBy { it.sequence }
        return Run(UUID.randomUUID().toString(),id,triggerId,reply.id,RunStatus.GENERATING,model,1).also { runs.add(it) }
    }
    override suspend fun updateReply(run: Run,text: String,assistantSteps: List<AssistantStep>) {
        val i=history.indexOfFirst { it.id==run.replyMessageId }
        history[i]=history[i].copy(
            text=text,
            reasoningSteps=assistantSteps.map(AssistantStep::reasoning).filter(String::isNotBlank),
            assistantSteps=assistantSteps,
            version=history[i].version+1,
        )
    }
    override suspend fun setRunModel(run: Run,model: String) { runs[runs.indexOfFirst { it.id==run.id }]=run.copy(model=model) }
    override suspend fun finishRun(run: Run,text: String,assistantSteps: List<AssistantStep>,reasoningDurationMillis: Long?,status: RunStatus,error: String?) {
        updateReply(run,text,assistantSteps)
        val i=history.indexOfFirst { it.id==run.replyMessageId }
        history[i]=history[i].copy(status=when(status) { RunStatus.SUCCEEDED->MessageStatus.COMPLETE; RunStatus.CANCELLED->MessageStatus.CANCELLED; else->MessageStatus.FAILED },error=error,reasoningDurationMillis=reasoningDurationMillis)
        runs[runs.indexOfFirst { it.id==run.id }]=run.copy(status=status,error=error)
    }
    override suspend fun recoverInterrupted() { }
    override suspend fun snapshot(id: String) = saved
    override suspend fun publishSnapshot(snapshot: ContextSnapshot): Boolean {
        if(rejectPublication || history.filter { it.sequence<=snapshot.boundary }.associate { it.id to it.version }!=snapshot.sourceVersions) return false
        saved=snapshot; return true
    }
    fun add(role: MessageRole,text: String): Message {
        val next=(history.maxOfOrNull { it.sequence } ?: 0)+1
        return Message("m$next","c",next,role,text,MessageStatus.COMPLETE,1).also { history.add(it) }
    }
}
