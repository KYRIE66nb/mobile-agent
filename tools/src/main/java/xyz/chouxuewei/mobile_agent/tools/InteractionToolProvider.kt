package xyz.chouxuewei.mobile_agent.tools

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
    override val title = "询问用户"
    override val description = "需求存在关键歧义时暂停任务并向你提问，收到回答后继续。"
    override val definitions = listOf(
        ToolDefinition(
            id = "ask_user",
            title = "询问用户",
            description = "仅当缺少的信息会实质改变任务目标、范围或结果时调用。一次只问一个清晰问题，可提供互斥选项并允许自由输入。不要用于工具授权，也不要索要密码、验证码、支付信息、API 密钥或账号安全凭据。用户回答后继续原任务；用户暂不回答时说明尚缺什么。",
            inputSchema = """{"type":"object","properties":{"question":{"type":"string","minLength":1,"maxLength":500,"description":"需要用户回答的一个明确问题"},"options":{"type":"array","items":{"type":"string","minLength":1,"maxLength":100},"maxItems":6,"uniqueItems":true,"description":"可选的互斥答案；不确定时可省略"},"allow_free_text":{"type":"boolean","default":true,"description":"是否允许用户输入自定义答案"}},"required":["question"],"additionalProperties":false}""",
            sideEffect = ToolSideEffect.READ,
            providerId = id,
            approvalDescription = "等待用户补充关键信息。",
            requiresPermissionApproval = false,
        ),
    )

    override suspend fun availability() = ToolAvailability(ToolAvailabilityState.AVAILABLE)

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        require(call.toolId == "ask_user") { "询问工具不支持 ${call.toolId}" }
        val args = call.arguments()
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val options = args["options"]?.jsonArray
            ?.map { it.jsonPrimitive.content.trim() }
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val allowFreeText = args["allow_free_text"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val requestId = requireNotNull(context.toolCallRecordId) { "询问工具缺少调用记录" }
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
            summary = if (answer.answered) "用户已回答" else "用户暂未回答",
        )
    }
}
