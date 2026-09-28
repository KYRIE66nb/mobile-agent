package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 模型网关韧性装饰器，纯逻辑无 Android 依赖。
 * 组合约定：FailoverGateway(RetryingGateway(主), RetryingGateway(备))——
 * 每个端点先自行重试瞬时故障，彻底失败后才切换备用配置。
 */

/** 请求曾产生过正文/工具调用则不可重试：重发会让已生成的内容重复出现。 */
private fun ModelEvent.isContent() =
    this is ModelEvent.TextDelta || this is ModelEvent.ToolCall || this is ModelEvent.ReasoningDelta

private class AttemptAborted(override val message: String) : Exception(message)

private val HTTP_CODE = Regex("\\(HTTP (\\d{3})\\)")
private val INTERRUPTED_MARKERS = listOf("中断", "interrupted", "timeout", "timed out")

/**
 * 只把明确瞬时性的失败视为可重试：408/429/5xx、连接层中断与超时。
 * 401/403/404/413 等配置类错误立即失败，重试只会浪费额度。
 */
internal fun isRetryableModelError(message: String): Boolean {
    val code = HTTP_CODE.find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (code != null) return code == 408 || code == 429 || code in 500..599
    return INTERRUPTED_MARKERS.any { message.contains(it, ignoreCase = true) }
}

/**
 * 对上游请求做指数退避重试。一旦本轮已产出正文/工具调用就放弃重试并透传失败，
 * 避免重复文本污染输出；第 maxAttempts 次失败原样透传错误事件。
 */
class RetryingGateway(
    private val upstream: ChatModelGateway,
    private val maxAttempts: Int = 3,
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 8_000,
    /** 测试注入即时休眠；生产为 delay。 */
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : ChatModelGateway {

    override fun stream(request: ChatRequest): Flow<ModelEvent> = flow {
        var attempt = 0
        while (true) {
            var sawContent = false
            var retryReason: String? = null
            // emit 期间下游抛回的异常会顺着 collect 传回本 catch；用标志位与上游故障区分开，一律上抛。
            var emitting = false
            try {
                upstream.stream(request).collect { event ->
                    if (event is ModelEvent.Error && !sawContent &&
                        attempt < maxAttempts - 1 && isRetryableModelError(event.message)
                    ) throw AttemptAborted(event.message)
                    if (event.isContent()) sawContent = true
                    emitting = true
                    emit(event)
                    emitting = false
                }
            } catch (aborted: AttemptAborted) {
                if (emitting) throw aborted
                retryReason = aborted.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (emitting) throw failure
                if (!sawContent && attempt < maxAttempts - 1) {
                    // 网关实现把网络层错误抛成异常而非事件时同样按瞬时故障重试。
                    retryReason = failure.message ?: failure.javaClass.simpleName
                } else {
                    emit(
                        ModelEvent.Error(
                            failure.message ?: localizedText(
                                "回复意外中断，已生成的内容已保留",
                                "The response was interrupted. Generated content was preserved.",
                            )
                        )
                    )
                    return@flow
                }
            }
            if (retryReason == null) return@flow
            attempt++
            AgentLog.i("Gateway") { "model_request_retry attempt=${attempt + 1}/$maxAttempts reason=$retryReason" }
            sleeper((initialBackoffMs shl (attempt - 1)).coerceAtMost(maxBackoffMs))
        }
    }
}

/**
 * 主端点失败且尚未产出任何内容时整体切换备用端点重发同一请求。
 * 备用端点的流原样透传（其内部可再包 RetryingGateway 处理瞬时故障）。
 */
class FailoverGateway(
    private val primary: ChatModelGateway,
    private val backup: ChatModelGateway,
) : ChatModelGateway {

    override fun stream(request: ChatRequest): Flow<ModelEvent> = flow {
        var sawContent = false
        var failed = false
        // 与 RetryingGateway 相同的防护：emit 期间下游异常原样上抛，不得误判为主端点失败。
        var emitting = false
        try {
            primary.stream(request).collect { event ->
                if (event is ModelEvent.Error && !sawContent) {
                    // 吞掉尚未产出内容时的错误事件，让上游流自然结束后再整体切换端点。
                    failed = true
                } else {
                    if (event.isContent()) sawContent = true
                    emitting = true
                    emit(event)
                    emitting = false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (emitting) throw failure
            failed = true
        }
        if (!failed) return@flow
        if (sawContent) {
            // 已产出内容的失败只能如实上报：换端点重发会重复已生成的正文。
            emit(
                ModelEvent.Error(
                    localizedText(
                        "回复意外中断，已生成的内容已保留",
                        "The response was interrupted. Generated content was preserved.",
                    )
                )
            )
            return@flow
        }
        AgentLog.i("Gateway") { "primary model failed before any content; failing over to backup profile" }
        try {
            backup.stream(request).collect { emit(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit(
                ModelEvent.Error(
                    failure.message ?: localizedText(
                        "备用模型请求失败",
                        "The backup model request failed.",
                    )
                )
            )
        }
    }
}
