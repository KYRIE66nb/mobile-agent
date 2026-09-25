package xyz.chouxuewei.mobile_agent.overlay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.chouxuewei.mobile_agent.MainActivity
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.chat.ComposerDraft
import xyz.chouxuewei.mobile_agent.core.AssistantStep
import xyz.chouxuewei.mobile_agent.core.Conversation
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.MessageRole
import xyz.chouxuewei.mobile_agent.core.ThemePreference
import xyz.chouxuewei.mobile_agent.core.ToolApprovalRequest
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.data.ModelSettings
import xyz.chouxuewei.mobile_agent.data.SpeechSettings
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.voice.VoiceInputSource
import xyz.chouxuewei.mobile_agent.voice.VoiceInputState
import xyz.chouxuewei.mobile_agent.voice.VoiceInputTarget
import xyz.chouxuewei.mobile_agent.voice.VoiceInputDestination

/**
 * 常驻悬浮助手的系统宿主。业务事实仍来自 ChatRuntime、ConversationStore 和问题代理；
 * 服务只拥有展示状态、窗口位置与手势，避免悬浮窗形成第二套任务状态。
 */
class DeviceOperationOverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: PrototypeApplication
    private lateinit var windows: WindowManager
    private val presentation = OverlayStateMachine()
    private val viewState = MutableStateFlow(OverlayViewState())
    private var overlay: ComposeView? = null
    private var layout: WindowManager.LayoutParams? = null

    // 进程若由常驻服务单独恢复，默认没有 Activity 在前台；MainActivity 启动后会立即改为 true。
    private var appVisible = false
    private var hiddenForDeviceInteraction = false
    private var persistentOverlay = false
    private var persistentPreferenceLoaded = false
    private var theme = ThemePreference.SYSTEM
    private var conversations: List<Conversation> = emptyList()
    private var activeConversations: Set<String> = emptySet()
    private var approvals: List<ToolApprovalRequest> = emptyList()
    private var questions: List<UserQuestionRequest> = emptyList()
    private var mode: ExecutionMode? = null
    private var settings = ModelSettings()
    private var speechSettings = SpeechSettings()
    private var voiceInputState: VoiceInputState = VoiceInputState.Idle
    private var drafts: Map<String, ComposerDraft> = emptyMap()
    private var conversationId: String? = null
    private var messages: List<Message> = emptyList()
    private var calls: List<ToolCallRecord> = emptyList()
    private var steps: List<OverlayStep> = emptyList()
    private var responseText = ""
    private var stopping = false
    private var completionConversationId: String? = null
    private var completionHold = false
    private var resizeHint: String? = null
    private var conversationJob: Job? = null
    private var idleCollapseJob: Job? = null

    private var dockedEdge = DockEdge.RIGHT
    private var overlayY: Int? = null
    private var fullWidth = 0
    private var fullHeight = 0
    private var edgeDragX = 0f
    private var summaryDragX = 0f
    private var fullDragSession: FullDragSession? = null
    private var resizeSession: ResizeSession? = null

    private val toolTitles by lazy { app.toolRegistry.definitions.associate { it.id to it.title } }

    private val actions = object : OverlayActions {
        override fun expandSummary() = changePresentation { showSummary() }
        override fun expandFullChat() = changePresentation { showFullChat() }
        override fun collapseOneLevel() = changePresentation { collapseOneLevel() }
        override fun openConversation() = this@DeviceOperationOverlayService.openConversation()
        override fun selectConversation(id: String) = this@DeviceOperationOverlayService.selectConversation(id)
        override fun editDraft(draft: ComposerDraft) {
            val id = conversationId ?: return
            app.chatWorkspace.edit(id, draft)
        }
        override fun sendMessage() = this@DeviceOperationOverlayService.sendMessage()
        override fun startVoiceInput() = this@DeviceOperationOverlayService.startVoiceInput()
        override fun finishVoiceInput(action: OverlayVoiceReleaseAction) =
            this@DeviceOperationOverlayService.finishVoiceInput(action)
        override fun stopCurrent() = stopOperation()
        override fun decideApproval(
            request: ToolApprovalRequest,
            allow: Boolean,
            choiceId: String?,
            permanently: Boolean,
        ) {
            scope.launch { app.chatRuntime.decideTool(request.callId, allow, choiceId, permanently) }
        }
        override fun answerQuestion(request: UserQuestionRequest, answer: String?) {
            scope.launch { app.userQuestions.respond(request.id, answer) }
        }
        override fun dragEdge(dx: Float, dy: Float, finished: Boolean) = onEdgeDrag(dx, dy, finished)
        override fun dragSummary(dx: Float, dy: Float, finished: Boolean) = onSummaryDrag(dx, dy, finished)
        override fun dragWindow(dx: Float, dy: Float, finished: Boolean) =
            moveFullWindow(dx, dy, finished)
        override fun resize(corner: ResizeCorner, dx: Float, dy: Float, finished: Boolean) =
            resizeFullWindow(corner, dx, dy, finished)
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        running = true
        activeInstance = this
        appVisible = latestAppVisible
        app = application as PrototypeApplication
        windows = getSystemService(WindowManager::class.java)
        createNotificationChannels()
        updateForegroundType(microphone = false)
        collectRuntimeState()
        publishState()
    }

    private fun collectRuntimeState() {
        scope.launch {
            app.appearance.persistentOverlay.collectLatest { enabled ->
                persistentOverlay = enabled
                persistentPreferenceLoaded = true
                refreshOverlay()
                refreshNotification()
                stopIfUnused()
            }
        }
        scope.launch {
            app.appearance.theme.collectLatest { value ->
                theme = value
                publishState()
            }
        }
        scope.launch {
            app.appearance.currentConversation.collectLatest { id ->
                if (conversationId == null && !id.isNullOrBlank()) selectConversation(id, syncWorkspace = false)
            }
        }
        scope.launch {
            app.conversations.observeConversations().collectLatest { values ->
                conversations = values
                if (conversationId == null || values.none { it.id == conversationId }) {
                    val preferred = priorityConversationId()
                        ?: values.maxByOrNull(Conversation::updatedAt)?.id
                    if (preferred != null) selectConversation(preferred, syncWorkspace = false)
                }
                publishState()
            }
        }
        scope.launch {
            app.chatWorkspace.drafts.collectLatest { values ->
                drafts = values
                publishState()
            }
        }
        scope.launch {
            app.modelSettings.settings.collectLatest { value ->
                settings = value
                publishState()
            }
        }
        scope.launch {
            app.speechSettings.settings.collectLatest { value ->
                speechSettings = value
                publishState()
            }
        }
        scope.launch {
            app.voiceInput.state.collectLatest { value ->
                voiceInputState = value
                publishState()
                refreshNotification()
                stopIfUnused()
            }
        }
        scope.launch {
            app.chatRuntime.active.collectLatest { current ->
                val started = current - activeConversations
                val completed = activeConversations - current
                activeConversations = current
                if (current.isNotEmpty()) completionHold = false
                if (started.isNotEmpty()) {
                    if (presentation.presentation != OverlayPresentation.FULL_CHAT) {
                        selectConversation(started.first())
                    }
                    drawAttention()
                }
                if (completed.isNotEmpty()) {
                    completionConversationId = completed.first()
                    completionHold = true
                    if (presentation.presentation != OverlayPresentation.FULL_CHAT) {
                        selectConversation(completionConversationId!!)
                    }
                    drawAttention()
                }
                if (conversationId !in current) stopping = false
                scheduleIdleCollapse()
                refreshOverlay()
                refreshNotification()
                stopIfUnused()
            }
        }
        scope.launch {
            app.chatRuntime.approvals.collectLatest { values ->
                val previous = approvals.mapTo(linkedSetOf(), ToolApprovalRequest::callId)
                approvals = values.values.toList()
                approvals.firstOrNull { it.callId !in previous }?.let { request ->
                    if (presentation.presentation != OverlayPresentation.FULL_CHAT) {
                        selectConversation(request.conversationId)
                    }
                    drawAttention()
                }
                scheduleIdleCollapse()
                refreshOverlay()
                refreshNotification()
                stopIfUnused()
            }
        }
        scope.launch {
            app.userQuestions.requests.collectLatest { values ->
                val previous = questions.mapTo(linkedSetOf(), UserQuestionRequest::id)
                questions = values.values.toList()
                questions.firstOrNull { it.id !in previous }?.let { request ->
                    if (presentation.presentation != OverlayPresentation.FULL_CHAT) {
                        selectConversation(request.conversationId)
                    }
                    drawAttention()
                }
                scheduleIdleCollapse()
                refreshOverlay()
                refreshNotification()
                stopIfUnused()
            }
        }
        scope.launch {
            app.deviceGateway.activeMode.collectLatest { value ->
                mode = value
                scheduleIdleCollapse()
                refreshOverlay()
                refreshNotification()
                stopIfUnused()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SESSION -> intent.getStringExtra(EXTRA_CONVERSATION_ID)
                ?.takeIf(String::isNotBlank)?.let(::selectConversation)
            ACTION_STOP -> {
                intent.getStringExtra(EXTRA_CONVERSATION_ID)
                    ?.takeIf(String::isNotBlank)?.let(::selectConversation)
                stopOperation()
            }
            ACTION_VISIBILITY -> {
                appVisible = intent.getBooleanExtra(EXTRA_VISIBLE, true)
                refreshOverlay()
            }
        }
        // 服务被系统回收后先恢复，再由持久化开关和真实任务状态决定是否自行停止。
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fullWidth = 0
        fullHeight = 0
        refreshOverlay()
    }

    private fun selectConversation(id: String, syncWorkspace: Boolean = true) {
        if (id.isBlank()) return
        if (conversationId != id) {
            conversationId = id
            observeConversation(id)
        }
        if (syncWorkspace) app.chatWorkspace.select(id)
        publishState()
    }

    private fun observeConversation(id: String) {
        conversationJob?.cancel()
        conversationJob = scope.launch {
            combine(
                app.conversations.observeMessages(id),
                app.conversations.observeToolCalls(id),
            ) { currentMessages, currentCalls -> currentMessages to currentCalls }
                .collectLatest { (currentMessages, currentCalls) ->
                    messages = currentMessages
                    calls = currentCalls
                    val assistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    responseText = assistant?.assistantSteps
                        ?.map(AssistantStep::text)?.filter(String::isNotBlank)?.joinToString("\n\n")
                        ?.takeIf(String::isNotBlank)
                        ?: assistant?.text.orEmpty()
                    steps = currentCalls.filter { assistant == null || it.replyMessageId == assistant.id }
                        .takeLast(8).map { it.overlayStep(toolTitles) }
                    publishState()
                    refreshNotification()
                }
        }
    }

    private fun drawAttention() {
        val before = presentation.presentation
        presentation.drawAttention()
        if (before != presentation.presentation) applyPresentation(before)
        scheduleIdleCollapse()
        publishState()
    }

    private inline fun changePresentation(
        dockEdgeOverride: DockEdge? = null,
        change: OverlayStateMachine.() -> Unit,
    ) {
        val before = presentation.presentation
        presentation.change()
        if (before != presentation.presentation) {
            fullDragSession = null
            resizeHint = null
            applyPresentation(before, dockEdgeOverride)
        }
        if (presentation.presentation == OverlayPresentation.EDGE_HANDLE && !hasWork()) {
            completionHold = false
        }
        scheduleIdleCollapse()
        publishState()
        stopIfUnused()
    }

    private fun scheduleIdleCollapse() {
        idleCollapseJob?.cancel()
        val hasWork = hasWork()
        if (presentation.presentation != OverlayPresentation.SUMMARY || hasWork) return
        idleCollapseJob = scope.launch {
            delay(IDLE_COLLAPSE_MILLIS)
            val before = presentation.presentation
            presentation.collapseIdleSummary(hasWork())
            completionHold = false
            if (before != presentation.presentation) applyPresentation(before)
            publishState()
            stopIfUnused()
        }
    }

    private fun hasWork(): Boolean = activeConversations.isNotEmpty() || approvals.isNotEmpty() ||
        questions.isNotEmpty() || mode != null || voiceInputState is VoiceInputState.Recording ||
        voiceInputState is VoiceInputState.Transcribing

    private fun priorityConversationId(): String? = approvals.firstOrNull()?.conversationId
        ?: questions.firstOrNull()?.conversationId
        ?: conversationId?.takeIf { id -> id in activeConversations }
        ?: activeConversations.firstOrNull()

    private fun publishState() {
        val selected = conversationId
        val selectedConversation = conversations.firstOrNull { it.id == selected }
        val summary = summaryText(selectedConversation)
        viewState.value = OverlayViewState(
            presentation = presentation.presentation,
            theme = theme,
            selectedConversationId = selected,
            conversations = conversations,
            messages = messages,
            toolCalls = calls,
            toolTitles = toolTitles,
            draft = drafts[selected] ?: ComposerDraft(),
            activeConversations = activeConversations,
            approvals = approvals,
            questions = questions,
            mode = mode,
            stopping = stopping,
            completionConversationId = completionConversationId,
            summaryTitle = summary.first,
            summaryDetail = summary.second,
            modelConfigured = settings.selectedModel != null,
            resizeHint = resizeHint,
            dockedAtStart = dockedEdge == DockEdge.LEFT,
            voiceInputState = voiceInputState,
        )
    }

    private fun summaryText(conversation: Conversation?): Pair<String, String> {
        val selected = conversationId
        approvals.firstOrNull { it.conversationId == selected }?.let {
            return (conversation?.title ?: "需要授权") to "需要授权 · ${it.actionTitle}"
        }
        questions.firstOrNull { it.conversationId == selected }?.let {
            return (conversation?.title ?: "等待回答") to it.question
        }
        if (stopping) return (conversation?.title ?: "当前任务") to "正在停止任务…"
        if (selected in activeConversations) return (conversation?.title ?: "当前任务") to currentHeadline()
        if (selected == completionConversationId) {
            val detail = responseText.trim().lineSequence().lastOrNull()?.take(90)
                ?.takeIf(String::isNotBlank) ?: "任务已经完成"
            return (conversation?.title ?: "任务已完成") to detail
        }
        return "悬浮助手" to "暂无进行中的任务"
    }

    private fun currentHeadline(): String {
        val step = steps.lastOrNull()
        if (step == null) return if (mode == null) "正在等待 AI 完成任务" else "正在准备手机操作"
        return if (step.state == "已完成" && step.title != "结束手机操作") {
            "${step.detail}，正在分析下一步"
        } else step.detail
    }

    private fun refreshOverlay() {
        val promptPending = approvals.isNotEmpty() || questions.isNotEmpty()
        val shouldShow = !appVisible && Settings.canDrawOverlays(this) && (persistentOverlay || hasWork()) &&
            (!hiddenForDeviceInteraction || promptPending)
        if (!shouldShow) {
            removeOverlay()
            return
        }
        if (overlay == null) addOverlay() else applyPresentation(presentation.presentation)
        publishState()
    }

    private fun addOverlay() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DeviceOperationOverlayService)
            setViewTreeSavedStateRegistryOwner(this@DeviceOperationOverlayService)
            setViewTreeViewModelStoreOwner(this@DeviceOperationOverlayService)
            setContent {
                val state by viewState.collectAsState()
                OverlayContent(state, actions)
            }
        }
        val bounds = safeBounds()
        val (width, height) = desiredSize(bounds)
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            windowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val initialX = edgeX(bounds, width)
            val initialY = overlayY ?: bounds.top + dp(96)
            x = if (presentation.presentation == OverlayPresentation.FULL_CHAT) {
                clampFullX(initialX, bounds, width)
            } else initialX
            y = if (presentation.presentation == OverlayPresentation.FULL_CHAT) {
                clampFullY(initialY, bounds, height)
            } else initialY.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        overlay = view
        layout = params
        windows.addView(view, params)
    }

    private fun applyPresentation(previous: OverlayPresentation, dockEdgeOverride: DockEdge? = null) {
        val view = overlay ?: return
        val params = layout ?: return
        val bounds = safeBounds()
        val centerX = params.x + params.width / 2
        val (width, height) = desiredSize(bounds)
        params.width = width
        params.height = height
        params.flags = windowFlags()
        when (presentation.presentation) {
            OverlayPresentation.EDGE_HANDLE, OverlayPresentation.SUMMARY -> {
                if (previous == OverlayPresentation.FULL_CHAT) {
                    dockedEdge = dockEdgeOverride
                        ?: if (centerX < bounds.centerX()) DockEdge.LEFT else DockEdge.RIGHT
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
                }
                params.x = edgeX(bounds, width)
            }
            OverlayPresentation.FULL_CHAT -> {
                // 从可自由移动的摘要展开时，以摘要中心为锚点，避免完整窗口突然跳回屏幕边缘。
                if (previous != OverlayPresentation.FULL_CHAT) params.x = centerX - width / 2
                params.x = clampFullX(params.x, bounds, width)
            }
        }
        params.y = if (presentation.presentation == OverlayPresentation.FULL_CHAT) {
            clampFullY(params.y, bounds, height)
        } else params.y.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
        overlayY = params.y
        windows.updateViewLayout(view, params)
    }

    private fun desiredSize(bounds: Rect): Pair<Int, Int> = when (presentation.presentation) {
        // 视觉胶囊只有 28dp 宽，窗口保留 48dp 触控区，兼顾不挡内容与容易点按。
        OverlayPresentation.EDGE_HANDLE -> dp(48).coerceAtMost(bounds.width()) to dp(72).coerceAtMost(bounds.height())
        OverlayPresentation.SUMMARY ->
            minOf(dp(304), bounds.width() - dp(24)).coerceAtLeast(1) to
                minOf(dp(84), bounds.height()).coerceAtLeast(1)
        OverlayPresentation.FULL_CHAT -> {
            val minWidth = minOf(dp(MIN_FULL_WIDTH_DP), bounds.width())
            val minHeight = minOf(dp(MIN_FULL_HEIGHT_DP), bounds.height())
            val maxWidth = maxFullWidth(bounds, minWidth)
            val maxHeight = maxFullHeight(bounds, minHeight)
            if (fullWidth == 0) fullWidth = minOf(dp(DEFAULT_FULL_WIDTH_DP), maxWidth)
            if (fullHeight == 0) fullHeight = minOf(dp(DEFAULT_FULL_HEIGHT_DP), maxHeight)
            fullWidth.coerceIn(minWidth.coerceAtLeast(1), maxWidth) to
                fullHeight.coerceIn(minHeight.coerceAtLeast(1), maxHeight)
        }
    }

    /** 最大尺寸保留一圈很窄的可拖区域，否则窗口贴满屏幕后手指无法继续向外拖。 */
    private fun maxFullWidth(bounds: Rect, minWidth: Int): Int =
        (bounds.width() - dp(MAX_FULL_MARGIN_DP)).coerceAtLeast(minWidth.coerceAtLeast(1))

    private fun maxFullHeight(bounds: Rect, minHeight: Int): Int =
        (bounds.height() - dp(MAX_FULL_MARGIN_DP)).coerceAtLeast(minHeight.coerceAtLeast(1))

    private fun clampFullX(value: Int, bounds: Rect, width: Int): Int {
        val (minimum, maximum) = fullHorizontalLimits(bounds, width)
        return value.coerceIn(minimum, maximum)
    }

    private fun fullHorizontalLimits(bounds: Rect, width: Int): Pair<Int, Int> {
        val availableGap = (bounds.width() - width).coerceAtLeast(0)
        val margin = minOf(dp(MAX_FULL_MARGIN_DP / 2), availableGap / 2)
        return bounds.left + margin to bounds.right - width - margin
    }

    private fun clampFullY(value: Int, bounds: Rect, height: Int): Int {
        val availableGap = (bounds.height() - height).coerceAtLeast(0)
        val margin = minOf(dp(MAX_FULL_MARGIN_DP / 2), availableGap / 2)
        return value.coerceIn(bounds.top + margin, bounds.bottom - height - margin)
    }

    private fun windowFlags(): Int = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        if (presentation.presentation == OverlayPresentation.FULL_CHAT) 0
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private fun edgeX(bounds: Rect, width: Int): Int {
        val margin = if (presentation.presentation == OverlayPresentation.EDGE_HANDLE) 0 else dp(8)
        return if (dockedEdge == DockEdge.LEFT) bounds.left + margin
        else (bounds.right - width - margin).coerceAtLeast(bounds.left)
    }

    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val bounds = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            bounds.left += insets.left
            bounds.top += insets.top
            bounds.right -= insets.right
            bounds.bottom -= insets.bottom
            return bounds
        }
        @Suppress("DEPRECATION")
        return Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun onEdgeDrag(dx: Float, dy: Float, finished: Boolean) {
        edgeDragX += dx
        moveCompactWindow(dx, dy)
        if (!finished) return
        val startingEdge = dockedEdge
        val inward = if (startingEdge == DockEdge.LEFT) edgeDragX else -edgeDragX
        edgeDragX = 0f
        val targetEdge = nearestDockEdge()
        dockedEdge = targetEdge
        if (inward >= dp(24) && targetEdge == startingEdge) {
            changePresentation { showSummary() }
        } else {
            snapEdgeHandle()
            publishState()
        }
    }

    private fun onSummaryDrag(dx: Float, dy: Float, finished: Boolean) {
        summaryDragX += dx
        moveCompactWindow(dx, dy)
        if (!finished) return
        val startingEdge = dockedEdge
        val outward = if (startingEdge == DockEdge.LEFT) -summaryDragX else summaryDragX
        summaryDragX = 0f
        val reachedStartingEdge = isAtEdge(startingEdge, dp(8))
        dockedEdge = nearestDockEdge()
        if (outward >= dp(32) && reachedStartingEdge) {
            changePresentation { collapseOneLevel() }
        } else {
            publishState()
        }
    }

    /** 低层悬浮窗可在安全区域内双向移动；贴边状态仅在松手后重新吸附。 */
    private fun moveCompactWindow(dx: Float, dy: Float) {
        if (presentation.presentation == OverlayPresentation.FULL_CHAT) return
        val view = overlay ?: return
        val params = layout ?: return
        val bounds = safeBounds()
        params.x = (params.x + dx.toInt()).coerceIn(
            bounds.left,
            (bounds.right - params.width).coerceAtLeast(bounds.left),
        )
        params.y = (params.y + dy.toInt()).coerceIn(
            bounds.top,
            (bounds.bottom - params.height).coerceAtLeast(bounds.top),
        )
        overlayY = params.y
        windows.updateViewLayout(view, params)
    }

    private fun nearestDockEdge(): DockEdge {
        val params = layout ?: return dockedEdge
        return if (params.x + params.width / 2 < safeBounds().centerX()) DockEdge.LEFT else DockEdge.RIGHT
    }

    private fun isAtEdge(edge: DockEdge, tolerance: Int): Boolean {
        val params = layout ?: return false
        val bounds = safeBounds()
        return if (edge == DockEdge.LEFT) {
            params.x <= bounds.left + tolerance
        } else {
            params.x + params.width >= bounds.right - tolerance
        }
    }

    private fun snapEdgeHandle() {
        val view = overlay ?: return
        val params = layout ?: return
        params.x = edgeX(safeBounds(), params.width)
        windows.updateViewLayout(view, params)
    }

    private fun moveFullWindow(dx: Float, dy: Float, finished: Boolean) {
        if (presentation.presentation != OverlayPresentation.FULL_CHAT) return
        val view = overlay ?: return
        val params = layout ?: return
        val bounds = safeBounds()
        val session = fullDragSession ?: FullDragSession(
            rawX = params.x.toFloat(),
            rawY = params.y.toFloat(),
        ).also { fullDragSession = it }
        if (finished) {
            val (minimumX, maximumX) = fullHorizontalLimits(bounds, params.width)
            val target = fullWindowDockTarget(
                rawX = session.rawX,
                minimumX = minimumX.toFloat(),
                maximumX = maximumX.toFloat(),
                threshold = dp(FULL_DRAG_DOCK_THRESHOLD_DP).toFloat(),
            )
            fullDragSession = null
            resizeHint = null
            if (target != null) {
                val edge = if (target == FullWindowDockTarget.LEFT) DockEdge.LEFT else DockEdge.RIGHT
                // 完整窗从指定方向直接进入最小态，不能再按窗口中心重新选择停靠边。
                changePresentation(edge) { showEdgeHandle() }
            } else {
                publishState()
            }
            return
        }
        session.rawX += dx
        session.rawY += dy
        params.x = clampFullX(session.rawX.toInt(), bounds, params.width)
        params.y = clampFullY(session.rawY.toInt(), bounds, params.height)
        overlayY = params.y
        val (minimumX, maximumX) = fullHorizontalLimits(bounds, params.width)
        val target = fullWindowDockTarget(
            rawX = session.rawX,
            minimumX = minimumX.toFloat(),
            maximumX = maximumX.toFloat(),
            threshold = dp(FULL_DRAG_DOCK_THRESHOLD_DP).toFloat(),
        )
        val nextHint = target?.let { "松手收起到侧边" }
        if (resizeHint != nextHint) {
            resizeHint = nextHint
            publishState()
        }
        windows.updateViewLayout(view, params)
    }

    private fun resizeFullWindow(corner: ResizeCorner, dx: Float, dy: Float, finished: Boolean) {
        if (presentation.presentation != OverlayPresentation.FULL_CHAT) return
        val view = overlay ?: return
        val params = layout ?: return
        val bounds = safeBounds()
        val session = resizeSession?.takeIf { it.corner == corner } ?: ResizeSession(
            corner,
            params.x.toFloat(),
            params.y.toFloat(),
            (params.x + params.width).toFloat(),
            (params.y + params.height).toFloat(),
        ).also { resizeSession = it }
        if (!finished) {
            when (corner) {
                ResizeCorner.TOP_LEFT -> { session.left += dx; session.top += dy }
                ResizeCorner.TOP_RIGHT -> { session.right += dx; session.top += dy }
                ResizeCorner.BOTTOM_LEFT -> { session.left += dx; session.bottom += dy }
                ResizeCorner.BOTTOM_RIGHT -> { session.right += dx; session.bottom += dy }
            }
            val minWidth = minOf(dp(MIN_FULL_WIDTH_DP), bounds.width())
            val minHeight = minOf(dp(MIN_FULL_HEIGHT_DP), bounds.height())
            val maxWidth = maxFullWidth(bounds, minWidth)
            val maxHeight = maxFullHeight(bounds, minHeight)
            val rawWidth = session.right - session.left
            val rawHeight = session.bottom - session.top
            val width = rawWidth.toInt().coerceIn(minWidth.coerceAtLeast(1), maxWidth)
            val height = rawHeight.toInt().coerceIn(minHeight.coerceAtLeast(1), maxHeight)
            params.width = width
            params.height = height
            params.x = when (corner) {
                ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> (session.right - width).toInt()
                else -> session.left.toInt()
            }.let { clampFullX(it, bounds, width) }
            params.y = when (corner) {
                ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> (session.bottom - height).toInt()
                else -> session.top.toInt()
            }.let { clampFullY(it, bounds, height) }
            fullWidth = width
            fullHeight = height
            val minThreshold = dp(MIN_RESIZE_TRANSITION_DP)
            val maxThreshold = dp(MAX_RESIZE_TRANSITION_DP)
            resizeHint = when {
                minWidth - rawWidth >= minThreshold && minHeight - rawHeight >= minThreshold -> "松手收起为摘要"
                rawWidth - maxWidth >= maxThreshold && rawHeight - maxHeight >= maxThreshold -> "松手打开 Mobile Agent"
                else -> null
            }
            publishState()
            windows.updateViewLayout(view, params)
            return
        }

        val minWidth = minOf(dp(MIN_FULL_WIDTH_DP), bounds.width())
        val minHeight = minOf(dp(MIN_FULL_HEIGHT_DP), bounds.height())
        val maxWidth = maxFullWidth(bounds, minWidth)
        val maxHeight = maxFullHeight(bounds, minHeight)
        val rawWidth = session.right - session.left
        val rawHeight = session.bottom - session.top
        val collapse = minWidth - rawWidth >= dp(MIN_RESIZE_TRANSITION_DP) &&
            minHeight - rawHeight >= dp(MIN_RESIZE_TRANSITION_DP)
        val openApp = rawWidth - maxWidth >= dp(MAX_RESIZE_TRANSITION_DP) &&
            rawHeight - maxHeight >= dp(MAX_RESIZE_TRANSITION_DP)
        resizeSession = null
        resizeHint = null
        when {
            openApp -> openConversation()
            collapse -> changePresentation { collapseOneLevel() }
            else -> publishState()
        }
    }

    private fun sendMessage() {
        val id = conversationId ?: return
        val profile = settings.selectedModel ?: return
        app.chatWorkspace.send(id, profile.selectedReasoningEffort, profile.id)
    }

    private fun startVoiceInput(): Boolean {
        val id = conversationId
        if (id == null) {
            openSpeechSettings("请先打开一段对话")
            return false
        }
        val profile = settings.selectedModel
        if (profile == null) {
            openSpeechSettings("请先配置并选择聊天模型")
            return false
        }
        if (!speechSettings.configured) {
            openSpeechSettings("请先配置语音转写服务")
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openSpeechSettings("请先允许麦克风权限")
            return false
        }
        if (!updateForegroundType(microphone = true)) {
            openSpeechSettings("系统暂时不允许后台录音，请打开 App 后重试")
            return false
        }
        return app.voiceInput.start(
            VoiceInputTarget(id, profile.selectedReasoningEffort, profile.id, VoiceInputSource.OVERLAY),
        ).fold(
            onSuccess = { true },
            onFailure = {
                updateForegroundType(microphone = false)
                app.chatWorkspace.error.value = it.message ?: "无法启动录音，请重试"
                false
            },
        )
    }

    private fun finishVoiceInput(action: OverlayVoiceReleaseAction) {
        updateForegroundType(microphone = false)
        when (action) {
            OverlayVoiceReleaseAction.SEND_CURRENT -> app.voiceInput.finish()
            OverlayVoiceReleaseAction.CANCEL -> app.voiceInput.cancel()
            OverlayVoiceReleaseAction.SEND_NEW_CONVERSATION ->
                app.voiceInput.finish(VoiceInputDestination.NEW_CONVERSATION)
        }
    }

    private fun openSpeechSettings(message: String) {
        app.chatWorkspace.error.value = message
        app.requestedSettingsPage.value = "voice"
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SPEECH_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun updateForegroundType(microphone: Boolean): Boolean = runCatching {
        val currentNotification = notification()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                startForeground(NOTIFICATION_ID, currentNotification, type)
            }
            Build.VERSION.SDK_INT >= 29 && microphone -> startForeground(
                NOTIFICATION_ID,
                currentNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            else -> startForeground(NOTIFICATION_ID, currentNotification)
        }
    }.isSuccess

    private fun stopOperation() {
        if (stopping) return
        val id = conversationId ?: return
        if (id !in activeConversations) return
        stopping = true
        publishState()
        refreshNotification()
        scope.launch { app.chatRuntime.stop(id) }
    }

    private fun openConversation() {
        conversationId?.let(app.chatWorkspace::select)
        val before = presentation.presentation
        if (hasWork()) presentation.showSummary() else presentation.showEdgeHandle()
        if (before != presentation.presentation) applyPresentation(before)
        publishState()
        startActivity(openConversationIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun removeOverlay() {
        layout?.let { overlayY = it.y }
        overlay?.let { view -> runCatching { windows.removeView(view) } }
        overlay = null
        layout = null
        fullDragSession = null
        resizeSession = null
        resizeHint = null
    }

    private fun stopIfUnused() {
        if (!persistentPreferenceLoaded) return
        if (!persistentOverlay && !hasWork() && !completionHold) stopSelf()
    }

    private fun notification(): android.app.Notification {
        val approval = approvals.firstOrNull()
        val question = questions.firstOrNull()
        val interactionPending = approval != null || question != null
        val channelId = if (interactionPending) INTERACTION_CHANNEL_ID else CHANNEL_ID
        val title = when {
            voiceInputState is VoiceInputState.Recording -> "Mobile Agent 正在录音"
            voiceInputState is VoiceInputState.Transcribing -> "Mobile Agent 正在转写语音"
            approval != null -> "Mobile Agent 需要你的授权"
            question != null -> "Mobile Agent 需要你的回答"
            activeConversations.isNotEmpty() -> "Mobile Agent 正在处理任务"
            completionHold -> "Mobile Agent 已完成任务"
            persistentOverlay -> "Mobile Agent 悬浮助手已开启"
            else -> "Mobile Agent 正在准备"
        }
        val content = when {
            voiceInputState is VoiceInputState.Recording -> "松开贴边按钮后自动转写并发送"
            voiceInputState is VoiceInputState.Transcribing -> "识别完成后会自动发送给当前模型"
            approval != null -> approval.actionTitle
            question != null -> question.question
            activeConversations.isNotEmpty() -> currentHeadline()
            completionHold -> summaryText(conversations.firstOrNull { it.id == conversationId }).second
            persistentOverlay -> "悬浮按钮会在离开应用后保持可用"
            else -> "正在准备后台控制"
        }
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.lucide_brain_circuit)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(persistentOverlay || hasWork())
            .setOnlyAlertOnce(!interactionPending)
            .setPriority(if (interactionPending) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (interactionPending) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(
                this,
                0,
                openConversationIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        val selected = conversationId
        if (selected != null && selected in activeConversations) {
            builder.addAction(
                R.drawable.lucide_square,
                "停止当前任务",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, DeviceOperationOverlayService::class.java).apply {
                        action = ACTION_STOP
                        putExtra(EXTRA_CONVERSATION_ID, selected)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun openConversationIntent() = Intent(this, MainActivity::class.java).apply {
        action = ACTION_OPEN_CONVERSATION
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "悬浮助手与 AI 任务",
                NotificationManager.IMPORTANCE_LOW,
            ))
            createNotificationChannel(NotificationChannel(
                INTERACTION_CHANNEL_ID,
                "需要确认或回答",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "工具授权和 AI 询问等待处理时提醒" })
        }
    }

    private fun ToolCallRecord.overlayStep(titles: Map<String, String>) = OverlayStep(
        title = titles[toolId] ?: "手机操作",
        state = when (status) {
            ToolCallStatus.RECEIVED -> "准备中"
            ToolCallStatus.WAITING_APPROVAL -> "等待确认"
            ToolCallStatus.EXECUTING -> "执行中"
            ToolCallStatus.SUCCEEDED -> "已完成"
            ToolCallStatus.FAILED -> "未完成"
            ToolCallStatus.DENIED -> "未授权"
            ToolCallStatus.CANCELLED -> "已停止"
            ToolCallStatus.INTERRUPTED -> "已中断"
        },
        detail = when (status) {
            ToolCallStatus.FAILED, ToolCallStatus.DENIED, ToolCallStatus.CANCELLED,
            ToolCallStatus.INTERRUPTED -> displaySummary
                ?: error?.let { userFacingMessage(it, "操作未完成") }
                ?: "操作未完成"
            else -> operationDetail() ?: displaySummary ?: "准备中"
        },
    )

    /** 参数仅用于生成不泄露输入内容的动作摘要，不把原始 JSON 或用户文本放到其它应用上方。 */
    private fun ToolCallRecord.operationDetail(): String? {
        val completed = status == ToolCallStatus.SUCCEEDED
        return when (toolId) {
            "device_open" -> if (completed) "手机操作已开始" else "正在启动手机操作"
            "device_observe" -> if (completed) "已识别当前界面" else "正在识别当前界面"
            "device_close" -> if (completed) "手机操作已结束" else "正在结束手机操作"
            "device_action" -> {
                val action = runCatching {
                    Json.parseToJsonElement(argumentsJson).jsonObject["action"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                when (action) {
                    "open_app" -> if (completed) "已打开目标应用" else "正在打开目标应用"
                    "click_node" -> if (completed) "已点击界面元素" else "正在点击界面元素"
                    "long_click_node" -> if (completed) "已长按界面元素" else "正在长按界面元素"
                    "scroll_node" -> if (completed) "已滚动界面内容" else "正在滚动界面内容"
                    "tap" -> if (completed) "已点击当前界面" else "正在点击当前界面"
                    "long_press" -> if (completed) "已完成长按" else "正在长按当前界面"
                    "swipe" -> if (completed) "已完成滑动" else "正在滑动当前界面"
                    "input_text" -> if (completed) "已填写文字" else "正在填写文字"
                    "back" -> if (completed) "已返回上一页" else "正在返回上一页"
                    "enter" -> if (completed) "已执行确认" else "正在执行确认"
                    "home" -> if (completed) "已返回系统桌面" else "正在返回系统桌面"
                    "recents" -> if (completed) "已打开最近任务" else "正在打开最近任务"
                    "wait" -> if (completed) "界面已响应" else "正在等待界面响应"
                    else -> if (completed) "手机操作已完成" else "正在操作当前界面"
                }
            }
            "device_gesture" -> if (completed) "已完成复杂触控" else "正在执行连续轨迹"
            "device_batch" -> {
                val count = runCatching {
                    Json.parseToJsonElement(argumentsJson).jsonObject["steps"]?.jsonArray?.size
                }.getOrNull()
                val suffix = count?.let { " $it 步" }.orEmpty()
                if (completed) "已连续执行${suffix}手机操作" else "正在连续执行${suffix}手机操作"
            }
            "device_wait_for" -> if (completed) "界面已达到目标状态" else "正在等待界面状态"
            else -> null
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if ((voiceInputState as? VoiceInputState.Recording)?.target?.source == VoiceInputSource.OVERLAY) {
            app.voiceInput.cancel()
        }
        removeOverlay()
        conversationJob?.cancel()
        idleCollapseJob?.cancel()
        scope.cancel()
        viewModelStore.clear()
        running = false
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private data class OverlayStep(val title: String, val state: String, val detail: String)
    private enum class DockEdge { LEFT, RIGHT }
    private data class FullDragSession(var rawX: Float, var rawY: Float)
    private data class ResizeSession(
        val corner: ResizeCorner,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
    )

    companion object {
        private const val ACTION_SESSION = "xyz.chouxuewei.mobile_agent.overlay.SESSION"
        private const val ACTION_VISIBILITY = "xyz.chouxuewei.mobile_agent.overlay.VISIBILITY"
        private const val ACTION_STOP = "xyz.chouxuewei.mobile_agent.overlay.STOP"
        const val ACTION_OPEN_CONVERSATION = "xyz.chouxuewei.mobile_agent.overlay.OPEN_CONVERSATION"
        const val ACTION_OPEN_SPEECH_SETTINGS = "xyz.chouxuewei.mobile_agent.overlay.OPEN_SPEECH_SETTINGS"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_VISIBLE = "visible"
        private const val CHANNEL_ID = "device_operation"
        private const val INTERACTION_CHANNEL_ID = "agent_interaction"
        private const val NOTIFICATION_ID = 2026
        private const val IDLE_COLLAPSE_MILLIS = 20_000L
        private const val MIN_FULL_WIDTH_DP = 300
        private const val MIN_FULL_HEIGHT_DP = 360
        private const val DEFAULT_FULL_WIDTH_DP = 320
        private const val DEFAULT_FULL_HEIGHT_DP = 520
        private const val MIN_RESIZE_TRANSITION_DP = 48
        private const val MAX_RESIZE_TRANSITION_DP = 8
        private const val MAX_FULL_MARGIN_DP = 24
        private const val FULL_DRAG_DOCK_THRESHOLD_DP = 16
        @Volatile private var running = false
        @Volatile private var latestAppVisible = false
        @Volatile private var activeInstance: DeviceOperationOverlayService? = null

        fun start(context: Context, conversationId: String? = null) {
            ContextCompat.startForegroundService(context, Intent(context, DeviceOperationOverlayService::class.java).apply {
                action = ACTION_SESSION
                conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
            })
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DeviceOperationOverlayService::class.java))
        }

        fun setAppVisible(context: Context, visible: Boolean) {
            latestAppVisible = visible
            if (!running) return
            context.startService(Intent(context, DeviceOperationOverlayService::class.java).apply {
                action = ACTION_VISIBILITY
                putExtra(EXTRA_VISIBLE, visible)
            })
        }

        fun setMicrophoneCaptureInactive() {
            activeInstance?.let { service ->
                service.scope.launch { service.updateForegroundType(microphone = false) }
            }
        }

        /** 主屏识别和坐标动作期间移除控制层，避免 AI 识别或点击自己的悬浮窗。 */
        suspend fun setHiddenForDeviceInteraction(hidden: Boolean): Boolean =
            withContext(Dispatchers.Main.immediate) {
                val service = activeInstance ?: return@withContext false
                val wasVisible = service.overlay != null
                service.hiddenForDeviceInteraction = hidden
                service.refreshOverlay()
                wasVisible
            }
    }
}
