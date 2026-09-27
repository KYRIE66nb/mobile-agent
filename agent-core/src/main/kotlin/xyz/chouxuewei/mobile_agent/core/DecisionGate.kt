package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/** 决策闸对一次工具调用的裁决。 */
sealed interface GateVerdict {
    data object Allow : GateVerdict
    /** 需要用户确认；进入与手动审批相同的确认通道。 */
    data class Confirm(val reason: String) : GateVerdict
    /** 明确危险；不执行并把原因回传给模型。 */
    data class Block(val reason: String) : GateVerdict
}

data class GateRequest(
    val userRequest: String,
    val toolId: String,
    val toolTitle: String,
    val argumentsSummary: String,
)

/**
 * 操作安全闸：在执行前对有外部副作用的工具调用做语义级裁决。
 * 后端可替换：默认用当前对话模型回答一道有界选择题；以后可换成专用决策模型服务。
 */
interface DecisionGate {
    suspend fun gate(request: GateRequest): GateVerdict
}

/** 只有会产生外部副作用或破坏性的动作需要过闸；读操作直接放行。 */
val GATED_SIDE_EFFECTS: Set<ToolSideEffect> =
    setOf(ToolSideEffect.EXTERNAL_WRITE, ToolSideEffect.DESTRUCTIVE)

/**
 * 用当前对话模型实现的决策闸。不生成自由文本，只解析一行 JSON 裁决，
 * 超时或解析失败时降级为 Confirm（交还给用户判断），保证 fail-safe。
 */
class LlmDecisionGate(
    private val gateway: ChatModelGateway,
    private val timeoutMs: Long = 20_000,
) : DecisionGate {

    override suspend fun gate(request: GateRequest): GateVerdict = runCatching {
        withTimeout(timeoutMs) { decide(request) }
    }.getOrElse {
        AgentLog.w("DecisionGate") { "gate check fell back to confirm: ${it.message}" }
        GateVerdict.Confirm(localizedText("安全检查未完成，需要你确认", "The safety check did not finish. Your confirmation is required."))
    }

    private suspend fun decide(request: GateRequest): GateVerdict {
        val prompt = buildPrompt(request)
        val events = gateway.stream(
            ChatRequest(
                messages = listOf(ChatTurn(role = "user", content = prompt)),
                maxOutputTokens = 256,
                tools = emptyList(),
            ),
        ).toList()
        events.filterIsInstance<ModelEvent.Error>().firstOrNull()?.let {
            return GateVerdict.Confirm(it.message.take(120))
        }
        val text = events.filterIsInstance<ModelEvent.TextDelta>()
            .joinToString("") { it.text }
        return parse(text)
    }

    companion object {
        private val JSON_PATTERN = Regex("\\{[^{}]*\\}")

        fun buildPrompt(request: GateRequest): String = localizedText(
            """你是手机操作安全裁决器。判断下面这个动作是否可以直接执行，只输出一行 JSON，不要输出任何其它内容：
{"decision":"allow|confirm|block","reason":"不超过40字的原因"}
裁决规则：
- allow：普通操作，如打开应用、点击浏览、滚动、输入普通文本、等待。
- confirm：有外部影响或不易撤销，如发送消息或评论、发布内容、修改系统设置、删除或覆盖数据、提交含个人信息的表单、登录注册。
- block：高风险不可逆操作，如支付转账充值、填写验证码或密码、账号安全操作、解除授权、格式化或清空全部数据。
拿不准时输出 confirm，不要输出 block 之外的长解释。
用户请求：${request.userRequest.take(300)}
待执行动作：${request.toolTitle}
参数摘要：${request.argumentsSummary.take(500)}""",
            """You are a phone-operation safety judge. Decide whether the action below can run directly. Output exactly one line of JSON and nothing else:
{"decision":"allow|confirm|block","reason":"reason within 40 chars"}
Rules:
- allow: ordinary actions such as opening apps, tapping, scrolling, entering plain text, waiting.
- confirm: outward-facing or hard to undo, such as sending messages or comments, publishing content, changing system settings, deleting or overwriting data, submitting forms with personal info, signing in or up.
- block: high-risk irreversible actions such as payments or transfers, entering verification codes or passwords, account security operations, revoking authorization, wiping or formatting data.
When unsure, answer confirm.
User request: ${request.userRequest.take(300)}
Pending action: ${request.toolTitle}
Arguments summary: ${request.argumentsSummary.take(500)}""",
        )

        fun parse(text: String): GateVerdict {
            val json = JSON_PATTERN.find(text)?.value ?: return GateVerdict.Confirm(
                localizedText("安全裁决结果无法解析", "Could not parse the safety verdict.")
            )
            val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")
                .find(json)?.groupValues?.get(1)?.take(120).orEmpty()
            return when (Regex("\"decision\"\\s*:\\s*\"(\\w+)\"").find(json)?.groupValues?.get(1)?.lowercase()) {
                "allow" -> GateVerdict.Allow
                "block" -> GateVerdict.Block(reason.ifBlank {
                    localizedText("该操作被安全策略阻止", "The action was blocked by the safety policy.")
                })
                else -> GateVerdict.Confirm(reason.ifBlank {
                    localizedText("该操作需要你确认", "This action needs your confirmation.")
                })
            }
        }
    }
}
