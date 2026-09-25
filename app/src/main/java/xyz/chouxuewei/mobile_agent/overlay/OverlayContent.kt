package xyz.chouxuewei.mobile_agent.overlay

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.chat.ChatIcon
import xyz.chouxuewei.mobile_agent.chat.ComposerDraft
import xyz.chouxuewei.mobile_agent.chat.ReplyBody
import xyz.chouxuewei.mobile_agent.core.Conversation
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.MessageRole
import xyz.chouxuewei.mobile_agent.core.MessageStatus
import xyz.chouxuewei.mobile_agent.core.ThemePreference
import xyz.chouxuewei.mobile_agent.core.ToolApprovalRequest
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest
import xyz.chouxuewei.mobile_agent.core.localizedText
import xyz.chouxuewei.mobile_agent.ui.theme.LocalChatColors
import xyz.chouxuewei.mobile_agent.ui.theme.Mobile_agentTheme
import xyz.chouxuewei.mobile_agent.voice.VoiceInputState
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock

internal data class OverlayViewState(
    val presentation: OverlayPresentation = OverlayPresentation.EDGE_HANDLE,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val selectedConversationId: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val messages: List<Message> = emptyList(),
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val toolTitles: Map<String, String> = emptyMap(),
    val draft: ComposerDraft = ComposerDraft(),
    val activeConversations: Set<String> = emptySet(),
    val approvals: List<ToolApprovalRequest> = emptyList(),
    val questions: List<UserQuestionRequest> = emptyList(),
    val mode: ExecutionMode? = null,
    val stopping: Boolean = false,
    val completionConversationId: String? = null,
    val summaryTitle: String = localizedText("悬浮助手", "Floating assistant"),
    val summaryDetail: String = localizedText("暂无进行中的任务", "No active tasks"),
    val modelConfigured: Boolean = true,
    val resizeHint: String? = null,
    val dockedAtStart: Boolean = false,
    val voiceInputState: VoiceInputState = VoiceInputState.Idle,
)

internal interface OverlayActions {
    fun expandSummary()
    fun expandFullChat()
    fun collapseOneLevel()
    fun openConversation()
    fun selectConversation(id: String)
    fun editDraft(draft: ComposerDraft)
    fun sendMessage()
    fun startVoiceInput(): Boolean
    fun finishVoiceInput(action: OverlayVoiceReleaseAction)
    fun stopCurrent()
    fun decideApproval(request: ToolApprovalRequest, allow: Boolean, choiceId: String?, permanently: Boolean)
    fun answerQuestion(request: UserQuestionRequest, answer: String?)
    fun dragEdge(dx: Float, dy: Float, finished: Boolean)
    fun dragSummary(dx: Float, dy: Float, finished: Boolean)
    fun dragWindow(dx: Float, dy: Float, finished: Boolean)
    fun resize(corner: ResizeCorner, dx: Float, dy: Float, finished: Boolean)
}

internal enum class ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

internal enum class OverlayVoiceReleaseAction { SEND_CURRENT, CANCEL, SEND_NEW_CONVERSATION }

/**
 * 手指越过上下阈值才切换动作；已经进入某个区域后保留少量迟滞，避免边界抖动让状态反复闪烁。
 */
internal fun nextOverlayVoiceReleaseAction(
    current: OverlayVoiceReleaseAction,
    verticalOffset: Float,
    threshold: Float,
    hysteresis: Float = threshold * .2f,
): OverlayVoiceReleaseAction {
    require(threshold > 0f) { localizedText("threshold 必须大于 0", "threshold must be greater than 0") }
    require(hysteresis in 0f..threshold) { localizedText("hysteresis 必须位于 0..threshold", "hysteresis must be within 0..threshold") }
    val returnBoundary = threshold - hysteresis
    return when {
        verticalOffset <= -threshold -> OverlayVoiceReleaseAction.CANCEL
        verticalOffset >= threshold -> OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION
        current == OverlayVoiceReleaseAction.CANCEL && verticalOffset < -returnBoundary -> current
        current == OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION && verticalOffset > returnBoundary -> current
        else -> OverlayVoiceReleaseAction.SEND_CURRENT
    }
}

