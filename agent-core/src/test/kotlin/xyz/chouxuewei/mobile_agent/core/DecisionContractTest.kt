package xyz.chouxuewei.mobile_agent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionContractTest {

    @Test
    fun `default snapshot is inert`() {
        val s = DecisionSettingsSnapshot()
        assertEquals(DecisionBackend.NONE, s.backend)
        assertEquals(DecisionMode.SHADOW, s.mode)
        assertFalse(s.outboundConsent)
        assertFalse(s.navigationAcceleration)
        assertFalse(s.laya.configured)   // 无密钥引用
        assertFalse(s.jev.configured)
    }

    @Test
    fun `backend parse is stable and defaults to NONE`() {
        assertEquals(DecisionBackend.NONE, DecisionBackend.parse(null))
        assertEquals(DecisionBackend.NONE, DecisionBackend.parse("bogus"))
        assertEquals(DecisionBackend.LAYA, DecisionBackend.parse("laya"))
        assertEquals(DecisionBackend.JEV, DecisionBackend.parse("jev"))
        assertEquals(DecisionMode.SHADOW, DecisionMode.parse(null))
        assertEquals(DecisionMode.ENFORCE, DecisionMode.parse("enforce"))
        assertEquals(DecisionPurpose.NAVIGATION, DecisionPurpose.parse("navigation"))
    }

    @Test
    fun `profile configured requires url and key reference`() {
        assertFalse(DecisionProfile().configured)
        assertFalse(DecisionProfile(baseUrl = "https://x").configured)                 // 无密钥引用
        assertFalse(DecisionProfile(baseUrl = "ftp://x", keyReference = "k").configured)
        assertTrue(DecisionProfile(baseUrl = "https://api.x", keyReference = "k").configured)
        assertTrue(DecisionProfile(baseUrl = "http://localhost:8000", keyReference = "k").configured)
    }

    @Test
    fun `gate request carries only bounded decision context`() {
        val gate = GateRequest(
            userRequest = "x".repeat(1000),
            toolId = "device_action",
            toolTitle = "执行动作",
            argumentsSummary = "y".repeat(5000),
        )
        val req = safetyGateDecisionRequest(gate, "r1", keyGeneration = 3)
        assertEquals(DecisionPurpose.SAFETY_GATE, req.purpose)
        assertEquals(DecisionLimits.MAX_GOAL_CHARS, req.state["user_request"]!!
            .toString().trim('"').length)
        assertEquals(1000, req.state["action_proposal"]!!.toString()
            .substringAfter("arguments_summary\":\"").substringBefore("\"").length)
        assertEquals(setOf("allow", "confirm", "block"), req.options.keys)
        assertEquals(3, req.keyGeneration)
        // 契约里没有历史/截图字段
        assertNull(req.state["history"])
        assertNull(req.state["screenshot"])
        assertNull(req.state["messages"])
    }

    @Test
    fun `oversized requests are rejected locally before sending`() {
        val base = DecisionChoiceRequest(
            purpose = DecisionPurpose.SAFETY_GATE,
            requestId = "r",
            state = buildJsonObject { put("a", "b") },
            instructions = "i",
            options = mapOf("a" to "b"),
        )
        assertNull(base.limitViolation(100))

        val longInstructions = base.copy(instructions = "x".repeat(DecisionLimits.MAX_INSTRUCTION_CHARS + 1))
        assertEquals(DecisionFailureKind.TOO_LARGE, longInstructions.limitViolation(100))

        val manyOptions = base.copy(options = (1..DecisionLimits.MAX_OPTIONS + 1)
            .associate { "o$it" to "d" })
        assertEquals(DecisionFailureKind.TOO_LARGE, manyOptions.limitViolation(100))

        val bigState = base.copy(state = buildJsonObject {
            put("blob", "x".repeat(DecisionLimits.MAX_STATE_CHARS))
        })
        assertEquals(DecisionFailureKind.TOO_LARGE, bigState.limitViolation(100))

        assertEquals(DecisionFailureKind.TOO_LARGE, base.limitViolation(DecisionLimits.MAX_REQUEST_BYTES + 1))
    }

    @Test
    fun `stable hash is deterministic and does not leak content`() {
        val req = safetyGateDecisionRequest(
            GateRequest("u", "t", "tt", "args"), "r1", 1,
        )
        assertEquals(req.stableHash(), req.stableHash())
        assertEquals(16, req.stableHash().length)
        val other = req.copy(requestId = "r2")
        assertNotEquals(req.stableHash(), other.stableHash())
        assertFalse(req.stableHash().contains("args"))
    }
}
