package xyz.chouxuewei.mobile_agent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

/** 服务端上下文溢出的自愈：分类器、未产出内容时压缩重试、已产出内容不重试、只自愈一次。 */
class ContextOverflowRecoveryTest {

    @Test fun `classifier detects overflow markers`() {
        assertTrue(detectContextOverflow(413, null))
        assertTrue(detectContextOverflow(400, """{"error":{"code":"context_length_exceeded"}}"""))
        assertTrue(detectContextOverflow(400, """{"error":{"message":"This model's maximum context length is 8192 tokens"}}"""))
        assertTrue(detectContextOverflow(422, """{"detail":"input is too long"}"""))
        assertTrue(detectContextOverflow(400, """{"error":"上下文长度超出限制"}"""))
    }

    @Test fun `classifier ignores unrelated failures`() {
        assertFalse(detectContextOverflow(400, """{"error":{"code":"invalid_api_key"}}"""))
        assertFalse(detectContextOverflow(400, null))
        assertFalse(detectContextOverflow(500, """{"error":"context_length_exceeded"}"""))
        assertFalse(detectContextOverflow(503, """{"error":"maximum context length"}"""))
        assertFalse(detectContextOverflow(400, """{"error":{"message":"max_tokens too large"}}"""))
    }

    private fun definition(id: String) =
        ToolDefinition(id = id, title = id, description = id, inputSchema = "{}",
            sideEffect = ToolSideEffect.READ, providerId = "fake")

    /** FULL_ACCESS：审批会挂起等待用户，测试里必须显式放行。 */
    private val permissionStore = object : ToolPermissionStore {
        override val accesses = flowOf(emptyMap<String, ToolAccess>())
        override suspend fun access(capabilityId: String) =
            ToolAccess(permission = ToolPermissionMode.FULL_ACCESS)
        override suspend fun setEnabled(capabilityId: String, enabled: Boolean) = Unit
        override suspend fun setPermission(capabilityId: String, mode: ToolPermissionMode) = Unit
    }

    private class LongResultProvider(definitions: List<ToolDefinition>) : ToolProvider {
        override val id = "fake"; override val title = "fake"; override val description = "fake"
        override val definitions = definitions
        override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext) =
            ToolResult("x".repeat(5000), "done")
    }

    private suspend fun awaitTerminal(memory: MemoryConversationStore) {
        withTimeout(5000) {
            while (memory.runs.isEmpty() || memory.runs.last().status == RunStatus.GENERATING) yield()
        }
    }

    /** 第一轮要工具、第二轮溢出、第三轮成功：自愈应截断长工具结果后按原轮重试并跑通。 */
    @Test fun `overflow error mid run compacts and retries once`() = runBlocking {
        val provider = LongResultProvider(listOf(definition("fake_read")))
        val memory = MemoryConversationStore()
        val requests = CopyOnWriteArrayList<List<ChatTurn>>()
        var calls = 0
        val gateway = ChatModelGateway { request -> flow {
            requests += request.messages
            when (++calls) {
                1 -> emit(ModelEvent.ToolCall(RequestedToolCall("c1", "fake_read", "{}")))
                2 -> emit(ModelEvent.Error("模型服务拒绝了本次请求 (HTTP 400)", contextOverflow = true))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ChatRuntime(
            memory,
            { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
            scope,
            tools = ToolRegistry(listOf(provider)),
            toolPermissions = permissionStore,
        )
        try {
            runtime.send("c", "读一下", emptyList())
            awaitTerminal(memory)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
            assertEquals(3, calls)
            // 重试请求里的工具结果被截成了摘录而不是原文 5000 字符
            val retried = requests.last()
            val toolTurn = retried.last { it.role == "tool" }
            assertTrue(toolTurn.content.length < 5000)
            assertTrue(toolTurn.content.contains("history_read") || toolTurn.content.contains("截断"))
        } finally { scope.cancel() }
    }

    /** 已产出内容后溢出不能重试（会重复文本）——按原错误失败。 */
    @Test fun `overflow after content was emitted does not retry`() = runBlocking {
        val provider = LongResultProvider(listOf(definition("fake_read")))
        val memory = MemoryConversationStore()
        var calls = 0
        val gateway = ChatModelGateway { flow {
            if (++calls == 1) {
                emit(ModelEvent.ToolCall(RequestedToolCall("c1", "fake_read", "{}")))
                emit(ModelEvent.Completed("stop"))
            } else {
                emit(ModelEvent.TextDelta("半截"))
                emit(ModelEvent.Error("(HTTP 400)", contextOverflow = true))
            }
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ChatRuntime(
            memory,
            { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
            scope,
            tools = ToolRegistry(listOf(provider)),
            toolPermissions = permissionStore,
        )
        try {
            runtime.send("c", "读一下", emptyList())
            awaitTerminal(memory)
            assertEquals(RunStatus.FAILED, memory.runs.last().status)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    /** 自愈一次为限：连续溢出第二次直接失败，不死循环。 */
    @Test fun `overflow recovery is consumed once per run`() = runBlocking {
        val provider = LongResultProvider(listOf(definition("fake_read")))
        val memory = MemoryConversationStore()
        var calls = 0
        val gateway = ChatModelGateway { flow {
            if (++calls == 1) {
                emit(ModelEvent.ToolCall(RequestedToolCall("c1", "fake_read", "{}")))
                emit(ModelEvent.Completed("stop"))
            } else {
                emit(ModelEvent.Error("(HTTP 400)", contextOverflow = true))
            }
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ChatRuntime(
            memory,
            { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
            scope,
            tools = ToolRegistry(listOf(provider)),
            toolPermissions = permissionStore,
        )
        try {
            runtime.send("c", "读一下", emptyList())
            awaitTerminal(memory)
            assertEquals(RunStatus.FAILED, memory.runs.last().status)
            assertEquals(3, calls) // 工具轮 + 首次溢出 + 压缩重试后再次溢出
        } finally { scope.cancel() }
    }
}
