package xyz.chouxuewei.mobile_agent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端：navigation_goal → 瞬态观察 → 本地候选 → 决策服务 → 统一执行通道 →
 * 工具记录与历史一致；合格快路径期间不再调用主模型。
 */
class FastPathIntegrationTest {

    private val ephemeralJson = """{"observation_id":"obs-1","session_id":"s1","revision":1,""" +
        """"package":"com.demo","viewport":{"w":1080,"h":2400},""" +
        """"nodes":[{"id":"n1","text":"设置","bounds":[0,0,10,10],"actions":["click"],"enabled":true,"visible":true}]}"""

    private class FakeDeviceProvider : ToolProvider {
        override val id = "device"; override val title = "device"; override val description = "device"
        override val definitions = listOf(
            ToolDefinition(
                id = "device_observe", title = "observe", description = "observe",
                inputSchema = "{}", sideEffect = ToolSideEffect.READ, providerId = "device",
            ),
            ToolDefinition(
                id = "device_action", title = "action", description = "action",
                inputSchema = "{}", sideEffect = ToolSideEffect.EXTERNAL_WRITE, providerId = "device",
            ),
        )
        val executed = CopyOnWriteArrayList<RequestedToolCall>()
        var ephemeral: String = ""
        override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult {
            executed += call
            return when (call.toolId) {
                "device_observe" -> ToolResult(
                    content = """{"observation_id":"obs-1"}""",
                    summary = "observed",
                    ephemeral = kotlinx.serialization.json.Json.parseToJsonElement(ephemeral).jsonObject,
                )
                "device_action" -> ToolResult("{\"ok\":true}", "done")
                else -> ToolResult("{\"error\":\"x\"}", "err", true)
            }
        }
    }

    private class RecordingStore(val memory: MemoryConversationStore) : ConversationStore by memory {
        val records = CopyOnWriteArrayList<ToolCallRecord>()
        override suspend fun saveToolCall(call: ToolCallRecord) { records += call }
        override suspend fun updateToolCall(call: ToolCallRecord) {
            val i = records.indexOfFirst { it.id == call.id }
            if (i >= 0) records[i] = call else records += call
        }
    }

    private class RecordingProvider(val outcomes: MutableList<DecisionOutcome>) : DecisionProvider {
        override val backend = DecisionBackend.LAYA
        val requests = CopyOnWriteArrayList<DecisionChoiceRequest>()
        override suspend fun choose(request: DecisionChoiceRequest): DecisionOutcome {
            requests += request
            return outcomes.removeAt(0)
        }
    }

    private val permissionStore = object : ToolPermissionStore {
        override val accesses = flowOf(emptyMap<String, ToolAccess>())
        override suspend fun access(capabilityId: String) =
            ToolAccess(enabled = true, permission = ToolPermissionMode.FULL_ACCESS)
        override suspend fun setEnabled(capabilityId: String, enabled: Boolean) = Unit
        override suspend fun setPermission(capabilityId: String, mode: ToolPermissionMode) = Unit
    }

    private fun accepted(choice: String, confidence: Double = 0.95) =
        DecisionOutcome.Accepted(DecisionChoice(choice, confidence, emptyMap(), 5))

    private fun snapshot(
        backend: DecisionBackend = DecisionBackend.LAYA,
        mode: DecisionMode = DecisionMode.ENFORCE,
        nav: Boolean = true,
        consent: Boolean = true,
    ) = DecisionSettingsSnapshot(
        backend = backend, mode = mode, outboundConsent = consent,
        navigationAcceleration = nav,
        laya = DecisionProfile(baseUrl = "http://x", keyReference = "k", keyGeneration = 2),
    )

    private suspend fun awaitTerminal(memory: MemoryConversationStore) {
        withTimeout(5000) {
            while (memory.runs.isEmpty() || memory.runs.last().status == RunStatus.GENERATING) yield()
        }
    }

