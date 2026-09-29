package xyz.chouxuewei.mobile_agent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCandidateBuilderTest {

    private fun ephemeral(nodesJson: String): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(
            """{"observation_id":"obs-1","session_id":"sess-1","revision":42,""" +
                """"package":"com.demo","viewport":{"w":1080,"h":2400},"nodes":$nodesJson}"""
        ).jsonObject

    private fun node(id: String, actions: String, text: String? = null, extra: String = ""): String =
        """{"id":"$id","actions":[$actions],"enabled":true,"visible":true,"bounds":[0,0,10,10]""" +
            (text?.let { ""","text":"$it"""" } ?: "") + "$extra}"

    @Test
    fun `observation lite codec parses the contract`() {
        val obs = ObservationLiteCodec.parse(
            ephemeral("""[${node("n1", "\"click\"", "设置")}]""")
        )!!
        assertEquals("obs-1", obs.observationId)
        assertEquals("sess-1", obs.sessionId)
        assertEquals(42L, obs.revision)
        assertEquals("com.demo", obs.foregroundPackage)
        assertEquals(1080, obs.viewportW)
        assertEquals(1, obs.nodes.size)
        assertEquals("设置", obs.nodes[0].text)
        assertEquals(setOf("click"), obs.nodes[0].actions)
    }

    @Test
    fun `codec rejects malformed contract`() {
        assertNull(ObservationLiteCodec.parse(buildJsonObject { put("x", 1) }))
        assertNull(ObservationLiteCodec.parse(
            Json.parseToJsonElement("""{"observation_id":"o","nodes":[]}""").jsonObject
        )) // 缺 session_id
    }

    @Test
    fun `candidates come only from locally listed nodes`() {
        val obs = ObservationLiteCodec.parse(
            ephemeral(
                "[" + node("n1", "\"click\"", "设置") + "," +
                    node("n2", "\"scroll_forward\"", "列表") + "," +
                    node("n3", "\"set_text\"", "输入框") + "," +   // editable excluded
                    node("n4", "\"long_click\"", "更多") + "," +
                    node("n5", "\"click\"", extra = ",\"enabled\":false") + // disabled skip
                    "]"
            )
        )!!
        val candidates = ActionCandidateBuilder.build(obs)
        assertEquals(3, candidates.size)
        val byAction = candidates.associateBy { it.action }
        assertEquals("click_node", byAction["click_node"]!!.action)
        assertEquals("scroll_node", byAction["scroll_node"]!!.action)
        assertEquals("long_click_node", byAction["long_click_node"]!!.action)
        // 参数锚定到观察与节点，服务只能选 id 不能改参数
        val args = byAction["click_node"]!!.arguments
        assertEquals("obs-1", args["observation_id"]!!.jsonPrimitive.content)
        assertEquals("n1", args["node_ref"]!!.jsonPrimitive.content)
        assertEquals("forward", byAction["scroll_node"]!!.arguments["scroll_direction"]!!.jsonPrimitive.content)
        // expectedPackage 锚定当前前台包名用于屏幕切换检测
        assertEquals("com.demo", candidates[0].expectedPackage)
    }

    @Test
    fun `candidate set is bounded`() {
        val many = (1..30).joinToString(",") { node("n$it", "\"click\"", "i$it") }
        val obs = ObservationLiteCodec.parse(ephemeral("[$many]"))!!
        assertTrue(ActionCandidateBuilder.build(obs).size <= DecisionLimits.MAX_NAV_CANDIDATES)
    }

    @Test
    fun `navigation request carries bounded state and reserved options`() {
        val obs = ObservationLiteCodec.parse(
            ephemeral("""[${node("n1", "\"click\"", "设置")}]""")
        )!!
        val candidates = ActionCandidateBuilder.build(obs)
        val req = navigationDecisionRequest("打开设置页", obs, candidates, emptyList(), "r1", 7)
        assertEquals(DecisionPurpose.NAVIGATION, req.purpose)
        assertEquals("打开设置页", req.state["goal"]!!.jsonPrimitive.content)
        assertEquals("com.demo", req.state["foreground_package"]!!.jsonPrimitive.content)
        val optionKeys = req.options.keys
        assertTrue(optionKeys.contains("c1"))
        assertTrue(optionKeys.contains(DecisionPolicy.OPTION_GOAL_MET))
        assertTrue(optionKeys.contains(DecisionPolicy.OPTION_NONE))
        // 不含截图/历史字段
        assertNull(req.state["screenshot"])
        assertNull(req.state["history"])
        assertEquals(7, req.keyGeneration)
    }

    @Test
    fun `goal truncation keeps state bounded`() {
        val obs = ObservationLiteCodec.parse(ephemeral("[]"))!!
        val req = navigationDecisionRequest("g".repeat(1000), obs, emptyList(), emptyList(), "r", 0)
        assertEquals(DecisionLimits.MAX_GOAL_CHARS, req.state["goal"]!!.jsonPrimitive.content.length)
    }
}
