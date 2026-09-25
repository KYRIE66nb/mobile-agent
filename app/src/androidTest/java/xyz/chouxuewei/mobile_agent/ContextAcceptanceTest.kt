package xyz.chouxuewei.mobile_agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.model.OpenAiChatGateway
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

@RunWith(AndroidJUnit4::class)
class ContextAcceptanceTest {
    @Test fun realModelCompactsSyntheticHistoryAndRetrievesCorrection() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PrototypeApplication
        val store=app.conversations
        app.chatRuntime.ready()
        val c=store.createConversation()
        store.rename(c.id,"上下文验收（合成记录）")
        var sourceId=""
        repeat(7) { n ->
            val text=if(n==0) "更正：项目代号青竹，预算不是500元，而是800元。必须用中文，不能改动预算。" else "讨论第${n+1}个备选方案"
            val user=store.enqueue(c.id,text,emptyList())
            if(n==0) sourceId=user.id
            val run=store.beginRun(c.id,user.id,"synthetic-fixture-not-model")
            val material="""
                合成验收方案${n+1}：先整理现有读书笔记，再建立按主题和来源分类的索引，最后安排每周复盘。
                资料阶段要保留原始页码、作者、出版时间和引用位置，区分自己的理解与书中的原话。遇到不确定的数字时标记待核对，不自行补全。
                计划阶段把阅读目标拆成可完成的小步骤。工作日安排短阅读，周末集中回顾。每次记录一个核心观点、两个问题以及下一步可做的验证。
                复盘阶段比较不同笔记的重复内容与矛盾之处；重复观点可以合并，但不能删除原始来源。修改后的结论单独记下原因和日期，便于以后追溯。
                预算按用户更正后的800元执行，暂拟资料整理200元、阅读计划200元、复盘记录400元。上述分配只是待讨论建议，没有付款或外部操作。
                需要用户决定采用哪种标签体系、每周可投入多少时间，以及哪些历史材料需要优先整理。方案仍处于讨论阶段，本记录是测试材料，不是模型实际生成的答复。
            """.trimIndent()
            store.finishRun(run,material,RunStatus.SUCCEEDED)
        }
        val before=store.messages(c.id)
        val manager=ContextManager(store)
        val config=app.modelSettings.loadConfig()
        val gateway=OpenAiChatGateway(config)
        val arguments=InstrumentationRegistry.getArguments()
        val policy=ContextPolicy(arguments.getString("windowTokens","16384").toInt(),arguments.getString("outputReserve","6144").toInt())
        val turns=withTimeout(180000) { manager.prepare(c.id,Long.MAX_VALUE,policy,"configured-test-model",gateway) }
        val snapshot=requireNotNull(store.snapshot(c.id))
        println("Compaction estimate: ${snapshot.inputTokensBefore} -> ${snapshot.inputTokensAfter}; sources=${snapshot.sourceVersions.size}")
        assertTrue(snapshot.inputTokensAfter<policy.inputBudget*.6)
        assertEquals(before,store.messages(c.id))
        assertTrue(turns.any { it.content.contains("不是500元，而是800元") })
        val question=store.enqueue(c.id,"请引用 $sourceId 的原文，项目代号和更正后的预算是什么？",emptyList())
        val run=store.beginRun(c.id,question.id,"live-model")
        val retrieved=manager.prepare(c.id,question.sequence,policy,"configured-test-model",gateway)
        assertTrue(retrieved.any { it.content.startsWith("[取回的原始消息") && it.content.contains("800元") })
        val events=withTimeout(120000) { gateway.stream(ChatRequest(retrieved,1024)).toList() }
        assertFalse(events.any { it is ModelEvent.Error })
        assertTrue(events.any { it is ModelEvent.Completed })
        val reply=events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(reply.contains("青竹")); assertTrue(reply.contains("800"))
        store.finishRun(run,reply,RunStatus.SUCCEEDED)
        assertEquals(before.size+2,store.messages(c.id).size)
    }
}
