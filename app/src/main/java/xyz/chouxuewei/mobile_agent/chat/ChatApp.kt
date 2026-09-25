package xyz.chouxuewei.mobile_agent.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.core.AssistantStep
import xyz.chouxuewei.mobile_agent.core.AttachmentRef
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.core.ContextSnapshot
import xyz.chouxuewei.mobile_agent.core.ContextUsage
import xyz.chouxuewei.mobile_agent.core.Conversation
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.MessageRole
import xyz.chouxuewei.mobile_agent.core.MessageStatus
import xyz.chouxuewei.mobile_agent.core.ThemePreference
import xyz.chouxuewei.mobile_agent.core.ToolAccess
import xyz.chouxuewei.mobile_agent.core.ToolApprovalRequest
import xyz.chouxuewei.mobile_agent.core.ToolAvailabilityState
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus
import xyz.chouxuewei.mobile_agent.core.ToolCapability
import xyz.chouxuewei.mobile_agent.core.ToolPermissionMode
import xyz.chouxuewei.mobile_agent.core.ToolSummary
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.data.ModelSettings
import xyz.chouxuewei.mobile_agent.data.ModelProfile
import xyz.chouxuewei.mobile_agent.data.REASONING_EFFORT_OFF
import xyz.chouxuewei.mobile_agent.data.SpeechSettings
import xyz.chouxuewei.mobile_agent.overlay.DeviceOperationOverlayService
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.ui.theme.LocalChatColors
import xyz.chouxuewei.mobile_agent.ui.theme.Mobile_agentTheme
import xyz.chouxuewei.mobile_agent.ui.theme.resolveTheme
import xyz.chouxuewei.mobile_agent.attachments.IncomingShare
import xyz.chouxuewei.mobile_agent.voice.VoiceInputSource
import xyz.chouxuewei.mobile_agent.voice.VoiceInputState
import xyz.chouxuewei.mobile_agent.voice.VoiceInputTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(app: PrototypeApplication) {
    val preference by app.appearance.theme.collectAsState(initial = null)
    val workspace = app.chatWorkspace
    val current by workspace.current.collectAsState()
    val all by app.conversations.observeConversations().collectAsState(emptyList())
    val drafts by workspace.drafts.collectAsState()
    val active by app.chatRuntime.active.collectAsState()
    val notices by app.chatRuntime.notices.collectAsState()
    val contextUsages by app.chatRuntime.contextUsage.collectAsState()
    val approvals by app.chatRuntime.approvals.collectAsState()
    val questions by app.userQuestions.requests.collectAsState()
    val incomingShares by app.attachments.incoming.collectAsState()
    val persistentOverlay by app.appearance.persistentOverlay.collectAsState(initial = null)
    val attachmentError by app.attachments.error.collectAsState()
    val deviceMode by app.deviceGateway.activeMode.collectAsState()
    val pendingApproval = approvals.values.firstOrNull()
    val pendingQuestion = questions.values.firstOrNull()
    val incomingShare = incomingShares.firstOrNull()
    val toolAccesses by app.toolPermissions.accesses.collectAsState(emptyMap())
    val error by workspace.error.collectAsState()
    val settings by app.modelSettings.settings.collectAsState(ModelSettings())
    val speechSettings by app.speechSettings.settings.collectAsState(initial = SpeechSettings())
    val voiceInputState by app.voiceInput.state.collectAsState()
    val messages by remember(current) {
        current?.let(app.conversations::observeMessages) ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val toolCalls by remember(current) {
        current?.let(app.conversations::observeToolCalls) ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val conversationArtifacts by remember(current) {
        current?.let(app.artifacts::observeArtifacts) ?: flowOf(emptyList<Artifact>())
    }.collectAsState(emptyList())
    val toolTitles = remember(app) {
        app.toolRegistry.definitions.associate { definition -> definition.id to definition.title }
    }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val holder = rememberSaveableStateHolder()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var toolCapabilities by remember { mutableStateOf<List<ToolCapability>>(emptyList()) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    val requestedSettingsPage by app.requestedSettingsPage.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var summary by remember { mutableStateOf<ContextSnapshot?>(null) }
    var summaryOpen by remember { mutableStateOf(false) }
    var sourceMessage by remember { mutableStateOf<Message?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var artifactPreview by remember { mutableStateOf<Artifact?>(null) }
    var artifactPreviewContent by remember { mutableStateOf<ArtifactPreviewContent?>(null) }
    var artifactToDelete by remember { mutableStateOf<Artifact?>(null) }
    var attachmentTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(requestedSettingsPage) {
        requestedSettingsPage?.let { destination ->
            settingsPage = destination
            app.requestedSettingsPage.value = null
        }
    }
    LaunchedEffect(panel) {
        if (panel == "tools") {
            toolCapabilities = app.toolRegistry.capabilityPlaceholders
            toolCapabilities = app.toolRegistry.capabilities()
        }
    }
    LaunchedEffect(pendingApproval?.callId, pendingQuestion?.id) {
        if (pendingApproval != null || pendingQuestion != null) panel = null
    }
    LaunchedEffect(attachmentError) {
        attachmentError?.let { message ->
            workspace.error.value = message
            app.attachments.clearError()
        }
    }
    val context = LocalContext.current
    val artifactActions = ArtifactActions(
        onOpen = { artifact ->
            artifactPreview = artifact
            artifactPreviewContent = null
            scope.launch {
                runCatching {
                    loadArtifactPreview(context, artifact)
                }.onSuccess { preview ->
                    if (artifactPreview?.id == artifact.id) artifactPreviewContent = preview
                }.onFailure {
                    if (artifactPreview?.id == artifact.id) {
                        artifactPreview = null
                        artifactPreviewContent = null
                        workspace.error.value = userFacingMessage(it, "暂时无法预览这个文件，请稍后重试")
                    }
                }
            }
        },
        onShare = { artifact ->
            runCatching {
                val intent = Intent(Intent.ACTION_SEND)
                    .setType(artifact.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(artifact.contentUri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, "分享 ${artifact.name}"))
            }.onFailure { workspace.error.value = "文件分享失败，请重试" }
        },
        onReuse = { artifact ->
            current?.let { conversationId ->
                val draft = workspace.drafts.value[conversationId] ?: ComposerDraft()
                workspace.edit(
                    conversationId,
                    draft.copy(attachments = (draft.attachments + AttachmentRef(
                        artifact.contentUri, artifact.name, artifact.mimeType,
                    )).distinctBy(AttachmentRef::uri)),
                )
            }
        },
        onDelete = { artifact -> artifactToDelete = artifact },
    )
    LaunchedEffect(
        persistentOverlay,
        deviceMode,
        current,
        active,
        pendingApproval?.callId,
        pendingQuestion?.id,
    ) {
        // 常驻模式在空闲时也保留前台服务；关闭常驻后仍保留原有的任务期控制与通知兜底。
        val serviceConversationId = pendingApproval?.conversationId
            ?: pendingQuestion?.conversationId
            ?: current?.takeIf { it in active }
            ?: active.firstOrNull()
            ?: current?.takeIf { deviceMode != null }
        if (persistentOverlay == true || serviceConversationId != null) {
            DeviceOperationOverlayService.start(context, serviceConversationId)
        } else if (persistentOverlay == false) {
            DeviceOperationOverlayService.stop(context)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = attachmentTarget
        attachmentTarget = null
        if (uris.isNotEmpty() && id != null) {
            scope.launch {
                val imported = buildList {
                    uris.forEach { uri ->
                        runCatching { app.attachments.fromPicker(uri) }
                            .onSuccess { attachment -> add(attachment) }
                            .onFailure { failure ->
                                workspace.error.value = userFacingMessage(failure, "无法添加这个附件，请重新选择")
                            }
                    }
                }
                if (imported.isNotEmpty()) {
                    val draft = workspace.drafts.value[id] ?: ComposerDraft()
                    workspace.edit(
                        id,
                        draft.copy(attachments = (draft.attachments + imported).distinctBy(AttachmentRef::uri)),
                    )
                }
            }
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) workspace.error.value = "需要麦克风权限才能使用语音输入"
    }

    if (preference == null) return // 等待持久化设置，避免冷启动先闪现另一套主题。

    Mobile_agentTheme(preference!!) {
        val colors = LocalChatColors.current
        val view = LocalView.current
        val dark = resolveTheme(preference!!, isSystemInDarkTheme()) == ThemePreference.GRAPHITE
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        settingsPage?.let { destination ->
            BackHandler { settingsPage = null }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background,
                contentColor = colors.text,
            ) {
                ChatSettings(
                    app = app,
                    preference = preference!!,
                    settings = settings,
                    initialPage = if (destination == "model") "model" else destination,
                    onClose = { settingsPage = null },
                )
            }
            return@Mobile_agentTheme
        }

        ModalNavigationDrawer(
            drawerState = drawer,
            scrimColor = colors.text.copy(alpha = if (dark) .52f else .24f),
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawer,
                    modifier = Modifier.fillMaxWidth(.82f),
                    drawerShape = RoundedCornerShape(0.dp),
                    drawerContainerColor = colors.surface,
                ) {
                    HistoryDrawer(
                        conversations = all,
                        current = current,
                        active = active,
                        search = search,
                        onSearch = { search = it },
                        onClose = { scope.launch { drawer.close() } },
                        onNewConversation = {
                            workspace.newConversation()
                            scope.launch { drawer.close() }
                        },
                        onSelect = { id ->
                            workspace.select(id)
                            scope.launch { drawer.close() }
                        },
                        onSetPinned = workspace::setPinned,
                        onDelete = workspace::deleteConversation,
                        onTools = {
                            scope.launch {
                                drawer.close()
                                panel = "tools"
                            }
                        },
                        onSettings = {
                            scope.launch {
                                drawer.close()
                                settingsPage = "settings"
                            }
                        },
                    )
                }
            },
        ) {
            Scaffold(
                modifier = Modifier.testTag("chat_root_${preference!!.name}"),
                containerColor = colors.background,
                topBar = {
                    ChatTopBar(
                        title = all.firstOrNull { it.id == current }?.title ?: "Mobile Agent",
                        running = current in active,
                        onHistory = { scope.launch { drawer.open() } },
                        onNewConversation = workspace::newConversation,
                        menu = menu,
                        onMenuChange = { menu = it },
                        onCompact = { current?.let(workspace::compact); menu = false },
                        onSummary = {
                            menu = false
                            scope.launch {
                                summary = current?.let { app.conversations.snapshot(it) }
                                summaryOpen = true
                            }
                        },
                        onRename = {
                            renameText = all.firstOrNull { it.id == current }?.title.orEmpty()
                            renameOpen = true
                            menu = false
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                ) {
                    val id = current
                    if (id == null) {
                        LoadingConversation(Modifier.weight(1f))
                    } else {
                        holder.SaveableStateProvider(id) {
                            ChatTimeline(
                                messages = messages,
                                toolCalls = toolCalls,
                                artifacts = conversationArtifacts,
                                toolTitles = toolTitles,
                                notice = notices[id],
                                artifactActions = artifactActions,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    error?.let { message ->
                        InlineError(message) { workspace.error.value = null }
                    }
                    val draft = drafts[id] ?: ComposerDraft()
                    val selectedModel = settings.selectedModel
                    ChatComposer(
                        draft = draft,
                        running = id in active,
                        models = settings.models,
                        selectedModelId = selectedModel?.id,
                        reasoningEfforts = selectedModel?.reasoningEfforts.orEmpty(),
                        selectedReasoningEffort = selectedModel?.selectedReasoningEffort,
                        contextUsage = id?.let { contextUsages[it] }
                            ?.takeIf { it.modelProfileId == null || it.modelProfileId == selectedModel?.id },
                        onChange = { if (id != null) workspace.edit(id, it) },
                        onModelSelect = { modelId ->
                            scope.launch {
                                runCatching { app.modelSettings.setSelectedModel(modelId) }
                                    .onFailure { workspace.error.value = userFacingMessage(it, "模型切换失败，请重试") }
                            }
                        },
                        onReasoningSelect = { effort ->
                            scope.launch {
                                runCatching { app.modelSettings.setSelectedReasoningEffort(effort) }
                                    .onFailure { workspace.error.value = userFacingMessage(it, "思考强度未保存，请重试") }
                            }
                        },
                        onSend = {
                            if (id != null) workspace.send(
                                id,
                                selectedModel?.selectedReasoningEffort,
                                selectedModel?.id,
                            )
                        },
                        onStop = { if (id != null) workspace.stop(id) },
                        onAttach = { attachmentTarget = id; picker.launch(arrayOf("*/*")) },
                        onTools = { panel = "tools" },
                        onModel = { settingsPage = "model" },
                        voiceMode = voiceMode,
                        voiceInputState = voiceInputState,
                        onVoiceModeChange = { enabled ->
                            voiceMode = enabled
                            app.voiceInput.clearFailure()
                        },
                        onVoiceStart = {
                            when {
                                id == null || selectedModel == null -> false
                                !speechSettings.configured -> {
                                    workspace.error.value = "请先在设置的“语音”中配置转写服务"
                                    settingsPage = "voice"
                                    false
                                }
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                                    PackageManager.PERMISSION_GRANTED -> {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    false
                                }
                                else -> app.voiceInput.start(
                                    VoiceInputTarget(
                                        id,
                                        selectedModel.selectedReasoningEffort,
                                        selectedModel.id,
                                        VoiceInputSource.CHAT,
                                    ),
                                ).isSuccess
                            }
                        },
                        onVoiceFinish = app.voiceInput::finish,
                        onVoiceCancel = app.voiceInput::cancel,
                        enabled = id != null,
                    )
                }
            }
        }

        if (pendingApproval == null && pendingQuestion == null) incomingShare?.let { share ->
            IncomingShareSheet(
                share = share,
                conversations = all,
                currentConversationId = current,
                onNewConversation = { workspace.acceptShare(null, share) },
                onConversation = { id -> workspace.acceptShare(id, share) },
                onDismiss = { workspace.dismissShare(share) },
            )
        }

        if (pendingApproval == null && pendingQuestion == null && incomingShare == null) panel?.let {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val sheetHeight = screenHeight * .94f
            ModalBottomSheet(
                onDismissRequest = { panel = null },
                sheetState = sheetState,
                containerColor = colors.background,
                contentColor = colors.text,
                dragHandle = null,
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            ) {
                Column(Modifier.fillMaxWidth().height(sheetHeight)) {
                    SheetTopHandle(onClose = { panel = null })
                    ToolsPanel(
                        capabilities = toolCapabilities,
                        accesses = toolAccesses,
                        onEnabled = { capabilityId, enabled ->
                            scope.launch {
                                runCatching { app.toolPermissions.setEnabled(capabilityId, enabled) }
                                    .onFailure { workspace.error.value = userFacingMessage(it, "工具状态未保存，请重试") }
                            }
                        },
                        onPermission = { capabilityId, mode ->
                            scope.launch {
                                runCatching { app.toolPermissions.setPermission(capabilityId, mode) }
                                    .onFailure { workspace.error.value = userFacingMessage(it, "工具权限未保存，请重试") }
                            }
                        },
                    )
                }
            }
        }

        pendingApproval?.let { request ->
            key(request.callId) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var deciding by remember { mutableStateOf(false) }
                val decide: (Boolean, String?, Boolean) -> Unit = { allow, choiceId, permanentlyAllow ->
                    if (!deciding) {
                        deciding = true
                        scope.launch {
                            app.chatRuntime.decideTool(
                                request.callId,
                                allow,
                                choiceId,
                                permanentlyAllow,
                            )
                        }
                    }
                }
                ModalBottomSheet(
                    onDismissRequest = { decide(false, null, false) },
                    sheetState = sheetState,
                    containerColor = colors.surface,
                    contentColor = colors.text,
                    shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                ) {
                    ToolApprovalSheet(request, deciding, decide)
                }
            }
        }

        if (pendingApproval == null) pendingQuestion?.let { request ->
            key(request.id) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var submitting by remember { mutableStateOf(false) }
                val answer: (String?) -> Unit = { value ->
                    if (!submitting) {
                        submitting = true
                        scope.launch { app.userQuestions.respond(request.id, value) }
                    }
                }
                ModalBottomSheet(
                    onDismissRequest = { answer(null) },
                    sheetState = sheetState,
                    containerColor = colors.surface,
                    contentColor = colors.text,
                    shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                ) {
                    UserQuestionSheet(request, submitting, answer)
                }
            }
        }

        if (summaryOpen) {
            ContextSummaryDialog(
                summary = summary,
                onSource = { sourceId ->
                    scope.launch {
                        sourceMessage = current?.let { id ->
                            app.conversations.messages(id).firstOrNull { it.id == sourceId }
                        }
                        if (sourceMessage == null) workspace.error.value = "暂时无法读取这条历史消息，请稍后重试"
                    }
                },
                onClose = { summaryOpen = false },
            )
        }

        sourceMessage?.let { source ->
            SourceMessageDialog(source = source, onClose = { sourceMessage = null })
        }

        if (renameOpen) {
            RenameConversationDialog(
                text = renameText,
                onTextChange = { renameText = it },
                onSave = {
                    current?.let { workspace.rename(it, renameText) }
                    renameOpen = false
                },
                onClose = { renameOpen = false },
            )
        }

        artifactPreview?.let { artifact ->
            ArtifactPreviewDialog(
                artifact = artifact,
                content = artifactPreviewContent,
                onShare = { artifactActions.onShare(artifact) },
                onDismiss = {
                    artifactPreview = null
                    artifactPreviewContent = null
                },
            )
        }

        artifactToDelete?.let { artifact ->
            AlertDialog(
                onDismissRequest = { artifactToDelete = null },
                shape = RoundedCornerShape(24.dp),
                containerColor = colors.surface,
                title = { Text("删除文件？") },
                text = { Text("删除后将无法在这段对话中打开此文件。此操作不会影响其他应用中的文件。") },
                dismissButton = {
                    TextButton(onClick = { artifactToDelete = null }) { Text("取消") }
                },
                confirmButton = {
                    TextButton(onClick = {
                        artifactToDelete = null
                        scope.launch {
                            runCatching { app.artifacts.deleteArtifact(artifact.id) }
                                .onSuccess { deleted ->
                                    if (deleted) current?.let { id ->
                                        val draft = workspace.drafts.value[id] ?: return@let
                                        workspace.edit(id, draft.copy(attachments = draft.attachments.filterNot {
                                            it.uri == artifact.contentUri
                                        }))
                                    }
                                }
                                .onFailure { workspace.error.value = userFacingMessage(it, "文件未删除，请重试") }
                        }
                    }) { Text("删除", color = colors.error) }
                },
            )
        }
    }
}

private data class ArtifactActions(
    val onOpen: (Artifact) -> Unit,
    val onShare: (Artifact) -> Unit,
    val onReuse: (Artifact) -> Unit,
    val onDelete: (Artifact) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    running: Boolean,
    onHistory: () -> Unit,
    onNewConversation: () -> Unit,
    menu: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onCompact: () -> Unit,
    onSummary: () -> Unit,
    onRename: () -> Unit,
) {
    val colors = LocalChatColors.current
    CenterAlignedTopAppBar(
        title = {
            Text(
                if (running) "正在回复" else title.takeUnless { it == "新对话" }.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = if (running) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            HeaderIcon(R.drawable.lucide_panel_left, "历史对话", onHistory)
        },
        actions = {
            HeaderIcon(R.drawable.lucide_square_pen, "新对话", onNewConversation)
            Box {
                HeaderIcon(R.drawable.lucide_ellipsis, "对话菜单") { onMenuChange(true) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { onMenuChange(false) },
                    containerColor = colors.surfaceRaised,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 10.dp,
                ) {
                    DropdownMenuItem(text = { MenuText("整理较早对话", "释放上下文空间，完整记录仍会保留") }, onClick = onCompact)
                    DropdownMenuItem(text = { MenuText("查看对话摘要", "查看保留的要点和来源") }, onClick = onSummary)
                    DropdownMenuItem(text = { MenuText("修改对话标题", "让历史记录更容易查找") }, onClick = onRename)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
    )
}

@Composable
private fun HeaderIcon(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        ChatIcon(icon, label, Modifier.size(22.dp))
    }
}

@Composable
private fun MenuText(title: String, subtitle: String) {
    Column(Modifier.widthIn(min = 198.dp).padding(vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(subtitle, style = MaterialTheme.typography.labelSmall,
            color = LocalChatColors.current.secondary)
    }
}

@Composable
private fun HistoryDrawer(
    conversations: List<Conversation>,
    current: String?,
    active: Set<String>,
    search: String,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    onNewConversation: () -> Unit,
    onSelect: (String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalChatColors.current
    val filtered = conversations.filter { it.title.contains(search, ignoreCase = true) }
    val pinned = filtered.filter(Conversation::pinned)
    val recent = filtered.filterNot(Conversation::pinned)
    var menuConversationId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    Column(Modifier.fillMaxHeight().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = colors.accentSoft) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    ChatIcon(R.drawable.lucide_sparkles, null, Modifier.size(23.dp), colors.accent)
                }
            }
            Text(
                "mobile agent",
                Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
            HeaderIcon(R.drawable.lucide_panel_left, "关闭侧栏", onClose)
        }

        Surface(
            modifier = Modifier.fillMaxWidth()
                .shadow(7.dp, RoundedCornerShape(28.dp), ambientColor = colors.text.copy(alpha = .05f),
                    spotColor = colors.text.copy(alpha = .07f))
                .clickable(onClick = onNewConversation),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            contentColor = colors.text,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Row(
                Modifier.height(54.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                ChatIcon(R.drawable.lucide_square_pen, null, Modifier.size(20.dp))
                Text("新建对话", Modifier.padding(start = 9.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }

        DrawerSearch(search, onSearch)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pinned.isNotEmpty()) {
                item(key = "pinned_header") {
                    SectionLabel("置顶", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
                }
                items(pinned, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == current,
                        running = conversation.id in active,
                        menuExpanded = menuConversationId == conversation.id,
                        onClick = { onSelect(conversation.id) },
                        onLongClick = { menuConversationId = conversation.id },
                        onMenuDismiss = { menuConversationId = null },
                        onSetPinned = { pinnedValue -> onSetPinned(conversation.id, pinnedValue) },
                        onDelete = { pendingDelete = conversation },
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent_header") {
                    SectionLabel("最近", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
                }
                items(recent, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == current,
                        running = conversation.id in active,
                        menuExpanded = menuConversationId == conversation.id,
                        onClick = { onSelect(conversation.id) },
                        onLongClick = { menuConversationId = conversation.id },
                        onMenuDismiss = { menuConversationId = null },
                        onSetPinned = { pinnedValue -> onSetPinned(conversation.id, pinnedValue) },
                        onDelete = { pendingDelete = conversation },
                    )
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text("没有找到相关对话", Modifier.padding(18.dp), color = colors.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrawerQuickAction("工具", R.drawable.lucide_sliders_horizontal, onTools, Modifier.weight(1f))
            DrawerQuickAction("设置", R.drawable.lucide_settings, onSettings,
                Modifier.weight(1f).testTag("settings"))
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp).clickable(onClick = onSettings)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = colors.surfaceRaised) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    ChatIcon(R.drawable.lucide_user_round, null, Modifier.size(21.dp), colors.secondary)
                }
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text("本机存储", style = MaterialTheme.typography.bodyMedium)
            Text("对话记录保存在此设备", color = colors.tertiary, style = MaterialTheme.typography.labelSmall)
            }
            ChatIcon(R.drawable.lucide_ellipsis, null, Modifier.size(20.dp), colors.tertiary)
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
            title = { Text("删除对话？") },
            text = { Text("“${conversation.title}”中的消息、摘要和工具记录将被永久删除，此操作无法撤销。") },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(conversation.id)
                }) { Text("删除", color = colors.error) }
            },
        )
    }
}

@Composable
private fun DrawerSearch(value: String, onChange: (String) -> Unit) {
    val colors = LocalChatColors.current
    Surface(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = colors.surfaceRaised,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChatIcon(R.drawable.lucide_search, null, Modifier.size(18.dp), colors.tertiary)
                    if (value.isBlank()) Text("搜索对话", color = colors.tertiary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 9.dp))
                    Box(Modifier.weight(1f).padding(start = if (value.isBlank()) 0.dp else 9.dp)) { inner() }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    running: Boolean,
    menuExpanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuDismiss: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalChatColors.current
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = onClick,
                onLongClickLabel = "打开对话操作",
                onLongClick = onLongClick,
            ),
            shape = RoundedCornerShape(15.dp),
            color = if (selected) colors.accentSoft else Color.Transparent,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(
                    if (running) colors.accent else if (selected) colors.accent.copy(alpha = .52f) else colors.divider,
                    CircleShape,
                ))
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    val status = listOfNotNull(
                        "已置顶".takeIf { conversation.pinned },
                        "正在回复".takeIf { running },
                    ).joinToString(" · ")
                    if (status.isNotEmpty()) Text(status, color = if (running) colors.accent else colors.tertiary,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onMenuDismiss,
            containerColor = colors.surfaceRaised,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
        ) {
            DropdownMenuItem(
                text = {
                    MenuText(
                        if (conversation.pinned) "取消置顶" else "置顶对话",
                        if (conversation.pinned) "恢复按最近使用时间排序" else "固定在历史记录顶部",
                    )
                },
                onClick = {
                    onMenuDismiss()
                    onSetPinned(!conversation.pinned)
                },
            )
            DropdownMenuItem(
                text = {
                    MenuText(
                        "删除对话",
                        if (running) "请先停止当前回复" else "永久删除消息和相关记录",
                    )
                },
                enabled = !running,
                onClick = {
                    onMenuDismiss()
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DrawerQuickAction(
    title: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceRaised,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            ChatIcon(icon, null, Modifier.size(18.dp), colors.secondary)
            Text(title, Modifier.padding(start = 7.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LoadingConversation(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppGlyph("M", modifier = Modifier.size(48.dp))
            Text("正在恢复对话…", color = LocalChatColors.current.secondary,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChatTimeline(
    messages: List<Message>,
    toolCalls: List<ToolCallRecord>,
    artifacts: List<Artifact>,
    toolTitles: Map<String, String>,
    notice: String?,
    artifactActions: ArtifactActions,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    val latestIndex = messages.size + if (notice != null) 1 else 0
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, below) ->
            if (scrolling && !programmaticScroll) follow = !below
        }
    }
    LaunchedEffect(messages.lastOrNull()?.text, messages.lastOrNull()?.assistantSteps, messages.size,
        toolCalls.lastOrNull()?.status, artifacts.lastOrNull()?.id, notice) {
        if (follow && messages.isNotEmpty()) list.scrollToItem(latestIndex)
    }
    Box(modifier.fillMaxWidth()) {
        if (messages.isEmpty()) {
            EmptyConversation(notice, Modifier.align(Alignment.CenterStart).offset(y = (-24).dp))
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("messages"),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message,
                        toolCalls.filter { it.replyMessageId == message.id },
                        artifacts.filter { it.replyMessageId == message.id },
                        toolTitles,
                        artifactActions,
                    )
                }
                if (notice != null) {
                    item { TimelineNotice(notice) }
                }
                // 独立的末尾锚点保证长回复也能滚到内容底部，而不是最后一条消息的开头。
                item(key = "timeline_bottom") { Spacer(Modifier.height(1.dp)) }
            }
        }
        if (!follow && messages.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .shadow(8.dp, RoundedCornerShape(20.dp)).clickable {
                        scope.launch {
                            programmaticScroll = true
                            try {
                                list.animateScrollToItem(latestIndex)
                                follow = true
                            } finally {
                                programmaticScroll = false
                            }
                        }
                    },
                shape = RoundedCornerShape(20.dp),
                color = LocalChatColors.current.surface,
                border = BorderStroke(1.dp, LocalChatColors.current.divider),
            ) {
                Text("回到最新", Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyConversation(notice: String?, modifier: Modifier) {
    val colors = LocalChatColors.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatIcon(R.drawable.lucide_sparkles, null, Modifier.size(31.dp), colors.accent)
            Text(
                "你好，我能帮你做什么？",
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        notice?.let { TimelineNotice(it) }
    }
}

@Composable
private fun MessageRow(
    message: Message,
    toolCalls: List<ToolCallRecord>,
    artifacts: List<Artifact>,
    toolTitles: Map<String, String>,
    artifactActions: ArtifactActions,
) {
    val colors = LocalChatColors.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
    ) {
        if (message.role == MessageRole.USER) {
            Surface(
                shape = RoundedCornerShape(topStart = 21.dp, topEnd = 7.dp, bottomStart = 21.dp, bottomEnd = 21.dp),
                color = colors.userBubble,
                modifier = Modifier.fillMaxWidth(.86f),
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                    // 与 AI 正文和思考内容保持一致：长按进入系统原生选区，可复制整段或局部文字。
                    SelectionContainer {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    message.attachments.forEach { AttachmentCard(it) }
                }
            }
        } else if (message.text.isNotEmpty() || message.reasoningSteps.isNotEmpty() || message.assistantSteps.isNotEmpty() ||
            message.status == MessageStatus.GENERATING || toolCalls.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatIcon(R.drawable.lucide_sparkles, null, Modifier.size(18.dp), colors.accent)
                Text("Mobile Agent", Modifier.padding(start = 7.dp), color = colors.secondary,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
            AssistantMessageContent(message, toolCalls, artifacts, toolTitles, artifactActions)
        }
        MessageStatusLine(message)
    }
}

@Composable
private fun AssistantMessageContent(
    message: Message,
    toolCalls: List<ToolCallRecord>,
    artifacts: List<Artifact>,
    toolTitles: Map<String, String>,
    artifactActions: ArtifactActions,
) {
    val visibleCalls = toolCalls.filterNot { it.status == ToolCallStatus.WAITING_APPROVAL }
    if (message.assistantSteps.isEmpty()) {
        // 旧消息没有轮次映射，只能按旧结构显示；新消息全部走下面的精确顺序。
        LegacyAssistantContent(message, visibleCalls, artifacts, toolTitles, artifactActions)
        return
    }
    val assignedCallIds = message.assistantSteps.flatMap(AssistantStep::toolCallIds).toSet()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        message.assistantSteps.forEachIndexed { index, step ->
            AssistantStepContent(
                message = message,
                step = step,
                stepIndex = index,
                toolCalls = visibleCalls.filter { it.id in step.toolCallIds },
                artifacts = artifacts.filter { it.sourceToolCallId in step.toolCallIds },
                toolTitles = toolTitles,
                artifactActions = artifactActions,
                isCurrent = message.status == MessageStatus.GENERATING &&
                    index == message.assistantSteps.lastIndex && step.toolCallIds.isEmpty() && step.text.isEmpty(),
            )
        }
        // 极短的数据库观察时序中，工具记录可能先于消息快照到达；暂时放在末尾，下一帧会归位。
        visibleCalls.filterNot { it.id in assignedCallIds }.forEach { call ->
            ToolCallSummary(
                call,
                toolTitles,
                artifacts.filter { it.sourceToolCallId == call.id },
                artifactActions,
            )
        }
    }
}

@Composable
private fun LegacyAssistantContent(
    message: Message,
    toolCalls: List<ToolCallRecord>,
    artifacts: List<Artifact>,
    toolTitles: Map<String, String>,
    artifactActions: ArtifactActions,
) {
    ReasoningDisclosure(
        reasoning = message.reasoningSteps.joinToString("\n\n"),
        durationMillis = message.reasoningDurationMillis,
        active = message.status == MessageStatus.GENERATING,
        stateKey = "${message.id}:legacy",
    )
    toolCalls.forEach { call ->
        ToolCallSummary(
            call,
            toolTitles,
            artifacts.filter { it.sourceToolCallId == call.id },
            artifactActions,
        )
    }
    if (message.text.isNotEmpty()) {
        Column(Modifier.padding(top = 8.dp, end = 6.dp)) { ReplyBody(message.text) }
    }
}

@Composable
private fun AssistantStepContent(
    message: Message,
    step: AssistantStep,
    stepIndex: Int,
    toolCalls: List<ToolCallRecord>,
    artifacts: List<Artifact>,
    toolTitles: Map<String, String>,
    artifactActions: ArtifactActions,
    isCurrent: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReasoningDisclosure(
            reasoning = step.reasoning,
            durationMillis = step.reasoningDurationMillis,
            active = isCurrent,
            stateKey = "${message.id}:$stepIndex",
        )
        toolCalls.forEach { call ->
            ToolCallSummary(
                call,
                toolTitles,
                artifacts.filter { it.sourceToolCallId == call.id },
                artifactActions,
            )
        }
        if (step.text.isNotEmpty()) {
            Column(Modifier.padding(end = 6.dp)) { ReplyBody(step.text) }
        }
    }
}

@Composable
private fun ToolCallSummary(
    call: ToolCallRecord,
    toolTitles: Map<String, String>,
    artifacts: List<Artifact>,
    artifactActions: ArtifactActions,
) {
    Column {
        ToolSummaryRow(ToolSummary(
            title = toolTitles[call.toolId] ?: "工具调用",
            detail = call.displaySummary
                ?: call.error?.let { userFacingMessage(it, "工具未完成") }
                ?: toolStatusText(call.status),
            state = toolStatusText(call.status),
        ))
        NetworkSourceLinks(call)
        artifacts.forEach { artifact ->
            ArtifactCard(
                artifact = artifact,
                onOpen = { artifactActions.onOpen(artifact) },
                onShare = { artifactActions.onShare(artifact) },
                onReuse = { artifactActions.onReuse(artifact) },
                onDelete = { artifactActions.onDelete(artifact) },
            )
        }
    }
}

private data class NetworkSourceLink(val title: String, val url: String, val snippet: String)

/** 搜索结果跟随对应工具步骤展示，历史恢复后仍能打开当时使用的真实来源。 */
@Composable
private fun NetworkSourceLinks(call: ToolCallRecord) {
    if (call.toolId !in setOf("network_search", "network_fetch")) return
    val sources = remember(call.toolId, call.result) { parseNetworkSources(call) }
    if (sources.isEmpty()) return
    val colors = LocalChatColors.current
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("来源", color = colors.secondary, style = MaterialTheme.typography.labelMedium)
        sources.forEachIndexed { index, source ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching { uriHandler.openUri(source.url) }
                },
                color = colors.surfaceRaised,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, colors.divider),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        (index + 1).toString(),
                        color = colors.accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(
                            source.title.ifBlank { source.url },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = source.snippet.ifBlank {
                            runCatching { Uri.parse(source.url).host }.getOrNull().orEmpty()
                        }
                        if (detail.isNotBlank()) {
                            Text(
                                detail,
                                color = colors.secondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun parseNetworkSources(call: ToolCallRecord): List<NetworkSourceLink> = runCatching {
    val root = Json.parseToJsonElement(call.result.orEmpty()).jsonObject
    when (call.toolId) {
        "network_search" -> root["results"]?.jsonArray.orEmpty().mapNotNull { item ->
            val value = item.jsonObject
            val url = value["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            NetworkSourceLink(
                value["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                url,
                value["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        "network_fetch" -> {
            val url = root["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) emptyList()
            else listOf(NetworkSourceLink(root["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), url, ""))
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())

private fun toolStatusText(status: ToolCallStatus): String = when (status) {
    ToolCallStatus.RECEIVED -> "准备中"
    ToolCallStatus.WAITING_APPROVAL -> "等待确认"
    ToolCallStatus.EXECUTING -> "执行中"
    ToolCallStatus.SUCCEEDED -> "已完成"
    ToolCallStatus.FAILED -> "未完成"
    ToolCallStatus.DENIED -> "未授权"
    ToolCallStatus.CANCELLED -> "已停止"
    ToolCallStatus.INTERRUPTED -> "已中断"
}

@Composable
private fun ReasoningDisclosure(
    reasoning: String,
    durationMillis: Long?,
    active: Boolean,
    stateKey: String,
) {
    val colors = LocalChatColors.current
    val hasReasoning = reasoning.isNotBlank()
    if (!hasReasoning && !active) return
    // 思考内容可能很长，生成中也默认收起；用户点击后仍保留当前轮次的展开状态。
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val label = when {
        active && !hasReasoning -> "思考 · 进行中"
        active && durationMillis == null -> "思考 · 进行中"
        durationMillis != null -> "思考 · 持续了 ${formatReasoningDuration(durationMillis)}"
        else -> "思考 · 已完成"
    }

    Row(
        modifier = Modifier.padding(top = 7.dp).then(
            if (hasReasoning) Modifier.clickable { expanded = !expanded } else Modifier,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatIcon(R.drawable.lucide_brain_circuit, null, Modifier.size(16.dp), colors.tertiary)
        Text(
            label,
            Modifier.padding(start = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.tertiary,
        )
        if (hasReasoning) {
            ChatIcon(
                R.drawable.lucide_chevron_down,
                if (expanded) "收起思考内容" else "展开思考内容",
                Modifier.padding(start = 4.dp).size(15.dp).rotate(if (expanded) 180f else 0f),
                colors.tertiary,
            )
        }
    }
    if (expanded && hasReasoning) {
        ReasoningStep(reasoning)
    }
}

@Composable
private fun ReasoningStep(text: String) {
    val colors = LocalChatColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp, start = 7.dp, end = 8.dp)) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(colors.divider))
        SelectionContainer {
            Text(
                text,
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiary,
            )
        }
    }
}

@Composable
private fun ToolApprovalSheet(
    request: ToolApprovalRequest,
    deciding: Boolean,
    onDecision: (Boolean, String?, Boolean) -> Unit,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    var selectedChoice by rememberSaveable(request.callId) { mutableStateOf<String?>(null) }
    var overlayGranted by remember(request.callId) { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationGranted by remember(request.callId) {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED)
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationGranted = it
    }
    val selected = request.choices.firstOrNull { it.id == selectedChoice }
    val selectionReady = request.choices.isEmpty() || selected != null
    val overlayReady = selected?.requiresOverlayPermission != true || overlayGranted
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * .88f
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxSheetHeight).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppGlyph("!", container = colors.warning.copy(alpha = .14f), content = colors.warning)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    if (request.choices.isEmpty()) "允许使用“${request.capabilityTitle}”？" else "选择手机操作方式",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(request.actionTitle, style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceRaised,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(request.description, style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
                request.argumentsSummary?.takeIf(String::isNotBlank)?.let { summary ->
                    Text(
                        "将要执行",
                        Modifier.padding(top = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.tertiary,
                    )
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = colors.text)
                }
            }
        }
        if (request.choices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                request.choices.forEach { choice ->
                    val supported = choice.id != "background" || Build.VERSION.SDK_INT >= 34
                    val checked = choice.id == selectedChoice
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = supported && !deciding) {
                            selectedChoice = choice.id
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (checked) colors.accentSoft else colors.surface,
                        border = BorderStroke(1.dp, if (checked) colors.accent else colors.divider),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppGlyph(if (choice.id == "background") "后" else "前")
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (supported) choice.description else "后台操作需要 Android 14 或更高版本",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
        if ((selected?.requiresOverlayPermission == true || selected?.recommendsOverlayPermission == true) &&
            !overlayGranted
        ) {
            val required = selected?.requiresOverlayPermission == true
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !deciding) {
                    overlayPermission.launch(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ))
                },
                shape = RoundedCornerShape(14.dp),
                color = colors.warningSoft,
            ) {
                Text(
                    if (required) {
                        "允许显示悬浮窗，以便离开 Mobile Agent 后继续查看操作步骤"
                    } else {
                        "开启悬浮控制条（推荐），离开 Mobile Agent 后可查看进度并立即停止；不开启也可继续，请确保设备操作通知可用"
                    },
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.warning,
                )
            }
        }
        if (selected != null && !notificationGranted && Build.VERSION.SDK_INT >= 33) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !deciding) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceRaised,
                border = BorderStroke(1.dp, colors.divider),
            ) {
                Text(
                    "允许设备操作通知，以便悬浮控制条不可用时仍能查看进度和停止",
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondary,
                )
            }
        }
        if (request.requiresPermissionApproval) {
            Text(
                "选择“始终允许”后，今后使用“${request.capabilityTitle}”时不再询问。你可以随时在工具设置中修改。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = { onDecision(false, null, false) },
                enabled = !deciding,
                modifier = Modifier.weight(.8f),
            ) { Text("不允许") }
            if (request.requiresPermissionApproval) {
                Surface(
                    modifier = Modifier.weight(1.15f).clickable(
                        enabled = !deciding && selectionReady && overlayReady,
                    ) { onDecision(true, selectedChoice, true) },
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.accent),
                    contentColor = colors.accent,
                ) {
                    Text(
                        "始终允许",
                        Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Surface(
                modifier = Modifier.weight(1f).clickable(
                    enabled = !deciding && selectionReady && overlayReady,
                ) { onDecision(true, selectedChoice, false) },
                shape = RoundedCornerShape(14.dp),
                color = if (selectionReady && overlayReady) colors.accent else colors.muted,
                contentColor = Color.White,
            ) {
                Text(
                    when {
                        deciding -> "处理中…"
                        request.choices.isNotEmpty() -> "开始操作"
                        else -> "仅本次允许"
                    },
                    Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun UserQuestionSheet(
    request: UserQuestionRequest,
    submitting: Boolean,
    onAnswer: (String?) -> Unit,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    var answer by rememberSaveable(request.id) { mutableStateOf("") }
    var overlayGranted by remember(request.id) { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * .88f
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxSheetHeight).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppGlyph("?", container = colors.accentSoft, content = colors.accent)
            Column(Modifier.padding(start = 12.dp)) {
                Text("AI 需要你的回答", style = MaterialTheme.typography.titleLarge)
                Text("回答后会继续当前任务", style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceRaised,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Text(
                request.question,
                Modifier.fillMaxWidth().padding(15.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
            )
        }
        request.options.forEach { option ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !submitting) { onAnswer(option) },
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.divider),
            ) {
                Text(option, Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (request.allowFreeText) {
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it.take(2_000) },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (request.options.isEmpty()) "你的回答" else "其他回答") },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (!overlayGranted) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !submitting) {
                    overlayPermission.launch(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ))
                },
                shape = RoundedCornerShape(14.dp),
                color = colors.accentSoft,
            ) {
                Text(
                    "开启“显示在其他应用上层”，下次离开 Mobile Agent 后也能直接看到并回答问题。",
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = { onAnswer(null) },
                enabled = !submitting,
                modifier = Modifier.weight(1f),
            ) { Text("暂不回答") }
            if (request.allowFreeText) {
                Surface(
                    modifier = Modifier.weight(1f).clickable(
                        enabled = !submitting && answer.isNotBlank(),
                    ) { onAnswer(answer.trim()) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (answer.isNotBlank()) colors.accent else colors.muted,
                    contentColor = Color.White,
                ) {
                    Text(
                        if (submitting) "提交中…" else "提交回答",
                        Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun formatReasoningDuration(durationMillis: Long): String {
    val totalSeconds = maxOf(1L, (durationMillis + 999L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes == 0L) "${seconds} 秒" else "${minutes} 分 ${seconds} 秒"
}

@Composable
private fun MessageStatusLine(message: Message) {
    val colors = LocalChatColors.current
    when (message.status) {
        MessageStatus.QUEUED -> StatusPill("将在当前回复完成后发送", colors.warning, colors.warningSoft)
        MessageStatus.GENERATING -> Unit
        MessageStatus.FAILED, MessageStatus.CANCELLED, MessageStatus.INTERRUPTED ->
            StatusPill(userFacingMessage(message.error, "本次回复未完成"), colors.error, colors.errorSoft)
        else -> Unit
    }
}

@Composable
private fun StatusPill(text: String, color: Color, container: Color) {
    Surface(Modifier.padding(top = 8.dp), shape = RoundedCornerShape(10.dp), color = container) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = color,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TimelineNotice(text: String) {
    val colors = LocalChatColors.current
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceRaised,
        border = BorderStroke(1.dp, colors.divider)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), color = colors.secondary,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InlineError(message: String, onDismiss: () -> Unit) {
    val colors = LocalChatColors.current
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clickable(onClick = onDismiss),
        shape = RoundedCornerShape(14.dp), color = colors.errorSoft,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = colors.error, style = MaterialTheme.typography.bodySmall)
            Text("关闭", color = colors.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IncomingShareSheet(
    share: IncomingShare,
    conversations: List<Conversation>,
    currentConversationId: String?,
    onNewConversation: () -> Unit,
    onConversation: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
        Text("选择对话", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val summary = buildString {
                if (share.attachments.isNotEmpty()) append("${share.attachments.size} 个附件")
                if (share.text.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("含文字内容")
                }
            }
            Text(summary, Modifier.padding(top = 5.dp, bottom = 16.dp), color = colors.secondary)
            ShareTargetRow("新建对话", "新建对话并保留为草稿", onNewConversation)
            if (conversations.isNotEmpty()) {
                SectionLabel("已有对话", Modifier.padding(top = 18.dp, bottom = 7.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(conversations.take(20), key = Conversation::id) { conversation ->
                        ShareTargetRow(
                            title = conversation.title,
                            detail = if (conversation.id == currentConversationId) "当前对话" else "添加到这段对话的草稿",
                            onClick = { onConversation(conversation.id) },
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun ShareTargetRow(title: String, detail: String, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIcon(R.drawable.lucide_square_pen, null, tint = colors.secondary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = colors.secondary, style = MaterialTheme.typography.bodySmall)
            }
            ChatIcon(R.drawable.lucide_chevron_right, null, Modifier.size(18.dp), colors.tertiary)
        }
    }
}

@Composable
private fun ChatComposer(
    draft: ComposerDraft,
    running: Boolean,
    models: List<ModelProfile>,
    selectedModelId: String?,
    reasoningEfforts: List<String>,
    selectedReasoningEffort: String?,
    contextUsage: ContextUsage?,
    onChange: (ComposerDraft) -> Unit,
    onModelSelect: (String) -> Unit,
    onReasoningSelect: (String?) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onTools: () -> Unit,
    onModel: () -> Unit,
    voiceMode: Boolean,
    voiceInputState: VoiceInputState,
    onVoiceModeChange: (Boolean) -> Unit,
    onVoiceStart: () -> Boolean,
    onVoiceFinish: () -> Unit,
    onVoiceCancel: () -> Unit,
    enabled: Boolean,
) {
    val colors = LocalChatColors.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(28.dp)
    var modelMenu by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf(false) }
    val currentVoiceStart by rememberUpdatedState(onVoiceStart)
    val currentVoiceFinish by rememberUpdatedState(onVoiceFinish)
    val currentVoiceCancel by rememberUpdatedState(onVoiceCancel)
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
            .shadow(16.dp, shape, ambientColor = colors.text.copy(alpha = .07f),
                spotColor = colors.text.copy(alpha = .10f)),
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            if (draft.attachments.isNotEmpty()) {
                Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                    draft.attachments.forEach { attachment ->
                        AttachmentCard(attachment) {
                            onChange(draft.copy(attachments = draft.attachments.filterNot { it.uri == attachment.uri }))
                        }
                    }
                }
            }
            if (voiceMode) {
                val recording = voiceInputState is VoiceInputState.Recording
                val label = when (voiceInputState) {
                    is VoiceInputState.Recording -> "正在录音，松开发送"
                    is VoiceInputState.Transcribing -> "正在转写并发送…"
                    is VoiceInputState.Failed -> voiceInputState.message
                    VoiceInputState.Idle -> "按住说话"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .padding(horizontal = 4.dp, vertical = 5.dp)
                        .pointerInput(enabled) {
                            if (enabled) detectTapGestures(
                                onPress = {
                                    if (currentVoiceStart()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // 开始录音会立即改变语音状态；这里不能重启手势协程，否则松开前
                                        // onPress 就会被取消，最终表现为“录完却不发送”。
                                        if (tryAwaitRelease()) currentVoiceFinish() else currentVoiceCancel()
                                    }
                                },
                            )
                        }
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                        }
                        .testTag("voice_composer"),
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        recording -> colors.errorSoft
                        voiceInputState is VoiceInputState.Failed -> colors.warningSoft
                        else -> colors.surfaceRaised
                    },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatIcon(
                            R.drawable.lucide_mic,
                            null,
                            Modifier.size(19.dp),
                            if (recording) colors.error else colors.secondary,
                        )
                        Text(
                            label,
                            Modifier.padding(start = 8.dp),
                            color = when {
                                recording -> colors.error
                                voiceInputState is VoiceInputState.Failed -> colors.warning
                                else -> colors.secondary
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                BasicTextField(
                    value = draft.text,
                    onValueChange = { onChange(draft.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp, max = 160.dp)
                        .padding(horizontal = 10.dp, vertical = 10.dp).testTag("composer"),
                    enabled = enabled,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    decorationBox = { inner ->
                        Box {
                            if (draft.text.isEmpty()) {
                                Text("发消息，或描述你想完成的事", color = colors.tertiary,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComposerIcon(
                    if (voiceMode) R.drawable.lucide_keyboard else R.drawable.lucide_mic,
                    if (voiceMode) "切换到文字输入" else "切换到语音输入",
                    enabled && voiceInputState !is VoiceInputState.Recording,
                    { onVoiceModeChange(!voiceMode) },
                    filled = true,
                )
                ComposerIcon(R.drawable.lucide_plus, "添加附件", enabled, onAttach, filled = true)
                ComposerIcon(R.drawable.lucide_sliders_horizontal, "打开工具设置", true, onTools)
                ReasoningModePicker(
                    selected = selectedReasoningEffort,
                    efforts = reasoningEfforts,
                    enabled = enabled && selectedModel != null,
                    onSelect = onReasoningSelect,
                )
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        selectedModel?.let { profile ->
                            ContextUsageIndicator(
                                usage = contextUsage,
                                model = profile,
                                expanded = contextMenu,
                                onExpand = {
                                    modelMenu = false
                                    contextMenu = true
                                },
                                onDismiss = { contextMenu = false },
                            )
                        }
                        Surface(
                            modifier = Modifier.padding(horizontal = 2.dp)
                                .clickable {
                                    contextMenu = false
                                    modelMenu = true
                                },
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Transparent,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(selectedModel?.name ?: "未配置模型",
                                    color = colors.secondary, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 96.dp))
                                ChatIcon(R.drawable.lucide_chevron_down, null,
                                    Modifier.padding(start = 3.dp).size(15.dp), colors.tertiary)
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false },
                        containerColor = colors.surface,
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 0.dp,
                        shadowElevation = 12.dp,
                    ) {
                        models.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        Modifier.widthIn(min = 240.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(profile.name, maxLines = 1,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (profile.id == selectedModel?.id) FontWeight.SemiBold else FontWeight.Normal)
                                            Text(profile.model, maxLines = 1, color = colors.secondary,
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (profile.id == selectedModel?.id) {
                                            Text("当前", color = colors.accent,
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                },
                                onClick = {
                                    modelMenu = false
                                    onModelSelect(profile.id)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(Modifier.widthIn(min = 240.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (models.isEmpty()) "添加模型" else "管理模型",
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    ChatIcon(R.drawable.lucide_chevron_right, null,
                                        Modifier.padding(start = 8.dp).size(17.dp), colors.tertiary)
                                }
                            },
                            onClick = { modelMenu = false; onModel() },
                        )
                    }
                }
                if (running) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.text, contentColor = colors.background,
                        ),
                    ) { ChatIcon(R.drawable.lucide_square, "停止回复", Modifier.size(17.dp)) }
                }
                val canSend = selectedModel != null &&
                    (draft.text.isNotBlank() || draft.attachments.any(AttachmentRef::isImage))
                if (running && canSend) {
                    TextButton(onClick = onSend) { Text("发送补充") }
                } else if (!running) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = enabled && canSend,
                        modifier = Modifier.size(44.dp).testTag("send"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.accent, contentColor = colors.onAccent,
                            disabledContainerColor = colors.muted, disabledContentColor = colors.tertiary,
                        ),
                    ) { ChatIcon(R.drawable.lucide_arrow_up, "发送消息", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ContextUsageIndicator(
    usage: ContextUsage?,
    model: ModelProfile,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    val window = usage?.windowTokens ?: model.contextWindow
    val outputReserve = usage?.outputReserve ?: model.outputReserve
    // 最大输出属于总上下文，即使尚无一次请求，也要在进度中体现已经预留的额度。
    val input = usage?.inputTokens ?: 0
    val occupied = (input.toLong() + outputReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val fraction = if (window <= 0) 0f else (occupied.toFloat() / window).coerceIn(0f, 1f)
    val percent = if (window <= 0) 0 else
        ((occupied.toLong() * 100 + window / 2) / window).coerceIn(0, 100).toInt()
    val indicatorColor = when {
        fraction >= .95f -> colors.error
        fraction >= .8f -> colors.warning
        else -> colors.accent
    }
    Box {
        Surface(
            modifier = Modifier.size(36.dp).clickable(onClickLabel = "查看上下文额度", onClick = onExpand),
            shape = CircleShape,
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(17.dp),
                    color = indicatorColor,
                    trackColor = colors.divider,
                    strokeWidth = 2.dp,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset((-150).dp, (-4).dp),
            containerColor = Color(0xFF1D1D1F),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 0.dp,
            shadowElevation = 14.dp,
        ) {
            Column(
                Modifier.widthIn(min = 220.dp, max = 260.dp)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("上下文额度", color = Color(0xFFA9A9AE),
                    style = MaterialTheme.typography.labelMedium)
                Text("$percent% 已占用", color = Color(0xFFD3D3D7),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                val inputText = when {
                    usage == null -> "尚未计算"
                    usage.exact -> compactTokenCount(input)
                    else -> "≈${compactTokenCount(input)}"
                }
                Text(
                    "输入 $inputText · 输出预留 ${compactTokenCount(outputReserve)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "合计 ${compactTokenCount(occupied)} / ${compactTokenCount(window)}" +
                        " · 剩余 ${compactTokenCount((window - occupied).coerceAtLeast(0))}",
                    color = Color(0xFFD3D3D7),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                if (usage?.compacted == true) {
                    Text("较早内容已自动压缩", color = Color(0xFFAEBBFF),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun compactTokenCount(value: Int): String {
    if (value < 1_000) return value.toString()
    val tenths = (value + 50) / 100
    return if (tenths % 10 == 0) "${tenths / 10}K" else "${tenths / 10}.${tenths % 10}K"
}

@Composable
private fun ReasoningModePicker(
    selected: String?,
    efforts: List<String>,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalChatColors.current
    var menu by remember { mutableStateOf(false) }
    val selectedLabel = when (selected) {
        REASONING_EFFORT_OFF -> "关闭"
        null -> "思考"
        else -> selected
    }
    Box {
        Surface(
            modifier = Modifier.height(40.dp).widthIn(max = 86.dp)
                .clickable(enabled = enabled) { menu = true },
            shape = RoundedCornerShape(20.dp),
            color = if (selected == null) Color.Transparent else colors.accentSoft,
        ) {
            Row(
                Modifier.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatIcon(
                    R.drawable.lucide_brain_circuit,
                    "选择思考强度",
                    Modifier.size(18.dp),
                    if (selected == null) colors.secondary else colors.accent,
                )
                Text(
                    selectedLabel,
                    Modifier.padding(start = 5.dp).weight(1f, fill = false),
                    color = if (selected == null) colors.secondary else colors.accent,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("关闭", style = MaterialTheme.typography.bodyMedium)
                        Text("不启用深度思考", color = colors.tertiary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                onClick = { onSelect(REASONING_EFFORT_OFF); menu = false },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("不指定", style = MaterialTheme.typography.bodyMedium)
                        Text("由模型服务决定", color = colors.tertiary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                onClick = { onSelect(null); menu = false },
            )
            efforts.filterNot { it.equals(REASONING_EFFORT_OFF, ignoreCase = true) }.forEach { effort ->
                DropdownMenuItem(
                    text = { Text(effort, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onSelect(effort); menu = false },
                )
            }
        }
    }
}

@Composable
private fun ComposerIcon(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    val colors = LocalChatColors.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        if (filled) {
            Surface(shape = CircleShape, color = colors.surfaceRaised) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    ChatIcon(icon, label, Modifier.size(20.dp))
                }
            }
        } else {
            ChatIcon(icon, label, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SheetTopHandle(onClose: () -> Unit) {
    val colors = LocalChatColors.current
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Surface(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(width = 38.dp, height = 4.dp),
            shape = CircleShape,
            color = colors.outline,
        ) {}
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) {
            Surface(shape = CircleShape, color = colors.surfaceRaised) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    ChatIcon(R.drawable.lucide_x, "关闭")
                }
            }
        }
    }
}

@Composable
private fun ToolsPanel(
    capabilities: List<ToolCapability>,
    accesses: Map<String, ToolAccess>,
    onEnabled: (String, Boolean) -> Unit,
    onPermission: (String, ToolPermissionMode) -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("工具", style = MaterialTheme.typography.headlineSmall)
        Text("管理 AI 可以在对话中使用的工具。关闭后，AI 将无法调用。",
            style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
        Spacer(Modifier.height(2.dp))
        capabilities.forEach { capability ->
            ToolCapabilityCard(
                icon = when (capability.id) {
                    "history" -> R.drawable.lucide_search
                    "files" -> R.drawable.lucide_file_text
                    "network" -> R.drawable.lucide_globe
                    else -> R.drawable.lucide_monitor
                },
                capability = capability,
                access = accesses[capability.id] ?: ToolAccess(),
                onEnabled = { onEnabled(capability.id, it) },
                onPermission = { onPermission(capability.id, it) },
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            shape = RoundedCornerShape(18.dp), color = colors.accentSoft,
        ) {
            Text(
                "选择“每次询问”后，工具会在使用前等待你确认。不可用的工具需要先完成页面显示的条件。",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = colors.secondary,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ToolCapabilityCard(
    icon: Int,
    capability: ToolCapability,
    access: ToolAccess,
    onEnabled: (Boolean) -> Unit,
    onPermission: (ToolPermissionMode) -> Unit,
) {
    val colors = LocalChatColors.current
    var menu by remember { mutableStateOf(false) }
    val editable = capability.availability.state == ToolAvailabilityState.AVAILABLE ||
        capability.availability.state == ToolAvailabilityState.DEGRADED
    val status = when (capability.availability.state) {
        ToolAvailabilityState.AVAILABLE -> "可用"
        ToolAvailabilityState.DEGRADED -> capability.availability.detail.ifBlank { "部分可用" }
        else -> capability.availability.detail.ifBlank { "当前不可用" }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (editable) 1f else .52f),
        shape = RoundedCornerShape(22.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(13.dp), color = colors.surfaceRaised) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    ChatIcon(icon, null, Modifier.size(20.dp), colors.secondary)
                }
            }
            Column(Modifier.weight(1f).padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (capability.supportsPermissionControl) Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(capability.title, style = MaterialTheme.typography.titleSmall)
                        Text(status, style = MaterialTheme.typography.labelSmall,
                            color = if (editable) colors.accent else colors.tertiary)
                    }
                    Switch(
                        checked = access.enabled && editable,
                        onCheckedChange = onEnabled,
                        enabled = editable,
                    )
                }
                Text(capability.description, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("使用时", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = colors.tertiary)
                    Box {
                        Surface(
                            modifier = Modifier.clickable(enabled = editable) { menu = true },
                            shape = RoundedCornerShape(10.dp), color = colors.surfaceRaised,
                        ) {
                            Text(
                                if (access.permission == ToolPermissionMode.FULL_ACCESS) "始终允许" else "每次询问",
                                Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = colors.secondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        DropdownMenu(expanded = menu && editable, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("每次询问") }, onClick = {
                                menu = false; onPermission(ToolPermissionMode.REQUEST_APPROVAL)
                            })
                            DropdownMenuItem(text = { Text("始终允许") }, onClick = {
                                menu = false; onPermission(ToolPermissionMode.FULL_ACCESS)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextSummaryDialog(
    summary: ContextSnapshot?,
    onSource: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalChatColors.current
    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surface,
        icon = { AppGlyph("Σ") },
        title = { Text("对话摘要", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary == null) {
                    Text("这段对话还没有整理过。完整对话记录仍保存在本机。", color = colors.secondary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SummaryMetric("整理至", "第 ${summary.boundary} 条")
                        SummaryMetric("Token 估算", "${summary.inputTokensBefore} → ${summary.inputTokensAfter}")
                    }
                    Text("模型 · ${summary.model}", color = colors.secondary,
                        style = MaterialTheme.typography.labelMedium)
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceRaised,
                        border = BorderStroke(1.dp, colors.divider)) {
                        Text(summary.summary, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (summary.sourceVersions.isNotEmpty()) {
                        SectionLabel("来源消息")
                        summary.sourceVersions.keys.forEachIndexed { index, sourceId ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { onSource(sourceId) },
                                shape = RoundedCornerShape(13.dp), color = colors.muted,
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("查看来源 ${index + 1}", Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelLarge)
                                    ChatIcon(R.drawable.lucide_chevron_right, null, Modifier.size(17.dp), colors.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

@Composable
private fun SummaryMetric(label: String, value: String) {
    val colors = LocalChatColors.current
    Surface(shape = RoundedCornerShape(12.dp), color = colors.accentSoft) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(label, color = colors.secondary, style = MaterialTheme.typography.labelSmall)
            Text(value, color = colors.accent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SourceMessageDialog(source: Message, onClose: () -> Unit) {
    val colors = LocalChatColors.current
    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surface,
        title = {
            Column {
                Text("来源消息", style = MaterialTheme.typography.titleLarge)
                Text("第 ${source.sequence} 条消息", color = colors.secondary,
                    style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceRaised,
                border = BorderStroke(1.dp, colors.divider)) {
                Text(source.text, Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("返回对话摘要") } },
    )
}

@Composable
private fun RenameConversationDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalChatColors.current
    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.surface,
        icon = { AppGlyph("Aa") },
        title = { Text("修改对话标题", style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入一个容易查找的标题") },
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceRaised,
                    unfocusedContainerColor = colors.surfaceRaised,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                ),
            )
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
        confirmButton = { TextButton(onClick = onSave, enabled = text.isNotBlank()) { Text("保存") } },
    )
}
