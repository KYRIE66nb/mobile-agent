package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class ContextManagerTest {
    @Test fun automaticSummaryFailureUsesOriginalOnlyBelowHardLimit()=runBlocking {
        val store=MemoryConversationStore().apply { repeat(3) { add(MessageRole.USER,"问题"); add(MessageRole.ASSISTANT,"x".repeat(30)) } }
        val manager=ContextManager(store); val small=ContextPolicy(2048,512)
        val original=manager.estimate(listOf(ChatTurn("system",ContextManager.SYSTEM))+store.history.map { ChatTurn(it.role.name.lowercase(),it.text) })
        val padding=(small.inputBudget*.9).toInt()-original
        assertTrue(padding>0)
        store.history[1]=store.history[1].copy(text=store.history[1].text+"x".repeat(padding))
        var attempts=0
        val turns=manager.prepare("c",Long.MAX_VALUE,small,"test",ChatModelGateway { flow { attempts++; emit(ModelEvent.Error("摘要超时")) } })
        assertEquals(1,attempts); assertNull(store.saved)
        assertEquals(store.history.map { it.text },turns.drop(1).map { it.content })
        assertTrue(manager.estimate(turns)<=small.inputBudget)
    }
    private val policy=ContextPolicy(16000,1024)
    private val summary="目标：保留用户要求\n约束：参考原文\n纠正：按最新修改\n事实：来源 m1\n进展：讨论中\n待办：继续"
    private fun fixture()=MemoryConversationStore().apply {
        repeat(8) { add(MessageRole.USER,if(it==0) "更正：预算不是500，而是800元，必须中文。" else "讨论方案$it")
            add(MessageRole.ASSISTANT,"较早建议："+"旅行规划和说明。".repeat(80)) }
        add(MessageRole.USER,"继续比较方案")
    }
    private fun gateway(onRequest: (ChatRequest)->Unit = {})=ChatModelGateway { request -> flow {
        onRequest(request); emit(ModelEvent.TextDelta(summary)); emit(ModelEvent.Completed("stop"))
    } }

    @Test fun budgetsCountChineseAndUseConfiguredDefaults() {
        val manager=ContextManager(MemoryConversationStore())
        assertTrue(manager.estimate(listOf(ChatTurn("user","你好")))>=6)
        assertEquals(14176,policy.inputBudget)
        assertEquals(DEFAULT_CONTEXT_WINDOW_TOKENS, ContextPolicy().windowTokens)
        assertEquals(DEFAULT_MAX_OUTPUT_TOKENS, ContextPolicy().outputReserve)
        ContextPolicy().validate()
        assertThrows(IllegalArgumentException::class.java) { ContextPolicy(0, 2048).validate() }
    }
    @Test fun personalizationIsAddedToSystemPromptWithoutBecomingConversationHistory()=runBlocking {
        val store=MemoryConversationStore().apply { add(MessageRole.USER,"推荐一份午餐") }
        var reads=0
        val manager=ContextManager(store) {
            reads++
            "我不吃香菜；推荐时优先考虑性价比。"
        }
        val turns=manager.prepare("c",Long.MAX_VALUE,policy,"test",gateway())
        assertEquals(1,reads)
        assertEquals("system",turns.first().role)
        assertTrue(turns.first().content.contains("我不吃香菜"))
        assertEquals(listOf("system","user"),turns.map { it.role })
        assertEquals("推荐一份午餐",turns.last().content)
    }
    @Test fun compactionKeepsCorrectionsRecentMessagesAndAllOriginals()= runBlocking {
        val store=fixture(); val original=store.history.toList(); val manager=ContextManager(store)
        var requests=0
        val turns=manager.prepare("c",Long.MAX_VALUE,policy,"test",gateway { requests++; assertTrue(manager.estimate(it.messages)<=policy.inputBudget) })
        assertNotNull(store.saved); assertTrue(requests>1)
        assertEquals(original,store.history)
        assertTrue(turns.any { it.content.contains("不是500，而是800元") })
        assertEquals("继续比较方案",turns.last().content)
        assertTrue(manager.estimate(turns)<policy.inputBudget*.6)
        assertEquals(store.history.filter { it.sequence<=store.saved!!.boundary }.map { it.id }.toSet(),store.saved!!.sourceVersions.keys)
    }
    @Test fun concurrentAppendStaysBeyondSnapshotAndVersionChangeRejects()=runBlocking {
        val store=fixture(); val through=store.history.last().sequence
        var appended=false
        ContextManager(store).prepare("c",through,policy,"test",gateway {
            if(!appended) { store.add(MessageRole.USER,"压缩时追加的消息"); appended=true }
        })
        assertNotNull(store.saved); assertTrue(store.history.last().sequence>store.saved!!.boundary)
        assertFalse(store.saved!!.sourceVersions.containsKey(store.history.last().id))
        val previous=store.saved
        store.rejectPublication=true
        assertTrue(runCatching { ContextManager(store).prepare("c",Long.MAX_VALUE,policy,"test",gateway(),manual=true) }.isFailure)
        assertEquals(previous,store.saved)
    }
    @Test fun failedSummaryFallsBackOnlyWhenOriginalFits()=runBlocking {
        val store=MemoryConversationStore().apply { repeat(3) { add(MessageRole.USER,"问题"); add(MessageRole.ASSISTANT,"回答") } }
        val bad=ChatModelGateway { flow { emit(ModelEvent.Error("timeout")) } }
        val manager=ContextManager(store)
        assertTrue(runCatching { manager.prepare("c",Long.MAX_VALUE,policy,"test",bad,manual=true) }.isFailure)
        assertNull(store.saved)
        assertEquals(7,manager.prepare("c",Long.MAX_VALUE,policy,"test",bad).size)
        val large=fixture()
        assertTrue(runCatching { ContextManager(large).prepare("c",Long.MAX_VALUE,ContextPolicy(4096,512),"test",bad) }.exceptionOrNull() is ContextBudgetException)
        assertNull(large.saved)
    }
    @Test fun cancellationNeverPublishesPartialSnapshot()=runBlocking {
        val store=fixture()
        val started=CompletableDeferred<Unit>()
        val job=launch { ContextManager(store).prepare("c",Long.MAX_VALUE,policy,"test",ChatModelGateway { flow {
            emit(ModelEvent.TextDelta("目标：尚未完成")); started.complete(Unit); awaitCancellation()
        } }) }
        started.await(); job.cancelAndJoin(); assertNull(store.saved)
    }
    @Test fun repeatedCompactionRebuildsSourcesAndRetrievesExactOriginal()=runBlocking {
        val store=fixture(); val manager=ContextManager(store)
        manager.prepare("c",Long.MAX_VALUE,policy,"test",gateway())
        val firstBoundary=store.saved!!.boundary
        store.add(MessageRole.ASSISTANT,"好的"); store.add(MessageRole.USER,"新的问题"); store.add(MessageRole.ASSISTANT,"新的回答")
        manager.prepare("c",Long.MAX_VALUE,policy,"test",gateway(),manual=true)
        assertTrue(store.saved!!.boundary>firstBoundary)
        store.add(MessageRole.USER,"请引用 m1 的原文，预算是多少？")
        val turns=manager.prepare("c",Long.MAX_VALUE,policy,"test",gateway())
        assertTrue(turns.any { it.content.startsWith("[取回的原始消息") && it.content.contains("800元") })
        store.add(MessageRole.USER,"不可截断的当前请求".repeat(1000))
        assertTrue(runCatching { manager.prepare("c",Long.MAX_VALUE,ContextPolicy(2048,512),"smaller",gateway()) }.isFailure)
    }
}
