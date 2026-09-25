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
        require(request.id.isNotBlank()) { localizedText("问题 ID 不能为空", "Question ID cannot be empty.") }
        require(request.conversationId.isNotBlank()) { localizedText("对话 ID 不能为空", "Conversation ID cannot be empty.") }
        require(request.question.isNotBlank() && request.question.length <= 500) {
            localizedText("问题需要 1 到 500 个字符", "The question must contain 1 to 500 characters.")
        }
        require(request.options.size <= 6 && request.options.all { it.isNotBlank() && it.length <= 100 }) {
            localizedText("选项最多 6 个，每项需要 1 到 100 个字符", "Up to 6 options are allowed, each containing 1 to 100 characters.")
        }
        require(request.options.distinct().size == request.options.size) { localizedText("问题选项不能重复", "Question options cannot be duplicated.") }
        require(request.allowFreeText || request.options.isNotEmpty()) { localizedText("关闭自由输入时必须提供选项", "Options are required when free-form input is disabled.") }

        val waiter = CompletableDeferred<UserQuestionAnswer>()
        gate.withLock {
            check(request.id !in waiters) { localizedText("问题 ID 重复", "Duplicate question ID.") }
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
        require(normalized == null || normalized.length <= 2_000) { localizedText("回答不能超过 2000 个字符", "The answer cannot exceed 2000 characters.") }
        gate.withLock {
            val request = mutableRequests.value[id] ?: return
            require(normalized == null || request.allowFreeText || normalized in request.options) {
                localizedText("回答不在可选范围内", "The answer is not one of the available options.")
            }
            waiters[id]?.complete(UserQuestionAnswer(normalized))
        }
    }
}
