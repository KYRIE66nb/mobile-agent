package xyz.chouxuewei.mobile_agent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

/** RunPolicy/ScopeGate 在真实运行时管线上的集成行为：工具表过滤、免审批、闸拒绝都不经过审批界面。 */
class RunPolicyIntegrationTest {

    private fun definition(
        id: String,
        sideEffect: ToolSideEffect,
        userChoices: List<ToolInvocationChoice> = emptyList(),
    ) = ToolDefinition(
        id = id, title = id, description = id, inputSchema = "{}",
        sideEffect = sideEffect, providerId = "fake", userChoices = userChoices,
    )

    private class FakeProvider(override val definitions: List<ToolDefinition>) : ToolProvider {
        override val id = "fake"; override val title = "fake"; override val description = "fake"
        val executed = CopyOnWriteArrayList<String>()
        override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext) =
            ToolResult("{\"ok\":true}", "done").also { executed += call.toolId }
    }

    /** 默认权限是每次调用前批准：能走通即证明自动审批通道替代了审批界面。 */
    private val permissionStore = object : ToolPermissionStore {
        override val accesses = flowOf(emptyMap<String, ToolAccess>())
        override suspend fun access(capabilityId: String) = ToolAccess()
        override suspend fun setEnabled(capabilityId: String, enabled: Boolean) = Unit
        override suspend fun setPermission(capabilityId: String, mode: ToolPermissionMode) = Unit
    }

    private class RecordingStore(val memory: MemoryConversationStore) : ConversationStore by memory {
        val records = CopyOnWriteArrayList<ToolCallRecord>()
        override suspend fun saveToolCall(call: ToolCallRecord) { records += call }
        override suspend fun updateToolCall(call: ToolCallRecord) {
            val i = records.indexOfFirst { it.id == call.id }
            if (i >= 0) records[i] = call else records += call
        }
    }

    private fun runtime(
        store: RecordingStore,
        provider: FakeProvider,
        gateway: ChatModelGateway,
        scope: CoroutineScope,
    ) = ChatRuntime(
        store,
        { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
        scope,
        tools = ToolRegistry(listOf(provider)),
        toolPermissions = permissionStore,
    )

    private suspend fun awaitTerminal(memory: MemoryConversationStore) {
        withTimeout(5000) {
            while (memory.runs.isEmpty() || memory.runs.last().status == RunStatus.GENERATING) yield()
        }
    }

    @Test fun policyFiltersToolTableAndOutOfScopeCallNeverExecutes() = runBlocking {
        val provider = FakeProvider(listOf(
            definition("fake_read", ToolSideEffect.READ),
            definition("fake_write", ToolSideEffect.EXTERNAL_WRITE),
        ))
        val memory = MemoryConversationStore(); val store = RecordingStore(memory)
        var emitted = false
        val seenTools = CopyOnWriteArrayList<List<String>>()
        val gateway = ChatModelGateway { request -> flow {
            seenTools += request.tools.map { it.id }
            if (!emitted) {
                emitted = true
                emit(ModelEvent.ToolCall(RequestedToolCall("call-1", "fake_write", "{}")))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = RunPolicy(
            allowedToolIds = setOf("fake_read"),
            gate = ScopeGate(setOf("fake_read"), "测试"),
        )
        try {
            runtime(store, provider, gateway, scope).send("c", "做点什么", emptyList(), policy = policy)
            awaitTerminal(memory)
            assertTrue(seenTools.isNotEmpty()); assertTrue(seenTools.all { it == listOf("fake_read") })
            assertTrue(provider.executed.isEmpty())
            assertEquals(ToolCallStatus.FAILED, store.records.single { it.toolId == "fake_write" }.status)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
        } finally { scope.cancel() }
    }

    @Test fun inScopeSideEffectAutoApprovesWithoutApprovalWaiter() = runBlocking {
        val provider = FakeProvider(listOf(definition("fake_write", ToolSideEffect.EXTERNAL_WRITE)))
        val memory = MemoryConversationStore(); val store = RecordingStore(memory)
        var emitted = false
        val gateway = ChatModelGateway { flow {
            if (!emitted) {
                emitted = true
                emit(ModelEvent.ToolCall(RequestedToolCall("call-1", "fake_write", "{}")))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = runtime(store, provider, gateway, scope)
        val policy = RunPolicy(
            allowedToolIds = setOf("fake_write"),
            gate = ScopeGate(setOf("fake_write"), "测试"),
        )
        try {
            runtime.send("c", "写入", emptyList(), policy = policy)
            awaitTerminal(memory)
            assertEquals(listOf("fake_write"), provider.executed.toList())
            assertEquals(ToolCallStatus.SUCCEEDED, store.records.single { it.toolId == "fake_write" }.status)
            assertTrue(runtime.approvals.value.isEmpty())
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
        } finally { scope.cancel() }
    }

    @Test fun gateConfirmIsDeniedRatherThanParkedInUnattendedRun() = runBlocking {
        val provider = FakeProvider(listOf(definition("fake_write", ToolSideEffect.EXTERNAL_WRITE)))
        val memory = MemoryConversationStore(); val store = RecordingStore(memory)
        var emitted = false
        val gateway = ChatModelGateway { flow {
            if (!emitted) {
                emitted = true
                emit(ModelEvent.ToolCall(RequestedToolCall("call-1", "fake_write", "{}")))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = RunPolicy(
            allowedToolIds = setOf("fake_write"),
            gate = object : DecisionGate {
                override suspend fun gate(request: GateRequest) = GateVerdict.Confirm("需要人工确认")
            },
        )
        try {
            runtime(store, provider, gateway, scope).send("c", "写入", emptyList(), policy = policy)
            awaitTerminal(memory)
            assertTrue(provider.executed.isEmpty())
            assertEquals(ToolCallStatus.DENIED, store.records.single { it.toolId == "fake_write" }.status)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
        } finally { scope.cancel() }
    }

    @Test fun forbiddenScopeToolStaysUnavailableAfterSanitization() = runBlocking {
        val provider = FakeProvider(listOf(
            definition("history_search", ToolSideEffect.READ),
            definition("trigger_save", ToolSideEffect.LOCAL_WRITE),
        ))
        val memory = MemoryConversationStore(); val store = RecordingStore(memory)
        var emitted = false
        val gateway = ChatModelGateway { flow {
            if (!emitted) {
                emitted = true
                emit(ModelEvent.ToolCall(RequestedToolCall("call-1", "trigger_save", "{}")))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 与 TriggerManager.execute 相同的兜底：scope 减去禁名单后再交给运行时。
        val scopeIds = (setOf("history_search") + "trigger_save") - TriggerSpec.FORBIDDEN_SCOPE_TOOLS
        val policy = RunPolicy(allowedToolIds = scopeIds, gate = ScopeGate(scopeIds, "测试"))
        try {
            runtime(store, provider, gateway, scope).send("c", "试着自己再建一个触发器", emptyList(), policy = policy)
            awaitTerminal(memory)
            assertTrue(provider.executed.isEmpty())
            assertEquals(ToolCallStatus.FAILED, store.records.single { it.toolId == "trigger_save" }.status)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
        } finally { scope.cancel() }
    }

    @Test fun userChoicesResolveToFirstChoiceUnderAutoApprove() = runBlocking {
        val choice = ToolInvocationChoice("opt-1", "选项一", "第一个", "{\"mode\":\"a\"}")
        val provider = FakeProvider(listOf(
            definition("fake_write", ToolSideEffect.EXTERNAL_WRITE, listOf(choice)),
        ))
        val memory = MemoryConversationStore(); val store = RecordingStore(memory)
        var emitted = false
        val gateway = ChatModelGateway { flow {
            if (!emitted) {
                emitted = true
                emit(ModelEvent.ToolCall(RequestedToolCall("call-1", "fake_write", "{}")))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = RunPolicy(
            allowedToolIds = setOf("fake_write"),
            gate = ScopeGate(setOf("fake_write"), "测试"),
        )
        try {
            runtime(store, provider, gateway, scope).send("c", "写入", emptyList(), policy = policy)
            awaitTerminal(memory)
            assertEquals(listOf("fake_write"), provider.executed.toList())
            assertEquals("{\"mode\":\"a\"}", store.records.single { it.toolId == "fake_write" }.argumentsJson)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
        } finally { scope.cancel() }
    }
}
