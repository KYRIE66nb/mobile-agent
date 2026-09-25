package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

class HistoryToolProvider(private val conversations: ConversationStore) : ToolProvider {
    override val id = "history"
    override val title get() = localizedText("对话记录", "Conversation history")
    override val description get() = localizedText("根据你的要求，在当前对话或其他历史对话中查找消息。", "Find messages in the current or other conversations according to the request.")
    override val definitions get() = listOf(
        ToolDefinition(
            id = "history_search",
            title = localizedText("搜索对话记录", "Search conversation history"),
            description = localizedText("搜索当前会话的原始消息；只有用户明确要求查询其他或全部历史会话时才能使用 scope=all。结果中的 snippet 只是脱敏片段，引用准确原文前必须把返回的 message_id 交给 history_read。", "Search original messages in the current conversation. Use scope=all only when the user explicitly requests other or all conversations. A result snippet is redacted; pass its message_id to history_read before quoting exact text."),
            inputSchema = localizedJsonSchema("""{"type":"object","properties":{"query":{"type":"string","minLength":2,"maxLength":500,"description":localizedText("要查找的关键词或短语", "Keyword or phrase to find")},"scope":{"type":"string","enum":["current","all"],"default":"current","description":localizedText("默认仅搜索当前会话；all 需要用户明确要求跨会话查询", "Search the current conversation by default; all requires an explicit request to search across conversations")},"limit":{"type":"integer","minimum":1,"maximum":20,"default":8,"description":localizedText("最多返回的匹配数", "Maximum number of matches")}},"required":["query"],"additionalProperties":false}"""),
            sideEffect = ToolSideEffect.READ,
            providerId = "history",
            approvalDescription = localizedText("在对话记录中查找相关消息。", "Find relevant messages in conversation history."),
        ),
        ToolDefinition(
            id = "history_read",
            title = localizedText("读取历史消息", "Read history messages"),
            description = localizedText("二选一：按 history_search 返回的 message_ids 读取准确原文，或按工具结果引用 tool_call_id 分段读取长结果。读取被截断的工具结果时使用返回的 next_offset 继续，不要同时传两类标识。", "Choose one: read exact messages by message_ids from history_search, or read a long tool result in chunks by tool_call_id. Continue truncated results with next_offset, and never provide both identifier types."),
            inputSchema = localizedJsonSchema("""{"type":"object","properties":{"message_ids":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":10,"description":localizedText("history_search 返回的真实消息 ID", "Actual message ID returned by history_search")},"tool_call_id":{"type":"string","description":localizedText("长工具结果提示中返回的真实工具调用 ID", "Actual tool call ID returned in the long-result notice")},"offset":{"type":"integer","minimum":0,"default":0,"description":localizedText("仅用于分段读取工具结果", "Only for chunked tool-result reads")},"max_chars":{"type":"integer","minimum":1,"maximum":8000,"default":8000},"scope":{"type":"string","enum":["current","all"],"default":"current","description":localizedText("all 需要用户明确要求跨会话读取", "all requires an explicit request for cross-conversation reads")}},"oneOf":[{"required":["message_ids"]},{"required":["tool_call_id"]}],"additionalProperties":false}"""),
            sideEffect = ToolSideEffect.READ,
            providerId = "history",
            approvalDescription = localizedText("读取已找到的历史消息内容。", "Read the content of found history messages."),
        ),
    )

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "history_search" -> search(args, context)
            "history_read" -> read(args, context)
            else -> error(localizedText("历史工具不支持 ${call.toolId}", "History tools do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "history_search" -> args["query"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.take(120)?.let { localizedText("搜索内容：$it", "Search for: $it") }
            "history_read" -> args["message_ids"]?.jsonArray?.size
                ?.let { localizedText("读取 $it 条历史消息", "Read $it history messages") } ?: localizedText("读取历史消息详情", "Read history message details")
            else -> null
        }
    }.getOrNull()

    private suspend fun search(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(query.length in 2..500) { localizedText("搜索内容需要 2 到 500 个字符", "The search text must contain 2 to 500 characters.") }
        val all = args["scope"]?.jsonPrimitive?.content == "all"
        require(!all || explicitlyRequestsAllHistory(context.userRequest)) {
            localizedText("只有用户明确要求搜索其他或全部历史会话时才能跨会话搜索", "Cross-conversation searches require an explicit request to search other or all conversations.")
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
        return ToolResult(body, if (matches.isEmpty()) localizedText("没有找到相关历史消息", "No relevant history messages were found.") else localizedText("找到 ${matches.size} 条历史消息", "Found ${matches.size} history messages"))
    }

    private suspend fun read(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val ids = args["message_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.distinct().orEmpty()
        val toolCallId = args["tool_call_id"]?.jsonPrimitive?.contentOrNull?.trim()
        require((ids.isNotEmpty() && ids.size <= 10) xor !toolCallId.isNullOrBlank()) {
            localizedText("请提供 1 到 10 条消息 ID，或单独提供一个工具调用 ID", "Provide 1 to 10 message IDs, or one tool call ID by itself.")
        }
        val all = args["scope"]?.jsonPrimitive?.content == "all"
        require(!all || explicitlyRequestsAllHistory(context.userRequest)) {
            localizedText("只有用户明确要求搜索其他或全部历史会话时才能跨会话读取", "Cross-conversation reads require an explicit request to search other or all conversations.")
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
        return ToolResult(body, if (messages.isEmpty()) localizedText("没有找到指定的历史消息", "The specified history messages were not found.") else localizedText("已读取 ${messages.size} 条历史消息", "Read ${messages.size} history messages"))
    }

    private suspend fun readToolResult(
        args: JsonObject,
        context: ToolExecutionContext,
        toolCallId: String,
        all: Boolean,
    ): ToolResult {
        val call = conversations.toolCall(toolCallId)
            ?.takeIf { all || it.conversationId == context.conversationId }
            ?: error(localizedText("指定工具结果不可用", "The specified tool result is unavailable."))
        val full = call.result ?: call.error.orEmpty()
        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        require(offset <= full.length) { localizedText("offset 超出工具结果长度", "The offset is beyond the tool result length.") }
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
        return ToolResult(body, localizedText("已读取工具结果中的 ${content.length} 个字符", "Read ${content.length} characters from the tool result"))
    }

    private fun explicitlyRequestsAllHistory(text: String): Boolean =
        Regex(
            "(?i)历史会话|历史聊天|全部.{0,4}(会话|聊天|历史)|所有.{0,4}(会话|聊天|历史)|" +
                "其他.{0,4}(会话|聊天)|以前.{0,4}(会话|聊过)|跨会话|" +
                "all.{0,8}(conversations|chats|history)|other.{0,8}(conversations|chats)|" +
                "previous.{0,8}(conversations|chats)|across.{0,8}(conversations|chats)",
        )
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