@Composable
internal fun OverlayContent(state: OverlayViewState, actions: OverlayActions) {
    Mobile_agentTheme(state.theme) {
        Crossfade(
            targetState = state.presentation,
            animationSpec = tween(durationMillis = 140),
            label = "overlay-presentation",
        ) { presentation ->
            when (presentation) {
                OverlayPresentation.EDGE_HANDLE -> EdgeHandle(state, actions)
                OverlayPresentation.SUMMARY -> SummaryOverlay(state, actions)
                OverlayPresentation.FULL_CHAT -> FullChatOverlay(state, actions)
            }
        }
    }
}

@Composable
private fun EdgeHandle(state: OverlayViewState, actions: OverlayActions) {
    val colors = LocalChatColors.current
    val haptic = LocalHapticFeedback.current
    var voiceReleaseAction by remember { mutableStateOf(OverlayVoiceReleaseAction.SEND_CURRENT) }
    LaunchedEffect(state.voiceInputState is VoiceInputState.Recording) {
        if (state.voiceInputState !is VoiceInputState.Recording) {
            voiceReleaseAction = OverlayVoiceReleaseAction.SEND_CURRENT
        }
    }
    val status = if (state.voiceInputState is VoiceInputState.Recording) {
        voiceRecordingStatusVisual(voiceReleaseAction)
    } else {
        overlayStatusVisual(state, global = true)
    }
    val capsuleAlignment = if (state.dockedAtStart) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (state.dockedAtStart) {
        RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp, topEnd = 4.dp, bottomEnd = 4.dp)
    }
    Box(
        Modifier.fillMaxSize()
            .testTag("overlay_edge_handle")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var previous = down
                    var distance = Offset.Zero
                    var dragging = false
                    var recording = false
                    var recordingFinalized = false
                    var voiceVerticalOffset = 0f
                    val voiceDirectionThreshold = 48.dp.toPx()
                    val voiceDirectionHysteresis = 10.dp.toPx()
                    val deadline = SystemClock.uptimeMillis() + viewConfiguration.longPressTimeoutMillis
                    try {
                        while (true) {
                            val remaining = deadline - SystemClock.uptimeMillis()
                            val event = if (!dragging && !recording && remaining > 0) {
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else if (!dragging && !recording) {
                                null
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                recording = actions.startVoiceInput()
                                if (recording) {
                                    voiceReleaseAction = OverlayVoiceReleaseAction.SEND_CURRENT
                                    voiceVerticalOffset = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else {
                                    // 配置或权限不足时会打开设置；不要在同一次长按里重复触发。
                                    break
                                }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.position - previous.position
                            distance += delta
                            if (!recording && !dragging && distance.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                            }
                            if (recording && change.pressed) {
                                change.consume()
                                voiceVerticalOffset += delta.y
                                val nextAction = nextOverlayVoiceReleaseAction(
                                    current = voiceReleaseAction,
                                    verticalOffset = voiceVerticalOffset,
                                    threshold = voiceDirectionThreshold,
                                    hysteresis = voiceDirectionHysteresis,
                                )
                                if (nextAction != voiceReleaseAction) {
                                    voiceReleaseAction = nextAction
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            } else if (dragging && change.pressed) {
                                change.consume()
                                actions.dragEdge(delta.x, delta.y, false)
                            }
                            previous = change
                            if (!change.pressed) {
                                change.consume()
                                when {
                                    recording -> {
                                        recordingFinalized = true
                                        actions.finishVoiceInput(voiceReleaseAction)
                                    }
                                    dragging -> actions.dragEdge(0f, 0f, true)
                                    else -> actions.expandSummary()
                                }
                                break
                            }
                        }
                    } finally {
                        // 手势被系统中断或组件移除时必须终止录音，不能留下后台持续采集。
                        if (recording && !recordingFinalized) {
                            actions.finishVoiceInput(OverlayVoiceReleaseAction.CANCEL)
                        }
                        voiceReleaseAction = OverlayVoiceReleaseAction.SEND_CURRENT
                    }
                }
            }
            .semantics {
                contentDescription = localizedText("悬浮助手，${status.label}，点击查看摘要，长按语音输入", "Floating assistant, ${status.label}. Tap for summary, hold for voice input.")
                role = Role.Button
            },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp).align(Alignment.Center),
            shape = shape,
            color = colors.surface.copy(alpha = .94f),
            border = BorderStroke(1.dp, colors.divider),
            shadowElevation = 6.dp,
        ) {
            // 按钮保持一个完整轮廓，只把图标与主要抓握区域放到内侧，减少系统侧滑手势抢占。
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(capsuleAlignment).width(28.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (status.animated) {
                        // 贴边状态面积很小，任务执行和语音转写都用进度环明确表示仍在处理中。
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).testTag("overlay_edge_progress"),
                            color = status.foreground,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        ChatIcon(status.icon, null, Modifier.size(17.dp), status.foreground)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryOverlay(state: OverlayViewState, actions: OverlayActions) {
    val colors = LocalChatColors.current
    Surface(
        Modifier.fillMaxSize()
            .testTag("overlay_summary")
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { actions.dragSummary(0f, 0f, true) },
                    onDragCancel = { actions.dragSummary(0f, 0f, true) },
                ) { change, amount ->
                    change.consume()
                    actions.dragSummary(amount.x, amount.y, false)
                }
            },
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Button, onClick = actions::expandFullChat)
                    .semantics { contentDescription = localizedText("查看完整悬浮对话", "View full floating conversation") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(state, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 6.dp)) {
                    Text(
                        state.summaryTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.summaryDetail,
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.selectedConversationId in state.activeConversations) {
                OverlayIconAction(
                    icon = R.drawable.lucide_square,
                    description = localizedText("停止当前任务", "Stop current task"),
                    foreground = colors.error,
                    container = colors.errorSoft,
                    onClick = actions::stopCurrent,
                )
                Box(Modifier.width(4.dp))
            }
            OverlayIconAction(
                icon = R.drawable.lucide_chevron_right,
                description = if (state.approvals.isNotEmpty() || state.questions.isNotEmpty()) {
                    localizedText("处理待确认内容", "Handle pending items")
                } else {
                    localizedText("展开完整悬浮对话", "Expand full floating conversation")
                },
                foreground = colors.accent,
                container = colors.accentSoft,
                onClick = actions::expandFullChat,
            )
        }
    }
}

