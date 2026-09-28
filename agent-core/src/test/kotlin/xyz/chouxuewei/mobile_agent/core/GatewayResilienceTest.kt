package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayResilienceTest {

    private fun ok(text: String = "完成") = ChatModelGateway { flow {
        emit(ModelEvent.TextDelta(text)); emit(ModelEvent.Completed("stop"))
    } }

    private fun failing(vararg messages: String) = ChatModelGateway { flow {
        messages.forEach { emit(ModelEvent.Error(it)) }
    } }

    private fun request() = ChatRequest(listOf(ChatTurn("user", "你好")), 128)

    @Test fun retryingGatewaySucceedsFirstTryWithoutDelay() = runBlocking {
        var calls = 0
        val upstream = ChatModelGateway { calls++; ok().stream(it) }
        val events = RetryingGateway(upstream, sleeper = {}).stream(request()).toList()
        assertEquals(1, calls)
        assertTrue(events.any { it is ModelEvent.Completed })
    }

    @Test fun retryingGatewayRetriesTransientErrorThenSucceeds() = runBlocking {
        var calls = 0
        val upstream = ChatModelGateway { flow {
            calls++
            if (calls < 3) emit(ModelEvent.Error("请求超时 (HTTP 408)"))
            else { emit(ModelEvent.TextDelta("恢复")); emit(ModelEvent.Completed("stop")) }
        } }
        val events = RetryingGateway(upstream, maxAttempts = 3, sleeper = {}).stream(request()).toList()
        assertEquals(3, calls)
        assertEquals("恢复", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertFalse(events.any { it is ModelEvent.Error })
    }

    @Test fun retryingGatewayDoesNotRetryPermanentError() = runBlocking {
        var calls = 0
        val upstream = ChatModelGateway { calls++; failing("API 密钥无效 (HTTP 401)").stream(it) }
        val events = RetryingGateway(upstream, sleeper = {}).stream(request()).toList()
        assertEquals(1, calls)
        assertTrue(events.single() is ModelEvent.Error)
    }

    @Test fun retryingGatewayStopsRetryingAfterContentStarted() = runBlocking {
        var calls = 0
        val upstream = ChatModelGateway { flow {
            calls++
            emit(ModelEvent.TextDelta("半截回复"))
            emit(ModelEvent.Error("回复意外中断，已生成的内容已保留"))
        } }
        val events = RetryingGateway(upstream, sleeper = {}).stream(request()).toList()
        assertEquals(1, calls)
        assertEquals("半截回复", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.last() is ModelEvent.Error)
    }

    @Test fun retryingGatewayExhaustsAttemptsAndReportsLastError() = runBlocking {
        var calls = 0
        val upstream = ChatModelGateway { calls++; failing("服务不可用 (HTTP 503)").stream(it) }
        val events = RetryingGateway(upstream, maxAttempts = 2, sleeper = {}).stream(request()).toList()
        assertEquals(2, calls)
        assertTrue(events.last() is ModelEvent.Error)
    }

    @Test fun failoverGatewaySwitchesToBackupOnPreContentFailure() = runBlocking {
        var backupCalls = 0
        val backup = ChatModelGateway { backupCalls++; ok("备用回答").stream(it) }
        val gateway = FailoverGateway(failing("密钥无效 (HTTP 401)"), backup)
        val events = gateway.stream(request()).toList()
        assertEquals(1, backupCalls)
        assertEquals("备用回答", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test fun failoverGatewayKeepsPartialPrimaryOutput() = runBlocking {
        var backupCalls = 0
        val backup = ChatModelGateway { backupCalls++; ok("备用").stream(it) }
        val primary = ChatModelGateway { flow {
            emit(ModelEvent.TextDelta("主模型半截"))
            emit(ModelEvent.Error("回复意外中断"))
        } }
        val events = FailoverGateway(primary, backup).stream(request()).toList()
        assertEquals(0, backupCalls)
        assertEquals("主模型半截", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.last() is ModelEvent.Error)
    }

    @Test fun failoverGatewayPassesThroughWhenPrimarySucceeds() = runBlocking {
        var backupCalls = 0
        val backup = ChatModelGateway { backupCalls++; ok().stream(it) }
        val events = FailoverGateway(ok("主回答"), backup).stream(request()).toList()
        assertEquals(0, backupCalls)
        assertTrue(events.any { it is ModelEvent.Completed })
    }

    @Test fun composedResilienceRetriesEachEndpointThenFailsOver() = runBlocking {
        var primaryCalls = 0
        val primary = ChatModelGateway { primaryCalls++; failing("超时 (HTTP 408)").stream(it) }
        var backupCalls = 0
        val backup = ChatModelGateway { backupCalls++; ok("备用恢复").stream(it) }
        val gateway = FailoverGateway(
            RetryingGateway(primary, maxAttempts = 2, sleeper = {}),
            RetryingGateway(backup, sleeper = {}),
        )
        val events = gateway.stream(request()).toList()
        assertEquals(2, primaryCalls)
        assertEquals(1, backupCalls)
        assertEquals("备用恢复", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
    }
}
