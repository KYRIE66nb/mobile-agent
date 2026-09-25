package xyz.chouxuewei.mobile_agent.tools

import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

class HistoryToolProvider(private val conversations: ConversationStore) : ToolProvider {
    override val id = "history"
    override val title = "对话记录"
    override val description = "根据你的要求，在当前对话或其他历史对话中查找消息。"
    override val definitions = listOf(
        ToolDefinition(
            id = "history_search",
            title = "搜索对话记录",
            description = "搜索当前会话的原始消息；只有用户明确要求查询其他或全部历史会话时才能使用 scope=all。结果中的 snippet 只是脱敏片段，引用准确原文前必须把返回的 message_id 交给 history_read。",
            inputSchema = """{"type":"object","properties":{"query":{"type":"string","minLength":2,"maxLength":500,"description":"要查找的关键词或短语"},"scope":{"type":"string","enum":["current","all"],"default":"current","description":"默认仅搜索当前会话；all 需要用户明确要求跨会话查询"},"limit":{"type":"integer","minimum":1,"maximum":20,"default":8,"description":"最多返回的匹配数"}},"required":["query"],"additionalProperties":false}""",
            sideEffect = ToolSideEffect.READ,
            providerId = "history",
            approvalDescription = "在对话记录中查找相关消息。",
        ),
        ToolDefinition(
            id = "history_read",
            title = "读取历史消息",
            description = "二选一：按 history_search 返回的 message_ids 读取准确原文，或按工具结果引用 tool_call_id 分段读取长结果。读取被截断的工具结果时使用返回的 next_offset 继续，不要同时传两类标识。",
            inputSchema = """{"type":"object","properties":{"message_ids":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":10,"description":"history_search 返回的真实消息 ID"},"tool_call_id":{"type":"string","description":"长工具结果提示中返回的真实工具调用 ID"},"offset":{"type":"integer","minimum":0,"default":0,"description":"仅用于分段读取工具结果"},"max_chars":{"type":"integer","minimum":1,"maximum":8000,"default":8000},"scope":{"type":"string","enum":["current","all"],"default":"current","description":"all 需要用户明确要求跨会话读取"}},"oneOf":[{"required":["message_ids"]},{"required":["tool_call_id"]}],"additionalProperties":false}""",
            sideEffect = ToolSideEffect.READ,
            providerId = "history",
            approvalDescription = "读取已找到的历史消息内容。",
        ),
    )

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "history_search" -> search(args, context)
            "history_read" -> read(args, context)
            else -> error("历史工具不支持 ${call.toolId}")
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "history_search" -> args["query"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.take(120)?.let { "搜索内容：$it" }
            "history_read" -> args["message_ids"]?.jsonArray?.size
                ?.let { "读取 $it 条历史消息" } ?: "读取历史消息详情"
            else -> null
        }
    }.getOrNull()

    private suspend fun search(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(query.length in 2..500) { "搜索内容需要 2 到 500 个字符" }
        val all = args["scope"]?.jsonPrimitive?.content == "all"
        require(!all || explicitlyRequestsAllHistory(context.userRequest)) {
            "只有用户明确要求搜索其他或全部历史会话时才能跨会话搜索"
        }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 8).coerceIn(1, 20)
        val matches = conversations.searchMessages(query, if (all) null else context.conversationId, limit)
        val body = buildJsonObject {
            put("query", query)
            put("scope", if (all) "all" else "current")
            putJsonArray("matches") {
                matches.forEach { match -> add(buildJsonObject {
                    put("message_id", match.messageId)
                    put("conversation_id", match.conversationId)
                    put("conversation_title", match.conversationTitle)
                    put("role", match.role.name.lowercase())
                    put("created_at", match.createdAt)
                    put("snippet", maskSecrets(match.text).take(320))
                }) }
            }
        }.toString()
        return ToolResult(body, if (matches.isEmpty()) "没有找到相关历史消息" else "找到 ${matches.size} 条历史消息")
    }

    private suspend fun read(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val ids = args["message_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.distinct().orEmpty()
        val toolCallId = args["tool_call_id"]?.jsonPrimitive?.contentOrNull?.trim()
        require((ids.isNotEmpty() && ids.size <= 10) xor !toolCallId.isNullOrBlank()) {
            "请提供 1 到 10 条消息 ID，或单独提供一个工具调用 ID"
        }
        val all = args["scope"]?.jsonPrimitive?.content == "all"
        require(!all || explicitlyRequestsAllHistory(context.userRequest)) {
            "只有用户明确要求搜索其他或全部历史会话时才能跨会话读取"
        }
        if (!toolCallId.isNullOrBlank()) return readToolResult(args, context, toolCallId, all)
        val messages = conversations.messagesByIds(ids).filter { all || it.conversationId == context.conversationId }
        val revealSecrets = Regex("(?i)key|token|secret|password|密码|密钥|令牌").containsMatchIn(context.userRequest)
        val body = buildJsonObject {
            putJsonArray("messages") {
                messages.forEach { message -> add(buildJsonObject {
                    put("message_id", message.id)
                    put("conversation_id", message.conversationId)
                    put("role", message.role.name.lowercase())
                    put("created_at", message.createdAt)
                    put("text", if (revealSecrets) message.text else maskSecrets(message.text))
                }) }
            }
        }.toString()
        return ToolResult(body, if (messages.isEmpty()) "没有找到指定的历史消息" else "已读取 ${messages.size} 条历史消息")
    }

    private suspend fun readToolResult(
        args: JsonObject,
        context: ToolExecutionContext,
        toolCallId: String,
        all: Boolean,
    ): ToolResult {
        val call = conversations.toolCall(toolCallId)
            ?.takeIf { all || it.conversationId == context.conversationId }
            ?: error("指定工具结果不可用")
        val full = call.result ?: call.error.orEmpty()
        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        require(offset <= full.length) { "offset 超出工具结果长度" }
        val maxChars = (args["max_chars"]?.jsonPrimitive?.intOrNull ?: 8_000).coerceIn(1, 8_000)
        val revealSecrets = Regex("(?i)key|token|secret|password|密码|密钥|令牌").containsMatchIn(context.userRequest)
        val visible = if (revealSecrets) full else maskSecrets(full)
        val content = visible.drop(offset).take(maxChars)
        val hasMore = offset + content.length < visible.length
        val body = buildJsonObject {
            put("tool_call_id", call.id)
            put("tool_id", call.toolId)
            put("offset", offset)
            put("content", content)
            put("truncated", hasMore)
            if (hasMore) put("next_offset", offset + content.length)
        }.toString()
        return ToolResult(body, "已读取工具结果中的 ${content.length} 个字符")
    }

    private fun explicitlyRequestsAllHistory(text: String): Boolean =
        Regex("历史会话|历史聊天|全部.{0,4}(会话|聊天|历史)|所有.{0,4}(会话|聊天|历史)|其他.{0,4}(会话|聊天)|以前.{0,4}(会话|聊过)|跨会话")
            .containsMatchIn(text)

    /** 普通历史查询不给模型回传完整凭据，避免一次检索扩大秘密暴露范围。 */
    private fun maskSecrets(text: String): String = text.replace(
        Regex("(?i)(api[_ -]?key|token|secret|password|密码|密钥)\\s*[:：=]\\s*([^\\s,，;；]+)"),
    ) { match -> "${match.groupValues[1]}: ${mask(match.groupValues[2])}" }

    private fun mask(value: String): String = when {
        value.length <= 4 -> "****"
        else -> value.take(2) + "****" + value.takeLast(2)
    }
}