@Composable
private fun FullChatOverlay(state: OverlayViewState, actions: OverlayActions) {
    val colors = LocalChatColors.current
    var sessionsOpen by rememberSaveable { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxSize().testTag("overlay_full_chat"),
        shape = RoundedCornerShape(18.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = .72f)),
        shadowElevation = 10.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                FullHeader(
                    state = state,
                    sessionsOpen = sessionsOpen,
                    onToggleSessions = { sessionsOpen = !sessionsOpen },
                    actions = actions,
                )
                Surface(Modifier.fillMaxWidth().height(1.dp), color = colors.divider) {}
                if (sessionsOpen) {
                    ConversationSwitcher(
                        state,
                        onSelect = { id -> sessionsOpen = false; actions.selectConversation(id) },
                        Modifier.weight(1f),
                    )
                } else {
                    OverlayTimeline(state, Modifier.weight(1f))
                    val approval = state.approvals.firstOrNull { it.conversationId == state.selectedConversationId }
                    val question = state.questions.firstOrNull { it.conversationId == state.selectedConversationId }
                    if (approval != null) {
                        ApprovalCard(approval, actions)
                    } else if (question != null) {
                        QuestionCard(question, actions)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        OverlayComposer(state, actions, Modifier.weight(1f))
                        ResizeHandle(
                            ResizeCorner.BOTTOM_RIGHT,
                            Modifier.padding(end = 4.dp, bottom = 9.dp),
                            actions,
                        )
                    }
                }
            }
            state.resizeHint?.let { hint ->
                Surface(
                    Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.text.copy(alpha = .88f),
                ) {
                    Text(
                        hint,
                        Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        color = colors.background,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullHeader(
    state: OverlayViewState,
    sessionsOpen: Boolean,
    onToggleSessions: () -> Unit,
    actions: OverlayActions,
) {
    val colors = LocalChatColors.current
    val current = state.conversations.firstOrNull { it.id == state.selectedConversationId }
    val pendingElsewhere = (state.approvals.map { it.conversationId } + state.questions.map { it.conversationId })
        .distinct().count { it != state.selectedConversationId }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { actions.dragWindow(0f, 0f, true) },
                        onDragCancel = { actions.dragWindow(0f, 0f, true) },
                    ) { change, amount ->
                        change.consume()
                        actions.dragWindow(amount.x, amount.y, false)
                    }
                }
                .clickable(onClick = onToggleSessions),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(state, size = 32.dp)
            Column(Modifier.weight(1f).padding(start = 9.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        current?.title ?: localizedText("选择对话", "Choose conversation"),
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ChatIcon(
                        if (sessionsOpen) R.drawable.lucide_chevron_down else R.drawable.lucide_chevron_right,
                        localizedText("切换对话", "Switch conversation"),
                        Modifier.padding(start = 5.dp).size(16.dp),
                        colors.secondary,
                    )
                }
                if (pendingElsewhere > 0) {
                    Text(localizedText("另有 $pendingElsewhere 个对话需要处理", "$pendingElsewhere other conversations need attention"), color = colors.warning,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OverlayIconAction(
            icon = R.drawable.lucide_chevron_down,
            description = localizedText("收起为任务摘要", "Collapse to task summary"),
            foreground = colors.secondary,
            onClick = actions::collapseOneLevel,
        )
        OverlayIconAction(
            icon = R.drawable.lucide_external_link,
            description = localizedText("在 Mobile Agent 中打开", "Open in Mobile Agent"),
            foreground = colors.accent,
            onClick = actions::openConversation,
        )
    }
}

@Composable
private fun ConversationSwitcher(
    state: OverlayViewState,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val priorityIds = buildList {
        state.approvals.forEach { add(it.conversationId) }
        state.questions.forEach { add(it.conversationId) }
        state.activeConversations.forEach { add(it) }
    }.distinct()
    val byId = state.conversations.associateBy(Conversation::id)
    val priority = priorityIds.mapNotNull(byId::get)
    val recent = state.conversations.sortedByDescending(Conversation::updatedAt)
        .filterNot { it.id in priorityIds }.take(RECENT_CONVERSATION_LIMIT)
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (priority.isNotEmpty()) {
            item { SwitcherLabel(localizedText("任务对话", "Task conversation")) }
            items(priority, key = { "priority:${it.id}" }) { conversation ->
                ConversationRow(conversation, state, onSelect)
            }
        }
        if (recent.isNotEmpty()) {
            item { SwitcherLabel(localizedText("最近对话", "Recent conversations")) }
            items(recent, key = { "recent:${it.id}" }) { conversation ->
                ConversationRow(conversation, state, onSelect)
            }
        }
        item {
            TextButton(onClick = { state.selectedConversationId?.let(onSelect) }) {
                Text(localizedText("返回当前对话", "Return to current conversation"))
            }
        }
    }
}

@Composable
private fun SwitcherLabel(value: String) {
    Text(
        value,
        Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        color = LocalChatColors.current.secondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ConversationRow(conversation: Conversation, state: OverlayViewState, onSelect: (String) -> Unit) {
    val colors = LocalChatColors.current
    val status = conversationStatus(conversation.id, state)
    Surface(
        Modifier.fillMaxWidth().clickable { onSelect(conversation.id) },
        shape = RoundedCornerShape(12.dp),
        color = if (conversation.id == state.selectedConversationId) colors.accentSoft else colors.surface,
        border = BorderStroke(1.dp, if (conversation.id == state.selectedConversationId) colors.outline else colors.divider),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(status.second, CircleShape))
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (conversation.id == state.selectedConversationId) FontWeight.SemiBold else FontWeight.Normal)
                Text(status.first, color = status.second, style = MaterialTheme.typography.labelSmall)
            }
            ChatIcon(R.drawable.lucide_chevron_right, null, Modifier.size(16.dp), colors.tertiary)
        }
    }
}

