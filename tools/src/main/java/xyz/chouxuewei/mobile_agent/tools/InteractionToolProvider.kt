package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolAvailability
import xyz.chouxuewei.mobile_agent.core.ToolAvailabilityState
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest

/** 业务澄清入口；工具授权仍由 ChatRuntime 的审批流程独立负责。 */
class InteractionToolProvider(private val questions: UserQuestionBroker) : ToolProvider {
    override val id = "interaction"
    override val title get() = localizedText("询问用户", "Ask user")
    override val description get() = localizedText("需求存在关键歧义时暂停任务并向你提问，收到回答后继续。", "Pause and ask when the request has a critical ambiguity, then continue after receiving an answer.")
    override val definitions get() = listOf(
        ToolDefinition(
            id = "ask_user",
            title = localizedText("询问用户", "Ask user"),
            description = localizedText("仅当缺少的信息会实质改变任务目标、范围或结果时调用。一次只问一个清晰问题，可提供互斥选项并允许自由输入。不要用于工具授权，也不要索要密码、验证码、支付信息、API 密钥或账号安全凭据。用户回答后继续原任务；用户暂不回答时说明尚缺什么。", "Call only when missing information would materially change the task goal, scope, or result. Ask one clear question at a time, optionally with mutually exclusive choices and free-form input. Do not use for tool approval or ask for passwords, verification codes, payment information, API keys, or account security credentials. Continue the original task after the answer; if the user declines, explain what is still missing."),
            inputSchema = localizedJsonSchema("""{"type":"object","properties":{"question":{"type":"string","minLength":1,"maxLength":500,"description":localizedText("需要用户回答的一个明确问题", "One clear question for the user")},"options":{"type":"array","items":{"type":"string","minLength":1,"maxLength":100},"maxItems":6,"uniqueItems":true,"description":localizedText("可选的互斥答案；不确定时可省略", "Optional mutually exclusive answers; omit when uncertain")},"allow_free_text":{"type":"boolean","default":true,"description":localizedText("是否允许用户输入自定义答案", "Whether the user may enter a custom answer")}},"required":["question"],"additionalProperties":false}"""),
            sideEffect = ToolSideEffect.READ,
            providerId = id,
            approvalDescription = localizedText("等待用户补充关键信息。", "Wait for the user to provide key information."),
            requiresPermissionApproval = false,
        ),
    )

    override suspend fun availability() = ToolAvailability(ToolAvailabilityState.AVAILABLE)

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        require(call.toolId == "ask_user") { localizedText("询问工具不支持 ${call.toolId}", "Question tools do not support ${call.toolId}") }
        val args = call.arguments()
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val options = args["options"]?.jsonArray
            ?.map { it.jsonPrimitive.content.trim() }
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val allowFreeText = args["allow_free_text"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val requestId = requireNotNull(context.toolCallRecordId) { localizedText("询问工具缺少调用记录", "The question tool call record is missing.") }
        val answer = questions.ask(UserQuestionRequest(
            id = requestId,
            conversationId = context.conversationId,
            question = question,
            options = options,
            allowFreeText = allowFreeText,
        ))
        ToolResult(
            content = buildJsonObject {
                put("answered", answer.answered)
                answer.value?.let { put("answer", it) }
                putJsonArray("offered_options") { options.forEach { add(JsonPrimitive(it)) } }
            }.toString(),
            summary = if (answer.answered) localizedText("用户已回答", "User answered") else localizedText("用户暂未回答", "User has not answered yet"),
        )
    }
}
