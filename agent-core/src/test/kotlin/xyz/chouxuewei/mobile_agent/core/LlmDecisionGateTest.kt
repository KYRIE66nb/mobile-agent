package xyz.chouxuewei.mobile_agent.core

import org.junit.Assert.*
import org.junit.Test

/** 裁决解析：标准 JSON、模型不规范输出兜底、垃圾输出降级 Confirm。 */
class LlmDecisionGateTest {

    @Test fun `parses standard json verdict`() {
        assertEquals(GateVerdict.Allow, LlmDecisionGate.parse("""{"decision":"allow","reason":"打开应用"}"""))
        val blocked = LlmDecisionGate.parse("""{"decision":"block","reason":"支付转账"}""")
        assertTrue(blocked is GateVerdict.Block)
        assertEquals("支付转账", (blocked as GateVerdict.Block).reason)
        val confirm = LlmDecisionGate.parse("""{"decision":"confirm","reason":"发消息"}""")
        assertTrue(confirm is GateVerdict.Confirm)
        assertEquals("发消息", (confirm as GateVerdict.Confirm).reason)
    }

    @Test fun `parses json embedded in prose`() {
        val verdict = LlmDecisionGate.parse("""我认为可以执行。{"decision":"allow","reason":"普通操作"}""")
        assertEquals(GateVerdict.Allow, verdict)
    }

    @Test fun `accepts bare keyword at start`() {
        assertEquals(GateVerdict.Allow, LlmDecisionGate.parse("allow"))
        assertTrue(LlmDecisionGate.parse("BLOCK") is GateVerdict.Block)
        assertTrue(LlmDecisionGate.parse("  confirm") is GateVerdict.Confirm)
    }

    @Test fun `accepts unquoted decision field`() {
        assertEquals(GateVerdict.Allow, LlmDecisionGate.parse("decision: allow"))
        assertTrue(LlmDecisionGate.parse("decision=block") is GateVerdict.Block)
        assertTrue(LlmDecisionGate.parse("DECISION: CONFIRM") is GateVerdict.Confirm)
    }

    @Test fun `garbage degrades to confirm`() {
        assertTrue(LlmDecisionGate.parse("") is GateVerdict.Confirm)
        assertTrue(LlmDecisionGate.parse("这个动作我觉得没问题可以执行") is GateVerdict.Confirm)
        assertTrue(LlmDecisionGate.parse("""{"decision":"maybe","reason":"x"}""") is GateVerdict.Confirm)
        assertTrue(LlmDecisionGate.parse("无法裁决") is GateVerdict.Confirm)
    }
}
