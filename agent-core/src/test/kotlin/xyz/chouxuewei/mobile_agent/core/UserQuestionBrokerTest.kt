package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserQuestionBrokerTest {
    @Test fun publishesQuestionAndReturnsOnlyFirstAnswer() = runBlocking {
        val broker = UserQuestionBroker()
        val pending = async {
            broker.ask(UserQuestionRequest("q1", "c1", "你希望保存到哪里？", listOf("本机", "云端")))
        }
        while (broker.requests.value.isEmpty()) yield()

        assertEquals("你希望保存到哪里？", broker.requests.value.getValue("q1").question)
        broker.respond("q1", "本机")
        broker.respond("q1", "云端")

        assertEquals("本机", pending.await().value)
        assertTrue(broker.requests.value.isEmpty())
    }

    @Test fun dismissingQuestionReturnsUnanswered() = runBlocking {
        val broker = UserQuestionBroker()
        val pending = async {
            broker.ask(UserQuestionRequest("q2", "c1", "是否继续？", listOf("继续"), false))
        }
        while (broker.requests.value.isEmpty()) yield()

        broker.respond("q2", null)

        assertFalse(pending.await().answered)
        assertTrue(broker.requests.value.isEmpty())
    }
}
