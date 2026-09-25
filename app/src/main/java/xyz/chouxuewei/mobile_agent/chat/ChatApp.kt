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
import xyz.chouxuewei.mobile_agent.core.localizedText
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
                        workspace.error.value = userFacingMessage(it, localizedText("暂时无法预览这个文件，请稍后重试", "This file cannot be previewed right now. Please try again later."))
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
                context.startActivity(Intent.createChooser(intent, localizedText("分享 ${artifact.name}", "Share ${artifact.name}")))
            }.onFailure { workspace.error.value = localizedText("文件分享失败，请重试", "Could not share the file. Please try again.") }
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
                                workspace.error.value = userFacingMessage(failure, localizedText("无法添加这个附件，请重新选择", "Could not add this attachment. Please choose it again."))
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
        if (!granted) workspace.error.value = localizedText("需要麦克风权限才能使用语音输入", "Microphone permission is required for voice input")
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
                                    .onFailure { workspace.error.value = userFacingMessage(it, localizedText("模型切换失败，请重试", "Could not switch models. Please try again.")) }
                            }
                        },
                        onReasoningSelect = { effort ->
                            scope.launch {
                                runCatching { app.modelSettings.setSelectedReasoningEffort(effort) }
                                    .onFailure { workspace.error.value = userFacingMessage(it, localizedText("思考强度未保存，请重试", "Reasoning effort was not saved. Please try again.")) }
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
                                    workspace.error.value = localizedText("请先在设置的“语音”中配置转写服务", "Configure a transcription service under Voice settings first")
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
                                    .onFailure { workspace.error.value = userFacingMessage(it, localizedText("工具状态未保存，请重试", "Tool status was not saved. Please try again.")) }
                            }
                        },
                        onPermission = { capabilityId, mode ->
                            scope.launch {
                                runCatching { app.toolPermissions.setPermission(capabilityId, mode) }
                                    .onFailure { workspace.error.value = userFacingMessage(it, localizedText("工具权限未保存，请重试", "Tool permission was not saved. Please try again.")) }
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
                        if (sourceMessage == null) workspace.error.value = localizedText("暂时无法读取这条历史消息，请稍后重试", "This history message is temporarily unavailable. Please try again later.")
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
                title = { Text(localizedText("删除文件？", "Delete file?")) },
                text = { Text(localizedText("删除后将无法在这段对话中打开此文件。此操作不会影响其他应用中的文件。", "After deletion, this file cannot be opened from this conversation. Files in other apps are not affected.")) },
                dismissButton = {
                    TextButton(onClick = { artifactToDelete = null }) { Text(localizedText("取消", "Cancel")) }
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
                                .onFailure { workspace.error.value = userFacingMessage(it, localizedText("文件未删除，请重试", "Could not delete the file. Please try again.")) }
                        }
                    }) { Text(localizedText("删除", "Delete"), color = colors.error) }
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
                if (running) localizedText("正在回复", "Responding") else title.takeUnless { it in setOf("新对话", "New conversation") }.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = if (running) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            HeaderIcon(R.drawable.lucide_panel_left, localizedText("历史对话", "Conversation history"), onHistory)
        },
        actions = {
            HeaderIcon(R.drawable.lucide_square_pen, localizedText("新对话", "New conversation"), onNewConversation)
            Box {
                HeaderIcon(R.drawable.lucide_ellipsis, localizedText("对话菜单", "Conversation menu")) { onMenuChange(true) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { onMenuChange(false) },
                    containerColor = colors.surfaceRaised,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 10.dp,
                ) {
                    DropdownMenuItem(text = { MenuText(localizedText("整理较早对话", "Summarize earlier conversation"), localizedText("释放上下文空间，完整记录仍会保留", "Free context space while keeping the full history")) }, onClick = onCompact)
                    DropdownMenuItem(text = { MenuText(localizedText("查看对话摘要", "View conversation summary"), localizedText("查看保留的要点和来源", "View retained points and sources")) }, onClick = onSummary)
                    DropdownMenuItem(text = { MenuText(localizedText("修改对话标题", "Edit conversation title"), localizedText("让历史记录更容易查找", "Make this conversation easier to find")) }, onClick = onRename)
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
            HeaderIcon(R.drawable.lucide_panel_left, localizedText("关闭侧栏", "Close sidebar"), onClose)
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
                Text(localizedText("新建对话", "New conversation"), Modifier.padding(start = 9.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }

        DrawerSearch(search, onSearch)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pinned.isNotEmpty()) {
                item(key = "pinned_header") {
                    SectionLabel(localizedText("置顶", "Pin"), Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
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
                    SectionLabel(localizedText("最近", "Recent"), Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
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
                    Text(localizedText("没有找到相关对话", "No matching conversations"), Modifier.padding(18.dp), color = colors.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrawerQuickAction(localizedText("工具", "Tools"), R.drawable.lucide_sliders_horizontal, onTools, Modifier.weight(1f))
            DrawerQuickAction(localizedText("设置", "Settings"), R.drawable.lucide_settings, onSettings,
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
                Text(localizedText("本机存储", "On-device storage"), style = MaterialTheme.typography.bodyMedium)
            Text(localizedText("对话记录保存在此设备", "Conversations are stored on this device"), color = colors.tertiary, style = MaterialTheme.typography.labelSmall)
            }
            ChatIcon(R.drawable.lucide_ellipsis, null, Modifier.size(20.dp), colors.tertiary)
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
            title = { Text(localizedText("删除对话？", "Delete conversation?")) },
            text = { Text(localizedText("“${conversation.title}”中的消息、摘要和工具记录将被永久删除，此操作无法撤销。", "Messages, summaries, and tool records in “${conversation.title}” will be permanently deleted. This cannot be undone.")) },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(localizedText("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(conversation.id)
                }) { Text(localizedText("删除", "Delete"), color = colors.error) }
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
                    if (value.isBlank()) Text(localizedText("搜索对话", "Search conversations"), color = colors.tertiary,
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
                onLongClickLabel = localizedText("打开对话操作", "Open conversation actions"),
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
                        localizedText("已置顶", "Pinned").takeIf { conversation.pinned },
                        localizedText("正在回复", "Responding").takeIf { running },
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
                        if (conversation.pinned) localizedText("取消置顶", "Unpin") else localizedText("置顶对话", "Pin conversation"),
                        if (conversation.pinned) localizedText("恢复按最近使用时间排序", "Restore sorting by recent activity") else localizedText("固定在历史记录顶部", "Keep at the top of history"),
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
                        localizedText("删除对话", "Delete conversation"),
                        if (running) localizedText("请先停止当前回复", "Stop the current response first") else localizedText("永久删除消息和相关记录", "Permanently delete messages and related records"),
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
            Text(localizedText("正在恢复对话…", "Restoring conversation…"), color = LocalChatColors.current.secondary,
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
                Text(localizedText("回到最新", "Jump to latest"), Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
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
                localizedText("你好，我能帮你做什么？", "Hi, how can I help?"),
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
            title = toolTitles[call.toolId] ?: localizedText("工具调用", "Tool calls"),
            detail = call.displaySummary
                ?: call.error?.let { userFacingMessage(it, localizedText("工具未完成", "Tool not completed")) }
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
        Text(localizedText("来源", "Sources"), color = colors.secondary, style = MaterialTheme.typography.labelMedium)
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
    ToolCallStatus.RECEIVED -> localizedText("准备中", "Preparing")
    ToolCallStatus.WAITING_APPROVAL -> localizedText("等待确认", "Waiting for approval")
    ToolCallStatus.EXECUTING -> localizedText("执行中", "Running")
    ToolCallStatus.SUCCEEDED -> localizedText("已完成", "Completed")
    ToolCallStatus.FAILED -> localizedText("未完成", "Not completed")
    ToolCallStatus.DENIED -> localizedText("未授权", "Not authorized")
    ToolCallStatus.CANCELLED -> localizedText("已停止", "Stopped")
    ToolCallStatus.INTERRUPTED -> localizedText("已中断", "Interrupted")
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
        active && !hasReasoning -> localizedText("思考 · 进行中", "Reasoning · In progress")
        active && durationMillis == null -> localizedText("思考 · 进行中", "Reasoning · In progress")
        durationMillis != null -> localizedText("思考 · 持续了 ${formatReasoningDuration(durationMillis)}", "Reasoning · ${formatReasoningDuration(durationMillis)}")
        else -> localizedText("思考 · 已完成", "Reasoning · Completed")
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
                if (expanded) localizedText("收起思考内容", "Hide reasoning") else localizedText("展开思考内容", "Show reasoning"),
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
                    if (request.choices.isEmpty()) localizedText("允许使用“${request.capabilityTitle}”？", "Allow “${request.capabilityTitle}”?") else localizedText("选择手机操作方式", "Choose phone operation mode"),
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
                        localizedText("将要执行", "About to run"),
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
                            AppGlyph(if (choice.id == "background") localizedText("后", "BG") else localizedText("前", "FG"))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(choice.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (supported) choice.description else localizedText("后台操作需要 Android 14 或更高版本", "Background operation requires Android 14 or later"),
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
                        localizedText("允许显示悬浮窗，以便离开 Mobile Agent 后继续查看操作步骤", "Allow display over other apps to keep viewing operation steps after leaving Mobile Agent")
                    } else {
                        localizedText("开启悬浮控制条（推荐），离开 Mobile Agent 后可查看进度并立即停止；不开启也可继续，请确保设备操作通知可用", "Enable the floating controls (recommended) to view progress and stop immediately after leaving Mobile Agent. Otherwise, keep phone operation notifications enabled.")
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
                    localizedText("允许设备操作通知，以便悬浮控制条不可用时仍能查看进度和停止", "Allow phone operation notifications so progress and stop controls remain available when floating controls are unavailable"),
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondary,
                )
            }
        }
        if (request.requiresPermissionApproval) {
            Text(
                localizedText("选择“始终允许”后，今后使用“${request.capabilityTitle}”时不再询问。你可以随时在工具设置中修改。", "With Always allow, “${request.capabilityTitle}” will run without asking again. You can change this anytime in Tool settings."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.tertiary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = { onDecision(false, null, false) },
                enabled = !deciding,
                modifier = Modifier.weight(.8f),
            ) { Text(localizedText("不允许", "Deny")) }
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
                        localizedText("始终允许", "Always allow"),
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
                        deciding -> localizedText("处理中…", "Processing…")
                        request.choices.isNotEmpty() -> localizedText("开始操作", "Start operation")
                        else -> localizedText("仅本次允许", "Allow once")
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
                Text(localizedText("AI 需要你的回答", "AI needs your answer"), style = MaterialTheme.typography.titleLarge)
                Text(localizedText("回答后会继续当前任务", "The current task will continue after you answer"), style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
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
                label = { Text(if (request.options.isEmpty()) localizedText("你的回答", "Your answer") else localizedText("其他回答", "Other answer")) },
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
                    localizedText("开启“显示在其他应用上层”，下次离开 Mobile Agent 后也能直接看到并回答问题。", "Enable display over other apps so you can view and answer questions after leaving Mobile Agent."),
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
            ) { Text(localizedText("暂不回答", "Not now")) }
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
                        if (submitting) localizedText("提交中…", "Submitting…") else localizedText("提交回答", "Submit answer"),
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
    return if (minutes == 0L) localizedText("${seconds} 秒", "${seconds} sec") else localizedText("${minutes} 分 ${seconds} 秒", "${minutes} min ${seconds} sec")
}

@Composable
private fun MessageStatusLine(message: Message) {
    val colors = LocalChatColors.current
    when (message.status) {
        MessageStatus.QUEUED -> StatusPill(localizedText("将在当前回复完成后发送", "Will send after the current response"), colors.warning, colors.warningSoft)
        MessageStatus.GENERATING -> Unit
        MessageStatus.FAILED, MessageStatus.CANCELLED, MessageStatus.INTERRUPTED ->
            StatusPill(userFacingMessage(message.error, localizedText("本次回复未完成", "This response was not completed")), colors.error, colors.errorSoft)
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
            Text(localizedText("关闭", "Close"), color = colors.error, style = MaterialTheme.typography.labelSmall)
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
        Text(localizedText("选择对话", "Choose conversation"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val summary = buildString {
                if (share.attachments.isNotEmpty()) append(localizedText("${share.attachments.size} 个附件", "${share.attachments.size} attachments"))
                if (share.text.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(localizedText("含文字内容", "Includes text"))
                }
            }
            Text(summary, Modifier.padding(top = 5.dp, bottom = 16.dp), color = colors.secondary)
            ShareTargetRow(localizedText("新建对话", "New conversation"), localizedText("新建对话并保留为草稿", "Start a new conversation and keep as draft"), onNewConversation)
            if (conversations.isNotEmpty()) {
                SectionLabel(localizedText("已有对话", "Existing conversation"), Modifier.padding(top = 18.dp, bottom = 7.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(conversations.take(20), key = Conversation::id) { conversation ->
                        ShareTargetRow(
                            title = conversation.title,
                            detail = if (conversation.id == currentConversationId) localizedText("当前对话", "Current conversation") else localizedText("添加到这段对话的草稿", "Add to this conversation draft"),
                            onClick = { onConversation(conversation.id) },
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text(localizedText("取消", "Cancel"))
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
                    is VoiceInputState.Recording -> localizedText("正在录音，松开发送", "Recording — release to send")
                    is VoiceInputState.Transcribing -> localizedText("正在转写并发送…", "Transcribing and sending…")
                    is VoiceInputState.Failed -> voiceInputState.message
                    VoiceInputState.Idle -> localizedText("按住说话", "Hold to talk")
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
                                Text(localizedText("发消息，或描述你想完成的事", "Message Mobile Agent or describe what you want done"), color = colors.tertiary,
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
                    if (voiceMode) localizedText("切换到文字输入", "Switch to text input") else localizedText("切换到语音输入", "Switch to voice input"),
                    enabled && voiceInputState !is VoiceInputState.Recording,
                    { onVoiceModeChange(!voiceMode) },
                    filled = true,
                )
                ComposerIcon(R.drawable.lucide_plus, localizedText("添加附件", "Add attachment"), enabled, onAttach, filled = true)
                ComposerIcon(R.drawable.lucide_sliders_horizontal, localizedText("打开工具设置", "Open tool settings"), true, onTools)
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
                                Text(selectedModel?.name ?: localizedText("未配置模型", "No model configured"),
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
                                            Text(localizedText("当前", "Current"), color = colors.accent,
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
                                    Text(if (models.isEmpty()) localizedText("添加模型", "Add model") else localizedText("管理模型", "Manage models"),
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
                    ) { ChatIcon(R.drawable.lucide_square, localizedText("停止回复", "Stop response"), Modifier.size(17.dp)) }
                }
                val canSend = selectedModel != null &&
                    (draft.text.isNotBlank() || draft.attachments.any(AttachmentRef::isImage))
                if (running && canSend) {
                    TextButton(onClick = onSend) { Text(localizedText("发送补充", "Send follow-up")) }
                } else if (!running) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = enabled && canSend,
                        modifier = Modifier.size(44.dp).testTag("send"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.accent, contentColor = colors.onAccent,
                            disabledContainerColor = colors.muted, disabledContentColor = colors.tertiary,
                        ),
                    ) { ChatIcon(R.drawable.lucide_arrow_up, localizedText("发送消息", "Send message"), Modifier.size(18.dp)) }
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
            modifier = Modifier.size(36.dp).clickable(onClickLabel = localizedText("查看上下文额度", "View context usage"), onClick = onExpand),
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
                Text(localizedText("上下文额度", "Context usage"), color = Color(0xFFA9A9AE),
                    style = MaterialTheme.typography.labelMedium)
                Text(localizedText("$percent% 已占用", "$percent% used"), color = Color(0xFFD3D3D7),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                val inputText = when {
                    usage == null -> localizedText("尚未计算", "Not calculated")
                    usage.exact -> compactTokenCount(input)
                    else -> "≈${compactTokenCount(input)}"
                }
                Text(
                    localizedText("输入 $inputText · 输出预留 ${compactTokenCount(outputReserve)}", "Input $inputText · ${compactTokenCount(outputReserve)} reserved for output"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    localizedText("合计 ${compactTokenCount(occupied)} / ${compactTokenCount(window)}", "Total ${compactTokenCount(occupied)} / ${compactTokenCount(window)}") +
                        localizedText(" · 剩余 ${compactTokenCount((window - occupied).coerceAtLeast(0))}", " · ${compactTokenCount((window - occupied).coerceAtLeast(0))} remaining"),
                    color = Color(0xFFD3D3D7),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                if (usage?.compacted == true) {
                    Text(localizedText("较早内容已自动压缩", "Earlier content was compressed automatically"), color = Color(0xFFAEBBFF),
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
        REASONING_EFFORT_OFF -> localizedText("关闭", "Close")
        null -> localizedText("思考", "Reasoning")
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
                    localizedText("选择思考强度", "Choose reasoning effort"),
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
                        Text(localizedText("关闭", "Close"), style = MaterialTheme.typography.bodyMedium)
                        Text(localizedText("不启用深度思考", "Deep reasoning off"), color = colors.tertiary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                onClick = { onSelect(REASONING_EFFORT_OFF); menu = false },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text(localizedText("不指定", "Not specified"), style = MaterialTheme.typography.bodyMedium)
                        Text(localizedText("由模型服务决定", "Let the model service decide"), color = colors.tertiary,
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
                    ChatIcon(R.drawable.lucide_x, localizedText("关闭", "Close"))
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
        Text(localizedText("工具", "Tools"), style = MaterialTheme.typography.headlineSmall)
        Text(localizedText("管理 AI 可以在对话中使用的工具。关闭后，AI 将无法调用。", "Manage tools AI can use in conversations. Disabled tools cannot be called."),
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
                localizedText("选择“每次询问”后，工具会在使用前等待你确认。不可用的工具需要先完成页面显示的条件。", "With Ask every time, the tool waits for your approval before use. Unavailable tools require the conditions shown on this page."),
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
        ToolAvailabilityState.AVAILABLE -> localizedText("可用", "Available")
        ToolAvailabilityState.DEGRADED -> capability.availability.detail.ifBlank { localizedText("部分可用", "Partially available") }
        else -> capability.availability.detail.ifBlank { localizedText("当前不可用", "Currently unavailable") }
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
                    Text(localizedText("使用时", "While using"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = colors.tertiary)
                    Box {
                        Surface(
                            modifier = Modifier.clickable(enabled = editable) { menu = true },
                            shape = RoundedCornerShape(10.dp), color = colors.surfaceRaised,
                        ) {
                            Text(
                                if (access.permission == ToolPermissionMode.FULL_ACCESS) localizedText("始终允许", "Always allow") else localizedText("每次询问", "Ask every time"),
                                Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = colors.secondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        DropdownMenu(expanded = menu && editable, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(localizedText("每次询问", "Ask every time")) }, onClick = {
                                menu = false; onPermission(ToolPermissionMode.REQUEST_APPROVAL)
                            })
                            DropdownMenuItem(text = { Text(localizedText("始终允许", "Always allow")) }, onClick = {
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
        title = { Text(localizedText("对话摘要", "Conversation summary"), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summary == null) {
                    Text(localizedText("这段对话还没有整理过。完整对话记录仍保存在本机。", "This conversation has not been summarized yet. The full history remains on this device."), color = colors.secondary)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SummaryMetric(localizedText("整理至", "Summarized through"), localizedText("第 ${summary.boundary} 条", "Message ${summary.boundary}"))
                        SummaryMetric(localizedText("Token 估算", "Estimated tokens"), "${summary.inputTokensBefore} → ${summary.inputTokensAfter}")
                    }
                    Text(localizedText("模型 · ${summary.model}", "Model · ${summary.model}"), color = colors.secondary,
                        style = MaterialTheme.typography.labelMedium)
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceRaised,
                        border = BorderStroke(1.dp, colors.divider)) {
                        Text(summary.summary, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (summary.sourceVersions.isNotEmpty()) {
                        SectionLabel(localizedText("来源消息", "Source message"))
                        summary.sourceVersions.keys.forEachIndexed { index, sourceId ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { onSource(sourceId) },
                                shape = RoundedCornerShape(13.dp), color = colors.muted,
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(localizedText("查看来源 ${index + 1}", "View source ${index + 1}"), Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelLarge)
                                    ChatIcon(R.drawable.lucide_chevron_right, null, Modifier.size(17.dp), colors.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(localizedText("关闭", "Close")) } },
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
                Text(localizedText("来源消息", "Source message"), style = MaterialTheme.typography.titleLarge)
                Text(localizedText("第 ${source.sequence} 条消息", "Message ${source.sequence}"), color = colors.secondary,
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
        confirmButton = { TextButton(onClick = onClose) { Text(localizedText("返回对话摘要", "Back to conversation summary")) } },
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
        title = { Text(localizedText("修改对话标题", "Edit conversation title"), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(localizedText("输入一个容易查找的标题", "Enter a title that is easy to find")) },
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
        dismissButton = { TextButton(onClick = onClose) { Text(localizedText("取消", "Cancel")) } },
        confirmButton = { TextButton(onClick = onSave, enabled = text.isNotBlank()) { Text(localizedText("保存", "Save")) } },
    )
}
