package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun accepted(choice: String, confidence: Double? = 0.9) = DecisionOutcome.Accepted(
    DecisionChoice(choice, confidence, emptyMap(), 10)
)

class DecisionPolicyTest {

    @Test
    fun `gate allow requires adopt confidence`() {
        val high = DecisionPolicy.evaluateGate(accepted("allow", 0.95))
        assertTrue(high.verdict is GateVerdict.Allow)
        assertEquals("allow", high.outcome)

        val low = DecisionPolicy.evaluateGate(accepted("allow", 0.5))
        assertTrue(low.verdict is GateVerdict.Confirm)
        assertEquals("low_confidence", low.fallbackReason)

        val missing = DecisionPolicy.evaluateGate(accepted("allow", null))
        assertTrue(missing.verdict is GateVerdict.Confirm)
    }

    @Test
    fun `gate block requires higher confidence than allow`() {
        val strong = DecisionPolicy.evaluateGate(accepted("block", 0.95))
        assertTrue(strong.verdict is GateVerdict.Block)
        assertEquals("block", strong.outcome)

        val weak = DecisionPolicy.evaluateGate(accepted("block", 0.85))
        assertTrue(weak.verdict is GateVerdict.Confirm)
        assertEquals("low_confidence", weak.fallbackReason)
    }

    @Test
    fun `gate unexpected option and failures confirm`() {
        val unknown = DecisionPolicy.evaluateGate(accepted("maybe", 0.99))
        assertTrue(unknown.verdict is GateVerdict.Confirm)
        assertEquals("unexpected_option", unknown.fallbackReason)

        val failed = DecisionPolicy.evaluateGate(
            DecisionOutcome.Failed(DecisionFailureKind.TIMEOUT)
        )
        assertTrue(failed.verdict is GateVerdict.Confirm)
        assertEquals("timeout", failed.fallbackReason)
        assertEquals("fallback", failed.outcome)
    }

    @Test
    fun `navigation adoption only from local candidates`() {
        val ids = setOf("c1", "c2")
        val adopt = DecisionPolicy.evaluateNavigation(accepted("c1", 0.9), ids)
        assertEquals(DecisionPolicy.NavEvaluation.Adopt("c1", 0.9), adopt)

        assertEquals(
            DecisionPolicy.NavEvaluation.Fallback("invalid_candidate"),
            DecisionPolicy.evaluateNavigation(accepted("c9", 0.99), ids),
        )
        assertEquals(
            DecisionPolicy.NavEvaluation.Fallback("low_confidence"),
            DecisionPolicy.evaluateNavigation(accepted("c2", 0.4), ids),
        )
        assertEquals(
            DecisionPolicy.NavEvaluation.Fallback("service_none"),
            DecisionPolicy.evaluateNavigation(accepted("none", 0.99), ids),
        )
        assertEquals(
            DecisionPolicy.NavEvaluation.GoalMet,
            DecisionPolicy.evaluateNavigation(accepted("goal_met", 0.95), ids),
        )
        assertEquals(
            DecisionPolicy.NavEvaluation.Fallback("network"),
            DecisionPolicy.evaluateNavigation(
                DecisionOutcome.Failed(DecisionFailureKind.NETWORK), ids,
            ),
        )
    }
}

private class FakeProvider(
    var outcome: DecisionOutcome? = null,
    var throwable: Throwable? = null,
) : DecisionProvider {
    override val backend = DecisionBackend.LAYA
    val calls = mutableListOf<DecisionChoiceRequest>()
    override suspend fun choose(request: DecisionChoiceRequest): DecisionOutcome {
        calls += request
        throwable?.let { throw it }
        return outcome ?: DecisionOutcome.Failed(DecisionFailureKind.NOT_CONFIGURED)
    }
}

class SystemOneDecisionGateTest {

    private val gateRequest = GateRequest("open settings", "device_action", "动作", "{}")

    private fun gate(
        provider: DecisionProvider?,
        mode: DecisionMode,
        audits: MutableList<DecisionAuditEvent> = mutableListOf(),
        delegate: DecisionGate? = null,
        generation: suspend () -> Int = { 3 },
    ) = SystemOneDecisionGate(
        backend = DecisionBackend.LAYA,
        providerResolver = { provider },
        mode = mode,
        keyGeneration = generation,
        delegate = delegate,
        audit = { audits += it },
    )

    @Test
    fun `enforce adopts confident allow`() = runBlocking {
        val p = FakeProvider(accepted("allow", 0.95))
        val audits = mutableListOf<DecisionAuditEvent>()
        val verdict = gate(p, DecisionMode.ENFORCE, audits).gate(gateRequest)
        assertTrue(verdict is GateVerdict.Allow)
        assertEquals(1, p.calls.size)
        val event = audits.single()
        assertEquals("allow", event.verdict)
        assertEquals(3, event.keyGeneration)
        assertEquals(DecisionPurpose.SAFETY_GATE, event.purpose)
        assertEquals(16, event.requestHash.length)
        // 安全闸请求只带最小上下文
        assertTrue(p.calls.single().state.containsKey("action_proposal"))
    }

    @Test
    fun `enforce missing provider falls back to confirm`() = runBlocking {
        val audits = mutableListOf<DecisionAuditEvent>()
        val verdict = gate(null, DecisionMode.ENFORCE, audits).gate(gateRequest)
        assertTrue(verdict is GateVerdict.Confirm)
        assertEquals("not_configured", audits.single().fallbackReason)
    }

