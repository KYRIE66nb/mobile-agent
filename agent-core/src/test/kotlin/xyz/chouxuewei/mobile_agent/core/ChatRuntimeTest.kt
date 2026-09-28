package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class ChatRuntimeTest {
    @Test fun supplementDuringCancellationCleanupDoesNotStartOverlappingRun()=runBlocking {
        val memory=MemoryConversationStore()
        val finishing=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val store=object : ConversationStore by memory {
            // drain 调的是完整签名；只覆写短签名会被 by memory 委托绕过、握手信号永远不来。
            override suspend fun finishRun(
                run: Run,text: String,assistantSteps: List<AssistantStep>,
                reasoningDurationMillis: Long?,status: RunStatus,error: String?,
            ) {
                if(status==RunStatus.CANCELLED) { finishing.complete(Unit); release.await() }
                memory.finishRun(run,text,assistantSteps,reasoningDurationMillis,status,error)
            }
        }
        // Default 而非 Unconfined：Unconfined 让 drain 在测试线程重入恢复，握手Deferred 时序失控成死锁。
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CompletableDeferred<Unit>(); var calls=0
        val gateway=ChatModelGateway { flow {
            calls++; if(calls==1) { started.complete(Unit); awaitCancellation() }
            emit(ModelEvent.TextDelta("下一轮")); emit(ModelEvent.Completed("stop"))
        } }
        val runtime=ChatRuntime(store,{ _ -> ChatConnection(gateway,ContextPolicy(8192,1024),"test") },scope)
        try {
            runtime.send("c","开始",emptyList()); withTimeout(5000) { started.await() }; runtime.stop("c"); withTimeout(5000) { finishing.await() }
            runtime.send("c","停止之后补充",emptyList())
            assertEquals(1,memory.runs.size)
            release.complete(Unit)
            // active 清空早于 beginRun 落库，直接等第二条 run 完成。
            withTimeout(5000) { while(memory.runs.size<2 || memory.runs.last().status==RunStatus.GENERATING) yield() }
            assertEquals(2,memory.runs.size); assertEquals(RunStatus.SUCCEEDED,memory.runs.last().status)
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun stopKeepsPartialAndProcessesQueuedSupplementWithCorrectOrder()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CompletableDeferred<Unit>(); var calls=0
        val requests=mutableListOf<ChatRequest>()
        val gateway=ChatModelGateway { request -> flow {
            requests.add(request); calls++
            if(calls==1) { emit(ModelEvent.TextDelta("部分中文")); started.complete(Unit); awaitCancellation() }
            else { emit(ModelEvent.TextDelta("收到补充")); emit(ModelEvent.Completed("stop")) }
        } }
        val runtime=ChatRuntime(store,{ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },scope)
        try {
            runtime.send("c","第一条",emptyList()); withTimeout(5000) { started.await() }
            runtime.send("c","补充",emptyList()); assertTrue(store.history.any { it.status==MessageStatus.QUEUED })
            runtime.stop("c")
            // 仅等 active 清空有竞态：下一轮可能还没 startLocked。等运行落库完成再断言。
            withTimeout(5000) { while(store.runs.size<2 || store.runs.last().status==RunStatus.GENERATING) yield() }
            assertEquals(listOf("第一条","部分中文","补充","收到补充"),store.history.map { it.text })
            assertEquals(MessageStatus.CANCELLED,store.history[1].status)
            assertTrue(requests.last().messages.any { it.content=="部分中文" })
            assertEquals(2,store.runs.size)
        } finally { scope.cancel() }
    }
    @Test fun configurationFailureIsRecordedWithoutAutomaticReplay()=runBlocking {
        val store=MemoryConversationStore(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val runtime=ChatRuntime(store,{ _ -> error("没有配置") },scope)
        try {
            runtime.send("c","你好",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(1,store.runs.size); assertEquals(RunStatus.FAILED,store.runs.single().status)
            assertFalse(store.history.any { it.status==MessageStatus.QUEUED })
        } finally { scope.cancel() }
    }

    @Test fun recordsLatestReportedUsageOncePerModelRequest()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val recorded=mutableListOf<ModelUsageRecord>()
        val gateway=ChatModelGateway { flow {
            emit(ModelEvent.Usage(10,2))
            emit(ModelEvent.Usage(12,4))
            emit(ModelEvent.TextDelta("完成"))
            emit(ModelEvent.Completed("stop"))
        } }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ ->
                ChatConnection(gateway,ContextPolicy(8192,1024),"remote-model","profile-a","模型 A")
            },
            scope=scope,
            usageRecorder={ recorded += it },
        )
        try {
            runtime.send("c","统计用量",emptyList(),modelProfileId="profile-a")
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(1,recorded.size)
            assertEquals("profile-a",recorded.single().modelProfileId)
            assertEquals("模型 A",recorded.single().modelName)
            assertEquals("remote-model",recorded.single().modelId)
            assertEquals(12,recorded.single().inputTokens)
            assertEquals(4,recorded.single().outputTokens)
        } finally { scope.cancel() }
    }

    @Test fun configuredMaxStepsStopsFurtherToolRounds()=runBlocking {
        val store=MemoryConversationStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var modelRequests=0
        var toolExecutions=0
        val gateway=ChatModelGateway { flow {
            modelRequests++
            emit(ModelEvent.ToolCall(RequestedToolCall("call-$modelRequests","loop_tool","{}")))
            emit(ModelEvent.Completed("tool_calls"))
        } }
        val provider=object : ToolProvider {
            override val id="test"
            override val title="测试"
            override val description="测试步骤限制"
            override val definitions=listOf(ToolDefinition(
                id="loop_tool",
                title="循环工具",
                description="持续请求工具以验证单轮上限",
                inputSchema="""{"type":"object"}""",
                sideEffect=ToolSideEffect.READ,
                providerId=id,
                requiresPermissionApproval=false,
            ))
            override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult {
                toolExecutions++
                return ToolResult("{}","完成")
            }
        }
        val runtime=ChatRuntime(
            store=store,
            connection={ _ -> ChatConnection(gateway,ContextPolicy(16000,1024),"test") },
            scope=scope,
            tools=ToolRegistry(listOf(provider)),
            maxStepsPerRun={ 2 },
        )
        try {
            runtime.send("c","重复调用工具",emptyList())
            withTimeout(3000) { while(runtime.active.value.isNotEmpty()) yield() }
            assertEquals(3,modelRequests)
            assertEquals(2,toolExecutions)
            assertEquals(RunStatus.FAILED,store.runs.single().status)
            assertTrue(store.runs.single().error.orEmpty().contains("单轮最大步骤（2）"))
        } finally { scope.cancel() }
    }
}
