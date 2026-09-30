package xyz.chouxuewei.mobile_agent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

/** 思考强度导致的空响应自愈：首轮空且带 effort → 丢 effort 重试一次；仍空才按原错误失败。 */
class EffortDegradeTest {

    private fun definition(id: String) =
        ToolDefinition(id = id, title = id, description = id, inputSchema = "{}",
            sideEffect = ToolSideEffect.READ, providerId = "fake")

    private val permissionStore = object : ToolPermissionStore {
        override val accesses = flowOf(emptyMap<String, ToolAccess>())
        override suspend fun access(capabilityId: String) =
            ToolAccess(permission = ToolPermissionMode.FULL_ACCESS)
        override suspend fun setEnabled(capabilityId: String, enabled: Boolean) = Unit
        override suspend fun setPermission(capabilityId: String, mode: ToolPermissionMode) = Unit
    }

    private suspend fun awaitTerminal(memory: MemoryConversationStore) {
        withTimeout(5000) {
            while (memory.runs.isEmpty() || memory.runs.last().status == RunStatus.GENERATING) yield()
        }
    }

    @Test fun `empty body with effort retries once without effort`() = runBlocking {
        val memory = MemoryConversationStore()
        val efforts = CopyOnWriteArrayList<String?>()
        var calls = 0
        val gateway = ChatModelGateway { request -> flow {
            efforts += request.reasoningEffort
            when (++calls) {
                // 模拟高思考强度下只完成、正文为空
                1 -> emit(ModelEvent.Completed("length"))
                else -> {
                    emit(ModelEvent.TextDelta("完成"))
                    emit(ModelEvent.Completed("stop"))
                }
            }
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ChatRuntime(
            memory,
            { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
            scope,
            tools = ToolRegistry(listOf(object : ToolProvider {
                override val id = "fake"; override val title = "fake"; override val description = "fake"
                override val definitions = listOf(definition("fake_read"))
                override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext) =
                    ToolResult("ok", "done")
            })),
            toolPermissions = permissionStore,
        )
        try {
            runtime.send("c", "说点什么", emptyList(), reasoningEffort = "max")
            awaitTerminal(memory)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
            assertEquals(2, calls)
            assertEquals("max", efforts[0])
            assertNull(efforts[1])
        } finally { scope.cancel() }
    }

    @Test fun `empty body without effort fails immediately`() = runBlocking {
        val memory = MemoryConversationStore()
        var calls = 0
        val gateway = ChatModelGateway { flow {
            calls++
            emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ChatRuntime(
            memory,
            { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
            scope,
            tools = ToolRegistry(emptyList()),
            toolPermissions = permissionStore,
        )
        try {
            runtime.send("c", "说点什么", emptyList())
            awaitTerminal(memory)
            assertEquals(RunStatus.FAILED, memory.runs.last().status)
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun `effort retry consumed once`() = runBlocking {
        val memory = MemoryConversationStore()
        var calls = 0
        val gateway = ChatModelGateway { flow {
            calls++
            emit(ModelEvent.Completed("length"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = ChatRuntime(
            memory,
            { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
            scope,
            tools = ToolRegistry(emptyList()),
            toolPermissions = permissionStore,
        )
        try {
            runtime.send("c", "说点什么", emptyList(), reasoningEffort = "max")
            awaitTerminal(memory)
            assertEquals(RunStatus.FAILED, memory.runs.last().status)
            assertEquals(2, calls) // 首次空 + 降级重试仍空 → 失败，不死循环
        } finally { scope.cancel() }
    }
}
