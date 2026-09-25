package xyz.chouxuewei.mobile_agent.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.BuildConfig
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.core.ContextPolicy
import xyz.chouxuewei.mobile_agent.core.DEFAULT_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.MAX_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.MIN_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.ThemePreference
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.data.DEFAULT_REASONING_EFFORTS
import xyz.chouxuewei.mobile_agent.data.MAX_MODEL_PROFILES
import xyz.chouxuewei.mobile_agent.data.MAX_PERSONALIZATION_CHARS
import xyz.chouxuewei.mobile_agent.data.ModelProfile
import xyz.chouxuewei.mobile_agent.data.ModelSettings
import xyz.chouxuewei.mobile_agent.data.ModelUsageSummary
import xyz.chouxuewei.mobile_agent.data.REASONING_EFFORT_OFF
import xyz.chouxuewei.mobile_agent.data.SpeechApiFormat
import xyz.chouxuewei.mobile_agent.data.SpeechSettings
import xyz.chouxuewei.mobile_agent.device.RootAccessState
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.ui.theme.LocalChatColors

private data class SettingsTab(val id: String, val label: String, val icon: Int)
private data class SettingsNotice(val message: String, val success: Boolean)

private const val ABOUT_PLACEHOLDER = "Codex、黑白辩思"
private const val OPEN_SOURCE_URL = "https://github.com/"

private data class AcknowledgedLibrary(
    val name: String,
    val version: String,
    val license: String,
    val sourceUrl: String,
)

private val acknowledgedLibraries = listOf(
    AcknowledgedLibrary(
        "OkHttp",
        "4.12.0",
        "Apache-2.0",
        "https://github.com/square/okhttp/tree/parent-4.12.0",
    ),
    AcknowledgedLibrary(
        "kotlinx.serialization JSON",
        "1.7.3",
        "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.serialization/tree/v1.7.3",
    ),
    AcknowledgedLibrary(
        "libsu",
        "6.0.0",
        "Apache-2.0",
        "https://github.com/topjohnwu/libsu/tree/v6.0.0",
    ),
    AcknowledgedLibrary(
        "scrcpy",
        "4.1",
        "Apache-2.0",
        "https://github.com/Genymobile/scrcpy/tree/v4.1",
    ),
    AcknowledgedLibrary(
        "Lucide",
        "0.468.0",
        "ISC / Feather MIT",
        "https://github.com/lucide-icons/lucide/tree/0.468.0",
    ),
    AcknowledgedLibrary(
        "Coil",
        "2.7.0",
        "Apache-2.0",
        "https://github.com/coil-kt/coil/tree/2.7.0",
    ),
    AcknowledgedLibrary(
        "jsoup",
        "1.23.2",
        "MIT",
        "https://github.com/jhy/jsoup/tree/jsoup-1.23.2",
    ),
    AcknowledgedLibrary(
        "AndroidSVG",
        "1.4",
        "Apache-2.0",
        "https://github.com/BigBadaboom/androidsvg/tree/v1.4",
    ),
    AcknowledgedLibrary(
        "Square gifencoder",
        "0.10.1",
        "Apache-2.0",
        "https://github.com/square/gifencoder/tree/gifencoder-0.10.1",
    ),
)

private data class ThemeOption(
    val value: ThemePreference,
    val label: String,
    val caption: String,
    val icon: Int,
)