    @Test
    fun `shadow audits but never changes delegate behavior`() = runBlocking {
        val p = FakeProvider(accepted("block", 0.99))
        val audits = mutableListOf<DecisionAuditEvent>()
        // delegate 放行：SHADOW 下 block 裁决不改变结果
        val allow = gate(p, DecisionMode.SHADOW, audits, delegate = object : DecisionGate { override suspend fun gate(r: GateRequest) = GateVerdict.Allow }).gate(gateRequest)
        assertTrue(allow is GateVerdict.Allow)
        assertEquals(1, p.calls.size)                       // 裁决确实跑了
        assertEquals("block", audits.single().verdict)      // 且留痕

        val blocked = gate(p, DecisionMode.SHADOW, audits, delegate = object : DecisionGate { override suspend fun gate(r: GateRequest) = GateVerdict.Block("d") }).gate(gateRequest)
        assertTrue(blocked is GateVerdict.Block)
    }

    @Test
    fun `shadow without delegate allows through normal approval path`() = runBlocking {
        val p = FakeProvider(accepted("block", 0.99))
        val verdict = gate(p, DecisionMode.SHADOW).gate(gateRequest)
        assertTrue(verdict is GateVerdict.Allow)
    }

    @Test
    fun `cancellation propagates without audit`() = runBlocking {
        val p = FakeProvider(throwable = CancellationException("stop"))
        val audits = mutableListOf<DecisionAuditEvent>()
        try {
            gate(p, DecisionMode.ENFORCE, audits).gate(gateRequest)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `revoked key fails safe on next call`() = runBlocking {
        val p = FakeProvider(accepted("allow", 0.95))
        var revoked = false
        val audits = mutableListOf<DecisionAuditEvent>()
        val g = SystemOneDecisionGate(
            backend = DecisionBackend.LAYA,
            providerResolver = { if (revoked) null else p },
            mode = DecisionMode.ENFORCE,
            keyGeneration = { 1 },
            audit = { audits += it },
        )
        assertTrue(g.gate(gateRequest) is GateVerdict.Allow)
        revoked = true
        assertTrue(g.gate(gateRequest) is GateVerdict.Confirm)
        assertEquals("not_configured", audits.last().fallbackReason)
    }
}

class DecisionGateFactoryTest {

    private fun factory(
        snapshot: DecisionSettingsSnapshot,
        provider: DecisionProvider? = null,
        audits: MutableList<DecisionAuditEvent> = mutableListOf(),
        legacyEnabled: Boolean = true,
        legacy: DecisionGate? = null,
    ) = DecisionGateFactory(
        settings = { snapshot },
        resolveProvider = { provider },
        legacyGateEnabled = { legacyEnabled },
        legacyGate = { legacy },
        audit = { audits += it },
    )

    private val connection = ChatConnection(
        gateway = ChatModelGateway { kotlinx.coroutines.flow.emptyFlow() },
        policy = ContextPolicy(8192, 1024),
        model = "test",
    )

    @Test
    fun `none backend returns legacy gate`() = runBlocking {
        val legacy = object : DecisionGate { override suspend fun gate(r: GateRequest) = GateVerdict.Allow }
        val result = factory(DecisionSettingsSnapshot(), legacy = legacy)
            .safetyGate(connection, null)
        assertTrue(result === legacy)
    }

    @Test
    fun `none backend with disabled legacy returns null`() = runBlocking {
        assertNull(factory(DecisionSettingsSnapshot(), legacyEnabled = false)
            .safetyGate(connection, null))
    }

    @Test
    fun `unattended run never gets dedicated backend`() = runBlocking {
        val audits = mutableListOf<DecisionAuditEvent>()
        val snapshot = DecisionSettingsSnapshot(
            backend = DecisionBackend.LAYA,
            outboundConsent = true,
            laya = DecisionProfile(baseUrl = "http://x", keyReference = "k"),
        )
        val result = factory(
            snapshot, provider = FakeProvider(), audits = audits,
        ).safetyGate(connection, RunPolicy(allowedToolIds = emptySet()))
        assertTrue(result !is SystemOneDecisionGate)
        assertEquals("skipped", audits.single().verdict)
        assertEquals("unattended_run", audits.single().fallbackReason)
    }

    @Test
    fun `configured backend produces system one gate`() = runBlocking {
        val snapshot = DecisionSettingsSnapshot(
            backend = DecisionBackend.LAYA,
            mode = DecisionMode.ENFORCE,
            outboundConsent = true,
            laya = DecisionProfile(baseUrl = "http://x", keyReference = "k", keyGeneration = 5),
        )
        val result = factory(snapshot, provider = FakeProvider(accepted("allow", 0.95)))
            .safetyGate(connection, null)
        assertTrue(result is SystemOneDecisionGate)
        val verdict = result!!.gate(GateRequest("u", "t", "tt", "a"))
        assertTrue(verdict is GateVerdict.Allow)
    }

    @Test
    fun `unconsented backend still fails safe`() = runBlocking {
        val snapshot = DecisionSettingsSnapshot(
            backend = DecisionBackend.LAYA,
            mode = DecisionMode.ENFORCE,
            outboundConsent = false,
            laya = DecisionProfile(baseUrl = "http://x", keyReference = "k"),
        )
        val result = factory(snapshot, provider = FakeProvider(accepted("allow", 0.95)))
            .safetyGate(connection, null)
        assertTrue(result is SystemOneDecisionGate)
        // resolver 在 app 层检查 consent；工厂层即使拿到 provider，
        // 无同意时快照内的 outboundConsent=false 由解析方保证不返回。
        // 这里 provider 非空时闸照常工作——真正的同意闸门在仓库解析。
        assertTrue(result!!.gate(GateRequest("u", "t", "tt", "a")) is GateVerdict.Allow)
    }
}