    @Test fun goalToExecutionSkipsPlannerCallsAndWritesHistory() = runBlocking {
        val device = FakeDeviceProvider().also { it.ephemeral = ephemeralJson }
        val decisions = RecordingProvider(mutableListOf(accepted("c1"), accepted("goal_met")))
        val audits = CopyOnWriteArrayList<DecisionAuditEvent>()
        val memory = MemoryConversationStore()
        val store = RecordingStore(memory)
        val modelCalls = CopyOnWriteArrayList<Int>()
        var emittedObserve = false
        val gateway = ChatModelGateway { _ -> flow {
            modelCalls += 1
            if (!emittedObserve) {
                emittedObserve = true
                emit(ModelEvent.ToolCall(RequestedToolCall(
                    "call-1", "device_observe",
                    """{"session_id":"s1","navigation_goal":"打开设置页"}""",
                )))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ChatRuntime(
                store,
                { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
                scope,
                tools = ToolRegistry(listOf(device)),
                toolPermissions = permissionStore,
                fastPath = FastPathSupport(
                    settings = { snapshot() },
                    resolveProvider = { decisions },
                    audit = { audits += it },
                ),
            )
            runtime.send("c", "打开设置页", emptyList())
            awaitTerminal(memory)

            // 模型只被调用两次：一次规划出 observe(goal)，一次收尾写答复。
            // 快路径的 action+observe 循环不经过模型。
            assertEquals(2, modelCalls.size)
            val toolIds = device.executed.map { it.toolId }
            assertEquals(listOf("device_observe", "device_action", "device_observe"), toolIds)
            // 快路径动作参数是候选构造的（锚定观察与节点），服务只选了 id
            val action = device.executed[1]
            assertTrue(action.argumentsJson.contains("\"node_ref\":\"n1\""))
            assertTrue(action.argumentsJson.contains("\"observation_id\":\"obs-1\""))
            // 审计：一次采纳 + 一次 goal_met
            assertEquals(2, audits.size)
            assertEquals("adopt", audits[0].verdict)
            assertEquals("goal_met", audits[1].verdict)
            assertEquals("c1", audits[0].candidateId)
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
            // 工具记录写回历史（统一执行通道）
            assertTrue(store.records.any { it.toolId == "device_action" })
            assertTrue(store.records.any { it.id.contains(":fp:") })
        } finally { scope.cancel() }
    }

    @Test fun shadowAuditsButExecutesNothing() = runBlocking {
        val device = FakeDeviceProvider().also { it.ephemeral = ephemeralJson }
        val decisions = RecordingProvider(mutableListOf(accepted("c1")))
        val audits = CopyOnWriteArrayList<DecisionAuditEvent>()
        val memory = MemoryConversationStore()
        var emittedObserve = false
        val gateway = ChatModelGateway { _ -> flow {
            if (!emittedObserve) {
                emittedObserve = true
                emit(ModelEvent.ToolCall(RequestedToolCall(
                    "call-1", "device_observe",
                    """{"session_id":"s1","navigation_goal":"打开设置页"}""",
                )))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ChatRuntime(
                memory,
                { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
                scope,
                tools = ToolRegistry(listOf(device)),
                toolPermissions = permissionStore,
                fastPath = FastPathSupport(
                    settings = { snapshot(mode = DecisionMode.SHADOW) },
                    resolveProvider = { decisions },
                    audit = { audits += it },
                ),
            ).send("c", "打开设置页", emptyList())
            awaitTerminal(memory)
            // SHADOW：裁决跑了并留痕，但绝不执行 device_action
            assertEquals(1, device.executed.count { it.toolId == "device_observe" })
            assertTrue(device.executed.none { it.toolId == "device_action" })
            assertEquals(1, audits.size)
        } finally { scope.cancel() }
    }

    @Test fun disabledAccelerationSkipsFastPath() = runBlocking {
        val device = FakeDeviceProvider().also { it.ephemeral = ephemeralJson }
        val decisions = RecordingProvider(mutableListOf(accepted("c1")))
        val memory = MemoryConversationStore()
        var emittedObserve = false
        val gateway = ChatModelGateway { _ -> flow {
            if (!emittedObserve) {
                emittedObserve = true
                emit(ModelEvent.ToolCall(RequestedToolCall(
                    "call-1", "device_observe",
                    """{"session_id":"s1","navigation_goal":"打开设置页"}""",
                )))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ChatRuntime(
                memory,
                { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
                scope,
                tools = ToolRegistry(listOf(device)),
                toolPermissions = permissionStore,
                fastPath = FastPathSupport(
                    settings = { snapshot(nav = false) },
                    resolveProvider = { decisions },
                ),
            ).send("c", "打开设置页", emptyList())
            awaitTerminal(memory)
            assertTrue(decisions.requests.isEmpty())
            assertEquals(1, device.executed.size) // 只有模型发起的那次 observe
        } finally { scope.cancel() }
    }

    @Test fun providerFailureFallsBackToPlanner() = runBlocking {
        val device = FakeDeviceProvider().also { it.ephemeral = ephemeralJson }
        val decisions = RecordingProvider(mutableListOf(
            DecisionOutcome.Failed(DecisionFailureKind.CIRCUIT_OPEN),
        ))
        val memory = MemoryConversationStore()
        var emittedObserve = false
        var modelCalls = 0
        val gateway = ChatModelGateway { _ -> flow {
            modelCalls++
            if (!emittedObserve) {
                emittedObserve = true
                emit(ModelEvent.ToolCall(RequestedToolCall(
                    "call-1", "device_observe",
                    """{"session_id":"s1","navigation_goal":"打开设置页"}""",
                )))
            }
            emit(ModelEvent.TextDelta("完成")); emit(ModelEvent.Completed("stop"))
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ChatRuntime(
                memory,
                { ChatConnection(gateway, ContextPolicy(8192, 1024), "test") },
                scope,
                tools = ToolRegistry(listOf(device)),
                toolPermissions = permissionStore,
                fastPath = FastPathSupport(
                    settings = { snapshot() },
                    resolveProvider = { decisions },
                ),
            ).send("c", "打开设置页", emptyList())
            awaitTerminal(memory)
            assertTrue(device.executed.none { it.toolId == "device_action" })
            assertEquals(RunStatus.SUCCEEDED, memory.runs.last().status)
            assertEquals(2, modelCalls) // 规划 + 收尾，回退不挂死
        } finally { scope.cancel() }
    }
}