@Composable
fun ChatSettings(
    app: PrototypeApplication,
    preference: ThemePreference,
    settings: ModelSettings,
    initialPage: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable {
        mutableStateOf(
            initialPage.takeIf {
                it in setOf("general", "personalization", "model", "voice", "data", "about")
            } ?: "general",
        )
    }
    var feedback by remember { mutableStateOf<SettingsNotice?>(null) }
    var saving by remember { mutableStateOf(false) }
    var rootAccess by remember { mutableStateOf<RootAccessState?>(null) }
    var rootChanging by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var personalizationSaving by remember { mutableStateOf(false) }
    var speechSaving by remember { mutableStateOf(false) }
    var speechTesting by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var microphoneGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsGranted = it
        }
    val overlayPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            overlayGranted = Settings.canDrawOverlays(context)
            if (overlayGranted) scope.launch { app.appearance.setPersistentOverlay(true) }
        }
    val microphonePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            microphoneGranted = it
            feedback = SettingsNotice(
                if (it) "麦克风权限已允许" else "未允许麦克风权限，语音输入暂不可用",
                it,
            )
        }
    val activeRuns by app.chatRuntime.active.collectAsState()
    val detailedLogging by app.appearance.detailedLogging.collectAsState(initial = false)
    val persistentOverlay by app.appearance.persistentOverlay.collectAsState(initial = false)
    val maxSteps by app.agentExecutionSettings.maxSteps.collectAsState(initial = DEFAULT_SINGLE_RUN_MAX_STEPS)
    val personalizedInstructions by app.personalization.instructions.collectAsState(initial = "")
    val modelUsage by app.modelUsage.usage.collectAsState(initial = emptyList())
    val speechSettings by app.speechSettings.settings.collectAsState(initial = SpeechSettings())
    val tabs = listOf(
        SettingsTab("general", "通用", R.drawable.lucide_settings),
        SettingsTab("personalization", "个性化", R.drawable.lucide_sparkles),
        SettingsTab("voice", "语音", R.drawable.lucide_mic),
        SettingsTab("model", "模型服务", R.drawable.lucide_bot),
        SettingsTab("data", "数据管理", R.drawable.lucide_database),
        SettingsTab("about", "关于", R.drawable.lucide_info),
    )
    val tabsScroll = rememberScrollState()

    LaunchedEffect(page) {
        if (page == "general") {
            rootAccess = app.deviceGateway.rootAccessState()
            overlayGranted = Settings.canDrawOverlays(context)
            notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (page == "voice") {
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    LaunchedEffect(page, tabsScroll.maxValue) {
        val index = tabs.indexOfFirst { it.id == page }.coerceAtLeast(0)
        val target = if (tabs.lastIndex <= 0) 0 else tabsScroll.maxValue * index / tabs.lastIndex
        tabsScroll.animateScrollTo(target)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                ChatIcon(R.drawable.lucide_arrow_left, "返回对话", Modifier.size(22.dp))
            }
            Text(
                "设置", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(tabsScroll)
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                SettingsTabButton(tab, selected = page == tab.id) {
                    page = tab.id
                    feedback = null
                }
            }
        }

        feedback?.let { SettingsFeedback(it) }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (page) {
                "model" -> ModelSettingsPage(
                    settings = settings,
                    saving = saving,
                    canDelete = activeRuns.isEmpty(),
                    onSave = { profileId, name, url, model, key, window, output, reasoningField, reasoningEfforts, saved ->
                        scope.launch {
                            saving = true
                            feedback = null
                            try {
                                val policy = ContextPolicy(
                                    window.toIntOrNull() ?: 0,
                                    output.toIntOrNull() ?: 0
                                )
                                policy.validate()
                                val savedId = app.modelSettings.saveModel(
                                    profileId = profileId,
                                    name = name,
                                    baseUrl = url,
                                    model = model,
                                    newApiKey = key.takeIf { it.isNotBlank() },
                                    contextPolicy = policy,
                                    reasoningEffortField = reasoningField,
                                    reasoningEfforts = reasoningEfforts,
                                )
                                saved(savedId)
                                feedback = SettingsNotice("模型配置已保存", true)
                            } catch (e: Exception) {
                                feedback = SettingsNotice(
                                    userFacingMessage(e, "模型配置未保存，请重试"),
                                    false
                                )
                            } finally {
                                saving = false
                            }
                        }
                    },
                    onSelect = { id ->
                        scope.launch {
                            runCatching { app.modelSettings.setSelectedModel(id) }
                                .onSuccess { feedback = SettingsNotice("已切换当前模型", true) }
                                .onFailure {
                                    feedback = SettingsNotice(
                                        userFacingMessage(it, "模型切换失败，请重试"),
                                        false
                                    )
                                }
                        }
                    },
                    onDelete = { id, deleted ->
                        scope.launch {
                            saving = true
                            feedback = null
                            try {
                                require(app.chatRuntime.active.value.isEmpty()) { "正在生成回复，请结束后再删除模型" }
                                app.modelSettings.deleteModel(id)
                                deleted()
                                feedback = SettingsNotice("模型配置已删除", true)
                            } catch (e: Exception) {
                                feedback = SettingsNotice(
                                    userFacingMessage(e, "模型配置未删除，请重试"),
                                    false
                                )
                            } finally {
                                saving = false
                            }
                        }
                    },
                )

                "personalization" -> PersonalizationSettings(
                    value = personalizedInstructions,
                    saving = personalizationSaving,
                    onSave = { value ->
                        scope.launch {
                            personalizationSaving = true
                            feedback = null
                            try {
                                app.personalization.setInstructions(value)
                                feedback = SettingsNotice(
                                    if (value.isBlank()) "个性化设置已清空" else "个性化设置已保存",
                                    true,
                                )
                            } catch (failure: Exception) {
                                feedback = SettingsNotice(
                                    userFacingMessage(failure, "个性化设置未保存，请重试"),
                                    false,
                                )
                            } finally {
                                personalizationSaving = false
                            }
                        }
                    },
                )

                "voice" -> SpeechSettingsPage(
                    settings = speechSettings,
                    microphoneGranted = microphoneGranted,
                    saving = speechSaving,
                    testing = speechTesting,
                    onPermission = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onSaveOpenAi = { endpoint, model, key, saved ->
                        scope.launch {
                            speechSaving = true
                            feedback = null
                            try {
                                app.speechSettings.saveOpenAi(
                                    endpoint,
                                    model,
                                    key.takeIf(String::isNotBlank)
                                )
                                saved()
                                feedback = SettingsNotice("OpenAI 兼容配置已保存", true)
                            } catch (failure: Exception) {
                                feedback = SettingsNotice(
                                    userFacingMessage(failure, "语音配置未保存，请重试"),
                                    false,
                                )
                            } finally {
                                speechSaving = false
                            }
                        }
                    },
                    onSaveIflytek = { endpoint, appId, key, secret, language, accent, saved ->
                        scope.launch {
                            speechSaving = true
                            feedback = null
                            try {
                                app.speechSettings.saveIflytek(
                                    endpoint,
                                    appId,
                                    key.takeIf(String::isNotBlank),
                                    secret.takeIf(String::isNotBlank),
                                    language,
                                    accent,
                                )
                                saved()
                                feedback = SettingsNotice("科大讯飞配置已保存", true)
                            } catch (failure: Exception) {
                                feedback = SettingsNotice(
                                    userFacingMessage(failure, "科大讯飞配置未保存，请重试"),
                                    false,
                                )
                            } finally {
                                speechSaving = false
                            }
                        }
                    },
                    onSelect = { format ->
                        scope.launch {
                            runCatching { app.speechSettings.setSelectedFormat(format) }
                                .onSuccess { feedback = SettingsNotice("已切换当前语音服务", true) }
                                .onFailure {
                                    feedback = SettingsNotice(
                                        userFacingMessage(it, "语音服务未切换"),
                                        false
                                    )
                                }
                        }
                    },
                    onTest = { format ->
                        scope.launch {
                            speechTesting = true
                            feedback = null
                            app.voiceInput.verifyConfiguration(format)
                                .onSuccess { feedback = SettingsNotice("语音接口连接正常", true) }
                                .onFailure {
                                    feedback = SettingsNotice(
                                        userFacingMessage(it, "语音接口测试失败，请检查配置"),
                                        false,
                                    )
                                }
                            speechTesting = false
                        }
                    },
                )

                "data" -> DataSettings(
                    models = settings.models,
                    usage = modelUsage,
                    cleaning = cleaning,
                    canClean = activeRuns.isEmpty(),
                    onClean = {
                        scope.launch {
                            cleaning = true
                            feedback = null
                            try {
                                val result = app.cleanupStorage()
                                feedback = SettingsNotice(
                                    if (
                                        result.filesDeleted == 0 && result.permissionsReleased == 0
                                    ) {
                                        "没有需要清理的内容"
                                    } else {
                                        buildString {
                                            append("已释放 ${formatStorageBytes(result.bytesFreed)}，共清理 ${result.filesDeleted} 个文件")
                                            if (result.permissionsReleased > 0) {
                                                append("，移除 ${result.permissionsReleased} 项无用文件授权")
                                            }
                                        }
                                    }, true)
                            } catch (failure: Exception) {
                                feedback = SettingsNotice(
                                    userFacingMessage(failure, "清理未完成，请重试"),
                                    false
                                )
                            } finally {
                                cleaning = false
                            }
                        }
                    },
                )

                "about" -> AboutSettings(
                    versionName = BuildConfig.VERSION_NAME,
                    context = context,
                    onError = {
                        feedback = it
                    }
                )

                else -> GeneralSettings(
                    preference = preference,
                    detailedLogging = detailedLogging,
                    maxSteps = maxSteps,
                    rootAccess = rootAccess,
                    rootChanging = rootChanging,
                    overlayGranted = overlayGranted,
                    persistentOverlay = persistentOverlay,
                    notificationsGranted = notificationsGranted,
                    onSelect = { value ->
                        scope.launch {
                            runCatching { app.appearance.setTheme(value) }
                                .onFailure { feedback = SettingsNotice("主题未保存，请重试", false) }
                        }
                    },
                    onDetailedLogging = { enabled ->
                        scope.launch {
                            runCatching { app.appearance.setDetailedLogging(enabled) }
                                .onFailure {
                                    feedback = SettingsNotice("日志设置未保存，请重试", false)
                                }
                        }
                    },
                    onMaxSteps = { value ->
                        scope.launch {
                            runCatching { app.agentExecutionSettings.setMaxSteps(value) }
                                .onFailure {
                                    feedback = SettingsNotice(
                                        userFacingMessage(it, "单轮最大步骤未保存，请重试"),
                                        false,
                                    )
                                }
                        }
                    },
                    onRootEnabled = { enabled ->
                        scope.launch {
                            rootChanging = true
                            feedback = null
                            try {
                                rootAccess = app.deviceGateway.setRootEnabled(enabled)
                                if (enabled && rootAccess?.enabled != true) {
                                    feedback = SettingsNotice(
                                        rootAccess?.detail ?: "未获得 Root 权限",
                                        false
                                    )
                                }
                            } finally {
                                rootChanging = false
                            }
                        }
                    },
                    onOverlaySettings = {
                        overlayPermission.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    },
                    onPersistentOverlay = { enabled ->
                        scope.launch {
                            runCatching { app.appearance.setPersistentOverlay(enabled) }
                                .onFailure {
                                    feedback = SettingsNotice("悬浮助手设置未保存，请重试", false)
                                }
                        }
                    },
                    onNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PersonalizationSettings(
    value: String,
    saving: Boolean,
    onSave: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var draft by rememberSaveable { mutableStateOf(value) }
    LaunchedEffect(value) { draft = value }
    val normalized = draft.trim()
    val changed = normalized != value

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("个性化提示词", style = MaterialTheme.typography.titleSmall)
        Text(
            "写下希望 AI 长期记住的喜好、习惯和回复方式。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
        SettingsCard {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= MAX_PERSONALIZATION_CHARS) draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("personalization_instructions"),
                label = { Text("偏好与习惯") },
                placeholder = { Text("例如：我不吃香菜；推荐商品时优先考虑性价比；点外卖优先用淘宝；") },
                minLines = 7,
                maxLines = 14,
                shape = RoundedCornerShape(15.dp),
                colors = modelFieldColors(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = draft.isNotEmpty() && !saving, onClick = { draft = "" }) {
                    Text("清空输入")
                }
                Text(
                    "${draft.length} / $MAX_PERSONALIZATION_CHARS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
            }
        }
        Button(
            enabled = changed && !saving,
            onClick = { onSave(draft) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent
            ),
        ) {
            Text(if (saving) "正在保存…" else "保存个性化设置")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SpeechSettingsPage(
    settings: SpeechSettings,
    microphoneGranted: Boolean,
    saving: Boolean,
    testing: Boolean,
    onPermission: () -> Unit,
    onSaveOpenAi: (String, String, String, () -> Unit) -> Unit,
    onSaveIflytek: (String, String, String, String, String, String, () -> Unit) -> Unit,
    onSelect: (SpeechApiFormat) -> Unit,
    onTest: (SpeechApiFormat) -> Unit,
) {
    val colors = LocalChatColors.current
    var editingName by rememberSaveable { mutableStateOf(settings.selectedFormat.name) }
    val editing =
        SpeechApiFormat.entries.firstOrNull { it.name == editingName } ?: settings.selectedFormat
    var openAiEndpoint by rememberSaveable { mutableStateOf(settings.openAi.endpointUrl) }
    var openAiModel by rememberSaveable { mutableStateOf(settings.openAi.model) }
    var openAiKey by remember { mutableStateOf("") }
    var iflytekEndpoint by rememberSaveable { mutableStateOf(settings.iflytek.endpointUrl) }
    var iflytekAppId by rememberSaveable { mutableStateOf(settings.iflytek.appId) }
    var iflytekKey by remember { mutableStateOf("") }
    var iflytekSecret by remember { mutableStateOf("") }
    var iflytekLanguage by rememberSaveable { mutableStateOf(settings.iflytek.language) }
    var iflytekAccent by rememberSaveable { mutableStateOf(settings.iflytek.accent) }
    LaunchedEffect(settings.openAi.endpointUrl, settings.openAi.model) {
        openAiEndpoint = settings.openAi.endpointUrl
        openAiModel = settings.openAi.model
    }
    LaunchedEffect(
        settings.iflytek.endpointUrl,
        settings.iflytek.appId,
        settings.iflytek.language,
        settings.iflytek.accent,
    ) {
        iflytekEndpoint = settings.iflytek.endpointUrl
        iflytekAppId = settings.iflytek.appId
        iflytekLanguage = settings.iflytek.language
        iflytekAccent = settings.iflytek.accent
    }
    val changed = when (editing) {
        SpeechApiFormat.OPENAI_COMPATIBLE -> openAiEndpoint.trim() != settings.openAi.endpointUrl ||
                openAiModel.trim() != settings.openAi.model || openAiKey.isNotBlank()

        SpeechApiFormat.IFLYTEK_IAT -> iflytekEndpoint.trim() != settings.iflytek.endpointUrl ||
                iflytekAppId.trim() != settings.iflytek.appId || iflytekKey.isNotBlank() ||
                iflytekSecret.isNotBlank() || iflytekLanguage.trim() != settings.iflytek.language ||
                iflytekAccent.trim() != settings.iflytek.accent
    }
    val configured = when (editing) {
        SpeechApiFormat.OPENAI_COMPATIBLE -> settings.openAi.configured
        SpeechApiFormat.IFLYTEK_IAT -> settings.iflytek.configured
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("语音服务", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                "选择并配置转写协议",
                color = colors.tertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpeechProviderCard(
                title = "OpenAI 兼容",
                detail = "文件转写",
                selected = editing == SpeechApiFormat.OPENAI_COMPATIBLE,
                current = settings.selectedFormat == SpeechApiFormat.OPENAI_COMPATIBLE,
                configured = settings.openAi.configured,
                onClick = { editingName = SpeechApiFormat.OPENAI_COMPATIBLE.name },
            )
            SpeechProviderCard(
                title = "科大讯飞",
                detail = "语音听写流式版",
                selected = editing == SpeechApiFormat.IFLYTEK_IAT,
                current = settings.selectedFormat == SpeechApiFormat.IFLYTEK_IAT,
                configured = settings.iflytek.configured,
                onClick = { editingName = SpeechApiFormat.IFLYTEK_IAT.name },
            )
        }

        Text("服务连接", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            when (editing) {
                SpeechApiFormat.OPENAI_COMPATIBLE -> {
                    ModelField(
                        openAiEndpoint, { openAiEndpoint = it }, "转写接口完整地址",
                        Modifier.testTag("speech_openai_endpoint"), KeyboardType.Uri
                    )
                    ModelField(
                        openAiModel, { openAiModel = it }, "语音模型 ID",
                        Modifier.testTag("speech_openai_model")
                    )
                    SecretField(
                        openAiKey,
                        { openAiKey = it },
                        if (settings.openAi.hasApiKey) "API 密钥（留空则不修改）" else "API 密钥",
                        "speech_openai_key",
                    )
                    Text(
                        "multipart 上传 file 与 model，读取返回 JSON 的 text 字段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                    )
                }

                SpeechApiFormat.IFLYTEK_IAT -> {
                    ModelField(
                        iflytekEndpoint, { iflytekEndpoint = it }, "WebSocket 接口地址",
                        Modifier.testTag("speech_iflytek_endpoint"), KeyboardType.Uri
                    )
                    ModelField(
                        iflytekAppId, { iflytekAppId = it }, "AppID",
                        Modifier.testTag("speech_iflytek_app_id")
                    )
                    SecretField(
                        iflytekKey,
                        { iflytekKey = it },
                        if (settings.iflytek.hasApiKey) "APIKey（留空则不修改）" else "APIKey",
                        "speech_iflytek_key",
                    )
                    SecretField(
                        iflytekSecret,
                        { iflytekSecret = it },
                        if (settings.iflytek.hasApiSecret) "APISecret（留空则不修改）" else "APISecret",
                        "speech_iflytek_secret",
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ModelField(
                            iflytekLanguage, { iflytekLanguage = it }, "语种",
                            Modifier
                                .weight(1f)
                                .testTag("speech_iflytek_language")
                        )
                        ModelField(
                            iflytekAccent, { iflytekAccent = it }, "方言",
                            Modifier
                                .weight(1f)
                                .testTag("speech_iflytek_accent")
                        )
                    }
                    Text(
                        "默认 zh_cn / mandarin；使用 16 kHz 单声道 PCM，单次最长 60 秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                    )
                }
            }
        }
        Button(
            enabled = changed && !saving && !testing,
            onClick = {
                when (editing) {
                    SpeechApiFormat.OPENAI_COMPATIBLE -> onSaveOpenAi(
                        openAiEndpoint,
                        openAiModel,
                        openAiKey,
                    ) { openAiKey = "" }

                    SpeechApiFormat.IFLYTEK_IAT -> onSaveIflytek(
                        iflytekEndpoint,
                        iflytekAppId,
                        iflytekKey,
                        iflytekSecret,
                        iflytekLanguage,
                        iflytekAccent,
                    ) {
                        iflytekKey = ""
                        iflytekSecret = ""
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent
            ),
        ) {
            Text(if (saving) "正在保存…" else "保存当前配置")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = configured && !changed && !saving && !testing,
                onClick = { onTest(editing) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text(if (testing) "正在测试…" else "测试连接") }
            TextButton(
                enabled = configured && !changed && settings.selectedFormat != editing && !saving && !testing,
                onClick = { onSelect(editing) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text(if (settings.selectedFormat == editing) "当前使用" else "设为当前") }
        }

        Text("权限", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text("麦克风", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (microphoneGranted) "已允许，可在聊天页或贴边按钮录音" else "语音输入需要录音权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                    )
                }
                if (microphoneGranted) {
                    Text(
                        "已允许",
                        color = colors.success,
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    TextButton(onClick = onPermission) { Text("去允许") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SpeechProviderCard(
    title: String,
    detail: String,
    selected: Boolean,
    current: Boolean,
    configured: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier
            .width(164.dp)
            .height(76.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.accentSoft else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.outline else colors.divider),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp, vertical = 10.dp)
        ) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = if (current) 44.dp else 0.dp)
            ) {
                Text(
                    title, maxLines = 1, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    if (configured) detail else "未配置",
                    maxLines = 1,
                    color = colors.secondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (current) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd),
                    shape = RoundedCornerShape(8.dp),
                    color = colors.accent,
                    contentColor = colors.onAccent,
                ) {
                    Text(
                        "当前", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        colors = modelFieldColors(),
    )
}

@Composable
private fun SettingsTabButton(tab: SettingsTab, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) colors.muted else Color.Transparent,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatIcon(
                tab.icon,
                null,
                Modifier.size(19.dp),
                if (selected) colors.text else colors.secondary
            )
            Text(
                tab.label,
                Modifier.padding(start = 8.dp),
                color = if (selected) colors.text else colors.secondary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SettingsFeedback(notice: SettingsNotice) {
    val colors = LocalChatColors.current
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (notice.success) colors.successSoft else colors.errorSoft,
    ) {
        Text(
            notice.message,
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (notice.success) colors.success else colors.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GeneralSettings(
    preference: ThemePreference,
    detailedLogging: Boolean,
    maxSteps: Int,
    rootAccess: RootAccessState?,
    rootChanging: Boolean,
    overlayGranted: Boolean,
    persistentOverlay: Boolean,
    notificationsGranted: Boolean,
    onSelect: (ThemePreference) -> Unit,
    onDetailedLogging: (Boolean) -> Unit,
    onMaxSteps: (Int) -> Unit,
    onRootEnabled: (Boolean) -> Unit,
    onOverlaySettings: () -> Unit,
    onPersistentOverlay: (Boolean) -> Unit,
    onNotificationPermission: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val themes = listOf(
        ThemeOption(ThemePreference.PAPER, "浅色", "清爽留白", R.drawable.lucide_sun),
        ThemeOption(ThemePreference.GRAPHITE, "深色", "低亮专注", R.drawable.lucide_moon),
        ThemeOption(ThemePreference.SYSTEM, "跟随系统", "自动切换", R.drawable.lucide_monitor),
        ThemeOption(ThemePreference.WARM, "暖色", "柔和阅读", R.drawable.lucide_palette),
    )
    var rootOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    var maxStepsFieldBounds by remember { mutableStateOf<Rect?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rootOffsetInWindow = it.positionInWindow() }
            .pointerInput(maxStepsFieldBounds, rootOffsetInWindow) {
                // 只观察按下位置而不消费事件，让其它开关和卡片仍按原方式响应点击。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val windowPosition = down.position + rootOffsetInWindow
                    if (maxStepsFieldBounds?.contains(windowPosition) == false) {
                        focusManager.clearFocus()
                    }
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("主题", style = MaterialTheme.typography.titleSmall)
        themes.chunked(2).forEach { rowThemes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowThemes.forEach { option ->
                    ThemeOptionCard(
                        option = option,
                        selected = preference == option.value,
                        onClick = { onSelect(option.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SettingsValueRow("语言", "简体中文")
        SettingsValueRow("字体大小", "跟随系统")
        SingleRunMaxStepsRow(
            value = maxSteps,
            onChange = onMaxSteps,
            onFieldBoundsChanged = { maxStepsFieldBounds = it },
        )
        DetailedLoggingRow(detailedLogging, onDetailedLogging)
        BackgroundInteractionRow(
            overlayGranted = overlayGranted,
            persistentOverlay = persistentOverlay,
            notificationsGranted = notificationsGranted,
            onOverlaySettings = onOverlaySettings,
            onPersistentOverlay = onPersistentOverlay,
            onNotificationPermission = onNotificationPermission,
        )
        RootAccessRow(
            state = rootAccess,
            changing = rootChanging,
            onEnabled = onRootEnabled,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SingleRunMaxStepsRow(
    value: Int,
    onChange: (Int) -> Unit,
    onFieldBoundsChanged: (Rect) -> Unit,
) {
    val colors = LocalChatColors.current
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        // 持久化回流时不覆盖用户正在输入的下一位数字，失焦后再与真实设置同步。
        if (!focused) draft = value.toString()
    }
    val parsed = draft.toIntOrNull()
    val valid = parsed != null && parsed in MIN_SINGLE_RUN_MAX_STEPS..MAX_SINGLE_RUN_MAX_STEPS

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text("任务最大步骤", style = MaterialTheme.typography.bodyMedium)
            Text(
                "执行任务时，AI可执行的最大步数",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (draft.isNotEmpty() && !valid) colors.error else colors.secondary,
            )
        }
        Surface(
            modifier = Modifier
                .size(width = 52.dp, height = 48.dp)
                .onGloballyPositioned { onFieldBoundsChanged(it.boundsInWindow()) },
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceRaised,
            border = BorderStroke(
                1.dp,
                when {
                    draft.isNotEmpty() && !valid -> colors.error
                    focused -> colors.accent
                    else -> colors.outline
                },
            ),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { input ->
                    draft = input.filter(Char::isDigit).take(3)
                    draft.toIntOrNull()?.takeIf {
                        it in MIN_SINGLE_RUN_MAX_STEPS..MAX_SINGLE_RUN_MAX_STEPS && it != value
                    }?.let(onChange)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { focusState ->
                        focused = focusState.isFocused
                        if (!focusState.isFocused && !valid) draft = value.toString()
                    }
                    .testTag("single_run_max_steps"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                decorationBox = { innerField ->
                    // 与 Switch 使用相同的 52×48dp 占位，四周仅留少量余量以容纳三位数。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        innerField()
                    }
                },
            )
        }
    }
}

@Composable
private fun BackgroundInteractionRow(
    overlayGranted: Boolean,
    persistentOverlay: Boolean,
    notificationsGranted: Boolean,
    onOverlaySettings: () -> Unit,
    onPersistentOverlay: (Boolean) -> Unit,
    onNotificationPermission: () -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text("常驻悬浮助手", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        overlayGranted -> "空闲时保留悬浮按钮，并显示低优先级常驻通知。"
                        persistentOverlay -> "悬浮权限已关闭，当前只保留常驻通知；可在这里关闭。"
                        else -> "需要先允许显示在其他应用上层。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            Switch(
                checked = persistentOverlay,
                onCheckedChange = onPersistentOverlay,
                enabled = overlayGranted || persistentOverlay,
            )
        }
        Text(
            "任务执行、授权和 AI 提问仍会临时启用后台控制；常驻开关只决定空闲时是否保留拉杆。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                modifier = Modifier.clickable(onClick = onOverlaySettings),
                shape = RoundedCornerShape(12.dp),
                color = if (overlayGranted) colors.successSoft else colors.accentSoft,
            ) {
                Text(
                    if (overlayGranted) "悬浮窗已开启" else "开启悬浮窗",
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = if (overlayGranted) colors.success else colors.accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (Build.VERSION.SDK_INT >= 33) Surface(
                modifier = Modifier.clickable(
                    enabled = !notificationsGranted,
                    onClick = onNotificationPermission
                ),
                shape = RoundedCornerShape(12.dp),
                color = if (notificationsGranted) colors.successSoft else colors.surfaceRaised,
            ) {
                Text(
                    if (notificationsGranted) "通知已开启" else "允许任务通知",
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = if (notificationsGranted) colors.success else colors.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun DetailedLoggingRow(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    val colors = LocalChatColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text("详细日志", style = MaterialTheme.typography.bodyMedium)
            Text(
                "输出运行、模型、工具和设备耗时到 Logcat，用于调试",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
}

@Composable
private fun RootAccessRow(
    state: RootAccessState?,
    changing: Boolean,
    onEnabled: (Boolean) -> Unit,
) {
    val colors = LocalChatColors.current
    val available = state?.available == true
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text("Root 权限", style = MaterialTheme.typography.bodyMedium)
            Text(
                state?.detail ?: "正在检查 Root 状态",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }
        Switch(
            checked = state?.enabled == true,
            onCheckedChange = onEnabled,
            enabled = available && !changing,
        )
    }
}

@Composable
private fun ThemeOptionCard(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Surface(
        modifier = modifier
            .heightIn(min = 104.dp)
            .testTag("theme_${option.value.name}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.surfaceRaised else colors.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) colors.outline else colors.divider
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChatIcon(
                option.icon,
                null,
                Modifier.size(23.dp),
                if (selected) colors.accent else colors.text
            )
            Text(
                option.label,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                option.caption,
                color = colors.tertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String) {
    val colors = LocalChatColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceRaised) {
            Text(
                value, Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                color = colors.secondary, style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ModelSettingsPage(
    settings: ModelSettings,
    saving: Boolean,
    canDelete: Boolean,
    onSave: (
        String?, String, String, String, String, String, String, String, List<String>, (String) -> Unit,
    ) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String, () -> Unit) -> Unit,
) {
    val colors = LocalChatColors.current
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = when (editingId) {
        NEW_MODEL_EDITOR -> null
        null -> settings.selectedModel
        else -> settings.models.firstOrNull { it.id == editingId }
    }
    val editorKey = editingProfile?.id ?: NEW_MODEL_EDITOR
    var name by rememberSaveable(editorKey) { mutableStateOf(editingProfile?.name.orEmpty()) }
    var url by rememberSaveable(editorKey) { mutableStateOf(editingProfile?.baseUrl.orEmpty()) }
    var model by rememberSaveable(editorKey) { mutableStateOf(editingProfile?.model.orEmpty()) }
    // 密钥不进入 savedInstanceState，关闭设置后不保留明文。
    var key by remember(editorKey) { mutableStateOf("") }
    var window by rememberSaveable(editorKey) {
        mutableStateOf((editingProfile?.contextWindow ?: ContextPolicy().windowTokens).toString())
    }
    var output by rememberSaveable(editorKey) {
        mutableStateOf((editingProfile?.outputReserve ?: ContextPolicy().outputReserve).toString())
    }
    var reasoningField by rememberSaveable(editorKey) {
        mutableStateOf(editingProfile?.reasoningEffortField ?: "reasoning_effort")
    }
    // 逗号不属于合法等级字符，因此可以安全地用来保存页面重建时的可编辑列表。
    var reasoningEffortsText by rememberSaveable(editorKey) {
        mutableStateOf(
            (editingProfile?.reasoningEfforts ?: DEFAULT_REASONING_EFFORTS).joinToString(
                ","
            )
        )
    }
    val reasoningEfforts =
        reasoningEffortsText.split(',').map(String::trim).filter(String::isNotEmpty)
    var deleteTarget by remember { mutableStateOf<ModelProfile?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模型列表", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                "${settings.models.size}/$MAX_MODEL_PROFILES", color = colors.tertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            settings.models.forEach { profile ->
                val current = profile.id == settings.selectedModel?.id
                val editing = profile.id == editingProfile?.id
                Surface(
                    modifier = Modifier
                        .width(142.dp)
                        .height(66.dp)
                        .clickable { editingId = profile.id },
                    shape = RoundedCornerShape(16.dp),
                    color = if (editing) colors.accentSoft else colors.surface,
                    border = BorderStroke(1.dp, if (editing) colors.outline else colors.divider),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                    ) {
                        Column(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth()
                        ) {
                            Text(
                                profile.name,
                                modifier = Modifier.padding(end = if (current) 42.dp else 0.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                profile.model,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = colors.secondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (current) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd),
                                shape = RoundedCornerShape(8.dp),
                                color = colors.accent,
                                contentColor = colors.onAccent,
                            ) {
                                Text(
                                    "当前",
                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            val canAdd = settings.models.size < MAX_MODEL_PROFILES
            Surface(
                modifier = Modifier
                    .height(66.dp)
                    .clickable(enabled = canAdd) {
                        editingId = NEW_MODEL_EDITOR
                    },
                shape = RoundedCornerShape(16.dp),
                color = if (editingProfile == null) colors.accentSoft else colors.surface,
                border = BorderStroke(
                    1.dp,
                    if (editingProfile == null) colors.outline else colors.divider
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatIcon(R.drawable.lucide_plus, null, Modifier.size(17.dp), colors.secondary)
                    Text(
                        if (canAdd) "新增模型" else "已达上限", Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (canAdd) colors.text else colors.tertiary
                    )
                }
            }
        }

        Text("服务连接", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            ModelField(name, { name = it }, "配置名称", Modifier.testTag("model_name"))
            ModelField(
                url,
                { url = it },
                "服务地址",
                Modifier.testTag("model_url"),
                KeyboardType.Uri
            )
            ModelField(model, { model = it }, "模型 ID", Modifier.testTag("model_id"))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (editingProfile?.hasApiKey == true) "API 密钥（留空则不修改）" else "API 密钥") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                colors = modelFieldColors(),
            )
        }

        Text("上下文", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            ModelField(
                window,
                { window = it.filter(Char::isDigit) },
                "上下文长度（Token）",
                Modifier.testTag("model_window"),
                KeyboardType.Number,
            )
            ModelField(
                output,
                { output = it.filter(Char::isDigit) },
                "最大输出（Token）",
                Modifier.testTag("model_output"),
                keyboardType = KeyboardType.Number,
            )
            Text(
                "上下文总量包含输入和最大输出；App 会从总量中预留最大输出与少量协议余量。数值需要与模型服务支持的范围一致。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }

        Text("思考强度", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            ModelField(
                reasoningField,
                { reasoningField = it },
                "参数名",
                Modifier.testTag("reasoning_effort_field"),
            )
            Text(
                "可选等级（从低到高）",
                style = MaterialTheme.typography.labelLarge,
                color = colors.secondary,
            )
            ReasoningEffortEditor(
                efforts = reasoningEfforts,
                onChange = { reasoningEffortsText = it.joinToString(",") },
            )
        }

        Button(
            enabled = !saving,
            onClick = {
                onSave(
                    editingProfile?.id,
                    name,
                    url,
                    model,
                    key,
                    window,
                    output,
                    reasoningField,
                    reasoningEfforts,
                ) { savedId ->
                    key = ""
                    editingId = savedId
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent
            ),
        ) {
            Text(if (saving) "正在保存…" else if (editingProfile == null) "添加模型" else "保存模型")
        }
        editingProfile?.let { profile ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (profile.id != settings.selectedModel?.id) {
                    TextButton(enabled = !saving, onClick = { onSelect(profile.id) }) {
                        Text("设为当前模型")
                    }
                } else {
                    Text(
                        "聊天页当前使用", Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        color = colors.accent, style = MaterialTheme.typography.labelLarge
                    )
                }
                TextButton(enabled = !saving && canDelete, onClick = { deleteTarget = profile }) {
                    Text(
                        if (canDelete) "删除模型" else "回复中不可删除",
                        color = if (canDelete) colors.error else colors.tertiary
                    )
                }
            }
        }
        Spacer(Modifier.height(26.dp))
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
            title = { Text("删除模型配置？") },
            text = { Text("将删除“${profile.name}”的服务地址、参数和已保存密钥，此操作无法撤销。") },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(profile.id) { editingId = null }
                }) { Text("删除", color = colors.error) }
            },
        )
    }
}

private const val NEW_MODEL_EDITOR = "__new_model__"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningEffortEditor(
    efforts: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val colors = LocalChatColors.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var candidate by rememberSaveable { mutableStateOf("") }
    val normalizedCandidate = candidate.trim()
    val candidateValid = normalizedCandidate.matches(Regex("[A-Za-z0-9_.-]{1,40}")) &&
            !normalizedCandidate.equals(REASONING_EFFORT_OFF, ignoreCase = true) &&
            normalizedCandidate !in efforts

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        efforts.forEach { effort ->
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = colors.surfaceRaised,
                border = BorderStroke(1.dp, colors.divider),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(effort, style = MaterialTheme.typography.bodyMedium)
                    IconButton(
                        onClick = { onChange(efforts - effort) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        ChatIcon(
                            R.drawable.lucide_x,
                            "删除 $effort",
                            Modifier.size(15.dp),
                            colors.secondary
                        )
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.clickable { adding = true },
            shape = RoundedCornerShape(13.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatIcon(R.drawable.lucide_plus, null, Modifier.size(16.dp), colors.secondary)
                Text(
                    "新增", Modifier.padding(start = 5.dp), color = colors.secondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (adding) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = candidate,
                onValueChange = { candidate = it },
                modifier = Modifier.weight(1f),
                label = { Text("新等级值") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                colors = modelFieldColors(),
            )
            TextButton(
                enabled = candidateValid,
                onClick = {
                    onChange(efforts + normalizedCandidate)
                    candidate = ""
                    adding = false
                },
            ) { Text("添加") }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun ModelField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        colors = modelFieldColors(),
    )
}

@Composable
private fun modelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = LocalChatColors.current.surfaceRaised,
    unfocusedContainerColor = LocalChatColors.current.surfaceRaised,
    focusedBorderColor = LocalChatColors.current.accent,
    unfocusedBorderColor = LocalChatColors.current.divider,
)

@Composable
private fun DataSettings(
    models: List<ModelProfile>,
    usage: List<ModelUsageSummary>,
    cleaning: Boolean,
    canClean: Boolean,
    onClean: () -> Unit,
) {
    val colors = LocalChatColors.current
    val configuredIds = models.mapTo(mutableSetOf(), ModelProfile::id)
    val usageByProfile = usage.associateBy(ModelUsageSummary::modelProfileId)
    val usageRows = models.map { model ->
        usageByProfile[model.id]?.copy(modelName = model.name, modelId = model.model)
            ?: ModelUsageSummary(
                modelProfileId = model.id,
                modelName = model.name,
                modelId = model.model,
                measuredRequests = 0L,
                inputTokens = 0L,
                outputTokens = 0L,
                updatedAt = 0L,
            )
    } + usage.filterNot { it.modelProfileId in configuredIds }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("用量信息", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            if (usageRows.isEmpty()) {
                Text(
                    "暂无模型配置和可统计用量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            } else {
                usageRows.forEachIndexed { index, item ->
                    ModelUsageRow(item, configured = item.modelProfileId in configuredIds)
                    if (index != usageRows.lastIndex) SettingsDivider()
                }
            }
        }

        Text("存储清理", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            Text(
                "删除不再使用的缓存和临时文件。对话记录、正在使用的附件和手机原文件会保留。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
            Button(
                onClick = onClean,
                enabled = canClean && !cleaning,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cleanup_storage"),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(if (cleaning) "正在清理…" else "立即清理")
            }
            if (!canClean) {
                Text(
                    "任务执行期间无法清理，请等待任务完成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun AboutSettings(
    versionName: String,
    context: Context,
    onError: (SettingsNotice) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("应用", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            AboutValueRow("APP 版本号", "v$versionName")
        }

        Text("关于", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            AboutValueRow("作者", ABOUT_PLACEHOLDER)
            SettingsDivider()
            AboutValueRow("开源", OPEN_SOURCE_URL)
        }

        Text("鸣谢", style = MaterialTheme.typography.titleSmall)
        SettingsCard {
            acknowledgedLibraries.forEachIndexed { index, library ->
                AboutActionRow(
                    icon = R.drawable.lucide_globe,
                    title = library.name,
                    caption = "${library.version} · ${library.license}",
                    trailingIcon = R.drawable.lucide_external_link,
                    onClick = {
                        openAboutLink(context, library.sourceUrl, library.name, onError)
                    },
                )
                if (index != acknowledgedLibraries.lastIndex) SettingsDivider()
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

private fun openAboutLink(
    context: Context,
    url: String,
    name: String,
    onError: (SettingsNotice) -> Unit,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        onError(SettingsNotice("暂时无法打开 $name 项目页", false))
    }
}

@Composable
private fun AboutValueRow(label: String, value: String) {
    val colors = LocalChatColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.text)
    }
}

@Composable
private fun AboutActionRow(
    icon: Int,
    title: String,
    caption: String,
    trailingIcon: Int,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceRaised) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                ChatIcon(icon, null, Modifier.size(20.dp), colors.secondary)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                caption,
                color = colors.secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChatIcon(trailingIcon, null, Modifier.size(18.dp), colors.tertiary)
    }
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun ModelUsageRow(usage: ModelUsageSummary, configured: Boolean) {
    val colors = LocalChatColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                usage.modelName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!configured) {
                Text(
                    "已删除配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary
                )
            }
        }
        if (usage.modelId.isNotBlank() && usage.modelId != usage.modelName) {
            Text(
                usage.modelId,
                style = MaterialTheme.typography.labelSmall,
                color = colors.tertiary
            )
        }
        Text(
            "输入 ${formatUsageCount(usage.inputTokens)} · 输出 ${formatUsageCount(usage.outputTokens)} Token",
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
        Text(
            "合计 ${formatUsageCount(usage.totalTokens)} Token · ${formatUsageCount(usage.measuredRequests)} 次已统计请求",
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
    }
}

private fun formatUsageCount(value: Long): String =
    java.text.NumberFormat.getIntegerInstance().format(value)

@Composable
private fun SettingsDivider() {
    Surface(
        Modifier
            .fillMaxWidth()
            .height(1.dp), color = LocalChatColors.current.divider
    ) {}
}