@Composable
private fun OverlayTimeline(state: OverlayViewState, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(state.selectedConversationId, state.messages.size, state.messages.lastOrNull()?.text,
        state.toolCalls.lastOrNull()?.status) {
        if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
    }
    if (state.messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(localizedText("在下方发送消息开始对话", "Send a message below to start a conversation"), color = LocalChatColors.current.secondary,
                style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().testTag("overlay_messages"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.messages, key = Message::id) { message ->
            OverlayMessage(message, state.toolCalls.filter { it.replyMessageId == message.id }, state.toolTitles)
        }
    }
}

@Composable
private fun OverlayMessage(message: Message, calls: List<ToolCallRecord>, toolTitles: Map<String, String>) {
    val colors = LocalChatColors.current
    val toolGroups = groupOverlayToolCalls(calls)
    var completedExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
    ) {
        if (message.role == MessageRole.USER) {
            Surface(
                Modifier.fillMaxWidth(.88f),
                shape = RoundedCornerShape(17.dp),
                color = colors.userBubble,
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    if (message.attachments.isNotEmpty()) {
                        Text(localizedText("${message.attachments.size} 个附件", "${message.attachments.size} attachments"), Modifier.padding(top = 5.dp),
                            color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            Text("Mobile Agent", color = colors.secondary, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold)
            val reasoning = message.assistantSteps.lastOrNull { it.reasoning.isNotBlank() }?.reasoning
                ?: message.reasoningSteps.lastOrNull()
            if (!reasoning.isNullOrBlank() && message.status == MessageStatus.GENERATING) {
                Surface(
                    Modifier.fillMaxWidth().padding(top = 5.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceRaised,
                ) {
                    Text(
                        localizedText("思考 · ${reasoning.takeLast(500)}", "Reasoning · ${reasoning.takeLast(500)}"),
                        Modifier.padding(10.dp),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (toolGroups.completed.size > 1) {
                CompletedToolsRow(
                    calls = toolGroups.completed,
                    toolTitles = toolTitles,
                    expanded = completedExpanded,
                    onToggle = { completedExpanded = !completedExpanded },
                )
            } else {
                toolGroups.completed.forEach { call -> CompactToolRow(call, toolTitles) }
            }
            toolGroups.highlighted.forEach { call -> CompactToolRow(call, toolTitles) }
            if (message.text.isNotBlank()) {
                Column(Modifier.fillMaxWidth().padding(top = 7.dp, end = 5.dp)) { ReplyBody(message.text) }
            } else if (message.status == MessageStatus.GENERATING && calls.isEmpty()) {
                Text(localizedText("正在生成…", "Generating…"), Modifier.padding(top = 7.dp), color = colors.secondary,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ApprovalCard(request: ToolApprovalRequest, actions: OverlayActions) {
    val colors = LocalChatColors.current
    var choiceId by rememberSaveable(request.callId) { mutableStateOf<String?>(null) }
    var submitting by rememberSaveable(request.callId) { mutableStateOf(false) }
    val selectionReady = request.choices.isEmpty() || choiceId != null
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(17.dp),
        color = colors.warningSoft,
        border = BorderStroke(1.dp, colors.warning.copy(alpha = .35f)),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(localizedText("需要授权 · ${request.actionTitle}", "Authorization required · ${request.actionTitle}"), color = colors.warning,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(request.description, style = MaterialTheme.typography.bodySmall)
            request.argumentsSummary?.takeIf(String::isNotBlank)?.let {
                Text(localizedText("将要执行：$it", "About to run: $it"), color = colors.secondary, style = MaterialTheme.typography.bodySmall)
            }
            request.choices.forEach { choice ->
                val supported = choice.id != "background" || Build.VERSION.SDK_INT >= 34
                OutlinedButton(
                    onClick = { choiceId = choice.id },
                    enabled = supported && !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                ) {
                    Text((if (choiceId == choice.id) "✓ " else "") + choice.title)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TextButton(
                    onClick = {
                        submitting = true
                        actions.decideApproval(request, false, null, false)
                    },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                ) { Text(localizedText("不允许", "Deny"), color = colors.error) }
                if (request.requiresPermissionApproval) {
                    OutlinedButton(
                        onClick = {
                            submitting = true
                            actions.decideApproval(request, true, choiceId, true)
                        },
                        enabled = !submitting && selectionReady,
                        modifier = Modifier.weight(1f),
                    ) { Text(localizedText("始终允许", "Always allow")) }
                }
                Button(
                    onClick = {
                        submitting = true
                        actions.decideApproval(request, true, choiceId, false)
                    },
                    enabled = !submitting && selectionReady,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) { Text(if (request.choices.isEmpty()) localizedText("仅本次", "Just once") else localizedText("开始", "Start")) }
            }
        }
    }
}

@Composable
private fun QuestionCard(request: UserQuestionRequest, actions: OverlayActions) {
    val colors = LocalChatColors.current
    var answer by rememberSaveable(request.id) { mutableStateOf("") }
    var submitting by rememberSaveable(request.id) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(17.dp),
        color = colors.accentSoft,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = .3f)),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(localizedText("AI 需要你的回答", "AI needs your answer"), color = colors.accent, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
            Text(request.question, style = MaterialTheme.typography.bodyMedium)
            request.options.forEach { option ->
                OutlinedButton(
                    onClick = {
                        submitting = true
                        actions.answerQuestion(request, option)
                    },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(option) }
            }
            if (request.allowFreeText) {
                BasicTextField(
                    value = answer,
                    onValueChange = { answer = it.take(2_000) },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    decorationBox = { inner ->
                        Box {
                            if (answer.isBlank()) Text(localizedText("输入你的回答", "Enter your answer"), color = colors.tertiary,
                                style = MaterialTheme.typography.bodyMedium)
                            inner()
                        }
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TextButton(
                    onClick = {
                        submitting = true
                        actions.answerQuestion(request, null)
                    },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                ) { Text(localizedText("暂不回答", "Not now")) }
                if (request.allowFreeText) {
                    Button(
                        onClick = {
                            submitting = true
                            actions.answerQuestion(request, answer.trim())
                        },
                        enabled = !submitting && answer.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(localizedText("提交回答", "Submit answer")) }
                }
            }
        }
    }
}

@Composable
private fun OverlayComposer(state: OverlayViewState, actions: OverlayActions, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    val canSend = state.modelConfigured &&
        (state.draft.text.isNotBlank() || state.draft.attachments.any { it.isImage })
    Surface(
        modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 9.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = state.draft.text,
                onValueChange = { actions.editDraft(state.draft.copy(text = it)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 104.dp).padding(vertical = 11.dp),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) actions.sendMessage() }),
                decorationBox = { inner ->
                    Box {
                        if (state.draft.text.isBlank()) Text(
                            if (state.modelConfigured) localizedText("发送消息", "Send message") else localizedText("请先在 App 中配置模型", "Configure a model in the app first"),
                            color = colors.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        inner()
                    }
                },
            )
            if (state.selectedConversationId in state.activeConversations) {
                Surface(
                    Modifier.padding(start = 4.dp).size(48.dp).clickable(
                        role = Role.Button,
                        onClick = actions::stopCurrent,
                    ),
                    shape = CircleShape,
                    color = colors.muted,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ChatIcon(R.drawable.lucide_square, localizedText("停止当前任务", "Stop current task"), Modifier.size(16.dp), colors.error)
                    }
                }
            }
            Surface(
                Modifier.padding(start = 4.dp).size(48.dp).clickable(
                    enabled = canSend,
                    role = Role.Button,
                    onClick = actions::sendMessage,
                ),
                shape = CircleShape,
                color = if (canSend) colors.accent else colors.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ChatIcon(R.drawable.lucide_arrow_up, localizedText("发送消息", "Send message"), Modifier.size(18.dp),
                        if (canSend) colors.onAccent else colors.tertiary)
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(corner: ResizeCorner, modifier: Modifier, actions: OverlayActions) {
    Box(
        modifier.size(48.dp)
            .testTag("overlay_resize_handle")
            .semantics { contentDescription = localizedText("调整悬浮窗大小", "Resize floating window") }
            .pointerInput(corner) {
                detectDragGestures(
                    onDragEnd = { actions.resize(corner, 0f, 0f, true) },
                    onDragCancel = { actions.resize(corner, 0f, 0f, true) },
                ) { change, amount ->
                    change.consume()
                    actions.resize(corner, amount.x, amount.y, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ChatIcon(
            R.drawable.lucide_chevron_right,
            null,
            Modifier.size(16.dp).rotate(45f),
            LocalChatColors.current.tertiary,
        )
    }
}

@Composable
private fun OverlayIconAction(
    icon: Int,
    description: String,
    foreground: Color,
    container: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Surface(
        Modifier.size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .testTag("overlay_action_$description"),
        shape = CircleShape,
        color = container,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ChatIcon(icon, null, Modifier.size(18.dp), foreground)
        }
    }
}

@Composable
private fun StatusBadge(state: OverlayViewState, size: Dp) {
    val status = overlayStatusVisual(state, global = false)
    Surface(
        Modifier.size(size).semantics { contentDescription = status.label },
        shape = CircleShape,
        color = status.container,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ChatIcon(status.icon, null, Modifier.size(size * .48f), status.foreground)
        }
    }
}

@Composable
private fun CompletedToolsRow(
    calls: List<ToolCallRecord>,
    toolTitles: Map<String, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalChatColors.current
    Surface(
        Modifier.fillMaxWidth().padding(top = 5.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag("overlay_completed_tools"),
        shape = RoundedCornerShape(12.dp),
        color = colors.successSoft,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatIcon(R.drawable.lucide_circle_check, null, Modifier.size(18.dp), colors.success)
            Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                Text(localizedText("已完成 ${calls.size} 步", "Completed ${calls.size} steps"), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    toolTitles[calls.last().toolId] ?: localizedText("最近一步已完成", "Latest step completed"),
                    color = colors.secondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChatIcon(
                if (expanded) R.drawable.lucide_chevron_down else R.drawable.lucide_chevron_right,
                if (expanded) localizedText("收起已完成步骤", "Hide completed steps") else localizedText("展开已完成步骤", "Show completed steps"),
                Modifier.size(17.dp),
                colors.secondary,
            )
        }
    }
    if (expanded) calls.forEach { call -> CompactToolRow(call, toolTitles) }
}

@Composable
private fun CompactToolRow(call: ToolCallRecord, toolTitles: Map<String, String>) {
    val colors = LocalChatColors.current
    val (foreground, container) = when (call.status) {
        ToolCallStatus.SUCCEEDED -> colors.success to colors.successSoft
        ToolCallStatus.FAILED -> colors.error to colors.errorSoft
        ToolCallStatus.RECEIVED, ToolCallStatus.EXECUTING -> colors.accent to colors.accentSoft
        else -> colors.secondary to colors.surfaceRaised
    }
    Surface(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        shape = RoundedCornerShape(12.dp),
        color = container,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(foreground, CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    "${toolStatusText(call.status)} · ${toolTitles[call.toolId] ?: localizedText("工具调用", "Tool calls")}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    call.displaySummary ?: call.error ?: toolStatusText(call.status),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class OverlayStatusVisual(
    val label: String,
    val icon: Int,
    val foreground: Color,
    val container: Color,
    val animated: Boolean = false,
)

@Composable
private fun voiceRecordingStatusVisual(action: OverlayVoiceReleaseAction): OverlayStatusVisual {
    val colors = LocalChatColors.current
    return when (action) {
        OverlayVoiceReleaseAction.SEND_CURRENT ->
            OverlayStatusVisual(localizedText("正在录音，松开发送", "Recording — release to send"), R.drawable.lucide_mic, colors.error, colors.errorSoft)
        OverlayVoiceReleaseAction.CANCEL ->
            OverlayStatusVisual(localizedText("松手取消发送", "Release to cancel"), R.drawable.lucide_x, colors.error, colors.errorSoft)
        OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION ->
            OverlayStatusVisual(localizedText("松手在新会话发送", "Release to send in a new conversation"), R.drawable.lucide_plus, colors.accent, colors.accentSoft)
    }
}

internal enum class OverlayStatusKind { RECORDING, TRANSCRIBING, ATTENTION, RUNNING, COMPLETED, IDLE }

/** 状态优先级独立于绘制，确保动画只代表真正执行中，不覆盖录音、授权或提问提示。 */
internal fun overlayStatusKind(state: OverlayViewState, global: Boolean): OverlayStatusKind {
    val selected = state.selectedConversationId
    val attention = if (global) {
        state.approvals.isNotEmpty() || state.questions.isNotEmpty()
    } else {
        state.approvals.any { it.conversationId == selected } ||
            state.questions.any { it.conversationId == selected }
    }
    val running = if (global) state.activeConversations.isNotEmpty() else selected in state.activeConversations
    val completed = if (global) state.completionConversationId != null else selected == state.completionConversationId
    return when {
        state.voiceInputState is VoiceInputState.Recording -> OverlayStatusKind.RECORDING
        state.voiceInputState is VoiceInputState.Transcribing -> OverlayStatusKind.TRANSCRIBING
        attention -> OverlayStatusKind.ATTENTION
        running -> OverlayStatusKind.RUNNING
        completed -> OverlayStatusKind.COMPLETED
        else -> OverlayStatusKind.IDLE
    }
}

@Composable
private fun overlayStatusVisual(state: OverlayViewState, global: Boolean): OverlayStatusVisual {
    val colors = LocalChatColors.current
    return when (overlayStatusKind(state, global)) {
        OverlayStatusKind.RECORDING ->
            OverlayStatusVisual(localizedText("正在录音，松开发送", "Recording — release to send"), R.drawable.lucide_mic, colors.error, colors.errorSoft)
        OverlayStatusKind.TRANSCRIBING ->
            OverlayStatusVisual(
                localizedText("正在转写语音", "Transcribing speech"),
                R.drawable.lucide_sparkles,
                colors.accent,
                colors.accentSoft,
                animated = true,
            )
        OverlayStatusKind.ATTENTION ->
            OverlayStatusVisual(localizedText("需要处理", "Needs attention"), R.drawable.lucide_ellipsis, colors.warning, colors.warningSoft)
        OverlayStatusKind.RUNNING ->
            OverlayStatusVisual(localizedText("任务执行中", "Task running"), R.drawable.lucide_sparkles, colors.accent, colors.accentSoft, animated = true)
        OverlayStatusKind.COMPLETED ->
            OverlayStatusVisual(localizedText("任务已完成", "Task completed"), R.drawable.lucide_circle_check, colors.success, colors.successSoft)
        OverlayStatusKind.IDLE ->
            OverlayStatusVisual(localizedText("当前空闲", "Idle"), R.drawable.lucide_bot, colors.secondary, colors.muted)
    }
}

@Composable
private fun conversationStatus(id: String, state: OverlayViewState): Pair<String, Color> {
    val colors = LocalChatColors.current
    return when {
        state.approvals.any { it.conversationId == id } -> localizedText("需要授权", "Authorization required") to colors.warning
        state.questions.any { it.conversationId == id } -> localizedText("等待回答", "Waiting for answer") to colors.warning
        id in state.activeConversations -> localizedText("执行中", "Running") to colors.accent
        id == state.completionConversationId -> localizedText("刚刚完成", "Just completed") to colors.success
        else -> localizedText("最近使用", "Recently used") to colors.tertiary
    }
}

private fun toolStatusText(status: ToolCallStatus): String = when (status) {
    ToolCallStatus.RECEIVED -> localizedText("准备中", "Preparing")
    ToolCallStatus.WAITING_APPROVAL -> localizedText("等待确认", "Waiting for approval")
    ToolCallStatus.EXECUTING -> localizedText("执行中", "Running")
    ToolCallStatus.SUCCEEDED -> localizedText("已完成", "Completed")
    ToolCallStatus.FAILED -> localizedText("未完成", "Not completed")
    ToolCallStatus.DENIED -> localizedText("未授权", "Not authorized")
    ToolCallStatus.CANCELLED -> localizedText("已停止", "Stopped")
    ToolCallStatus.INTERRUPTED -> localizedText("已中断", "Interrupted")
}

private const val RECENT_CONVERSATION_LIMIT = 5
