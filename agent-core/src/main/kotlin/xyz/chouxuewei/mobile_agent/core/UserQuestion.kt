package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AI 暂停当前任务并等待用户补充信息时展示的业务问题。
 * 它与工具授权严格分开：回答问题不能隐式批准任何有副作用的操作。
 */
data class UserQuestionRequest(
    val id: String,
    val conversationId: String,
    val question: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = true,
)

data class UserQuestionAnswer(val value: String?) {
    val answered: Boolean get() = value != null
}

/** Application 级状态所有者，保证应用内弹窗和后台悬浮窗只能完成同一次等待。 */
class UserQuestionBroker {
    private val gate = Mutex()
    private val waiters = mutableMapOf<String, CompletableDeferred<UserQuestionAnswer>>()
    private val mutableRequests = MutableStateFlow<Map<String, UserQuestionRequest>>(emptyMap())
    val requests: StateFlow<Map<String, UserQuestionRequest>> = mutableRequests

    suspend fun ask(request: UserQuestionRequest): UserQuestionAnswer {
        require(request.id.isNotBlank()) { "问题 ID 不能为空" }
        require(request.conversationId.isNotBlank()) { "对话 ID 不能为空" }
        require(request.question.isNotBlank() && request.question.length <= 500) {
            "问题需要 1 到 500 个字符"
        }
        require(request.options.size <= 6 && request.options.all { it.isNotBlank() && it.length <= 100 }) {
            "选项最多 6 个，每项需要 1 到 100 个字符"
        }
        require(request.options.distinct().size == request.options.size) { "问题选项不能重复" }
        require(request.allowFreeText || request.options.isNotEmpty()) { "关闭自由输入时必须提供选项" }

        val waiter = CompletableDeferred<UserQuestionAnswer>()
        gate.withLock {
            check(request.id !in waiters) { "问题 ID 重复" }
            waiters[request.id] = waiter
            mutableRequests.update { it + (request.id to request) }
        }
        return try {
            waiter.await()
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    waiters.remove(request.id)
                    mutableRequests.update { it - request.id }
                }
            }
        }
    }

    suspend fun respond(id: String, value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length <= 2_000) { "回答不能超过 2000 个字符" }
        gate.withLock {
            val request = mutableRequests.value[id] ?: return
            require(normalized == null || request.allowFreeText || normalized in request.options) {
                "回答不在可选范围内"
            }
            waiters[id]?.complete(UserQuestionAnswer(normalized))
        }
    }
}
