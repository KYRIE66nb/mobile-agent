package xyz.chouxuewei.mobile_agent.prototype

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.jsonObject
import xyz.chouxuewei.mobile_agent.core.ChatConnection
import xyz.chouxuewei.mobile_agent.core.ChatRuntime
import xyz.chouxuewei.mobile_agent.core.DeviceModePreference
import xyz.chouxuewei.mobile_agent.core.FailoverGateway
import xyz.chouxuewei.mobile_agent.core.LlmDecisionGate
import xyz.chouxuewei.mobile_agent.core.RetryingGateway
import xyz.chouxuewei.mobile_agent.data.AppearanceRepository
import xyz.chouxuewei.mobile_agent.data.RoomConversationStore
import xyz.chouxuewei.mobile_agent.data.RoomArtifactStore
import xyz.chouxuewei.mobile_agent.model.OpenAiChatGateway
import xyz.chouxuewei.mobile_agent.BuildConfig
import xyz.chouxuewei.mobile_agent.core.ModelConfig
import xyz.chouxuewei.mobile_agent.data.ModelSettingsRepository
import xyz.chouxuewei.mobile_agent.data.ModelUsageRepository
import xyz.chouxuewei.mobile_agent.data.PersonalizationRepository
import xyz.chouxuewei.mobile_agent.device.AndroidDeviceGateway
import xyz.chouxuewei.mobile_agent.device.MainDisplayOverlayController
import xyz.chouxuewei.mobile_agent.overlay.DeviceOperationOverlayService
import xyz.chouxuewei.mobile_agent.tools.ToolCatalog
import xyz.chouxuewei.mobile_agent.tools.ToolPermissionRepository
import xyz.chouxuewei.mobile_agent.attachments.AttachmentManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.StorageCleanupResult
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker
import xyz.chouxuewei.mobile_agent.data.SpeechSettingsRepository
import xyz.chouxuewei.mobile_agent.data.AgentExecutionSettingsRepository
import xyz.chouxuewei.mobile_agent.data.AdGuardRepository
import xyz.chouxuewei.mobile_agent.data.RecipeRepository
import xyz.chouxuewei.mobile_agent.data.TriggerRepository
import xyz.chouxuewei.mobile_agent.device.adguard.AdGuardEngine
import xyz.chouxuewei.mobile_agent.device.recipe.RecipeEngine
import xyz.chouxuewei.mobile_agent.model.SpeechTranscriptionGateway
import xyz.chouxuewei.mobile_agent.model.IflytekSpeechTranscriptionGateway
import xyz.chouxuewei.mobile_agent.tools.notifications.AgentNotificationListenerService
import xyz.chouxuewei.mobile_agent.voice.VoiceInputController
import xyz.chouxuewei.mobile_agent.voice.VoiceInputDestination

class PrototypeApplication : Application() {
    val deviceGateway by lazy {
        AndroidDeviceGateway.configureRootShell()
        AndroidDeviceGateway(this, MainDisplayOverlayController { hidden ->
            DeviceOperationOverlayService.setHiddenForDeviceInteraction(hidden)
        })
    }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val conversations by lazy { RoomConversationStore(this) }
    val artifacts by lazy { RoomArtifactStore(this) }
    val attachments by lazy { AttachmentManager(this, conversations) }
    val appearance by lazy { AppearanceRepository(this) }
    val personalization by lazy { PersonalizationRepository(this) }
    val agentExecutionSettings by lazy { AgentExecutionSettingsRepository(this) }
    val adGuardSettings by lazy { AdGuardRepository(this) }
    val recipeSettings by lazy { RecipeRepository(this) }
    val triggerSettings by lazy { TriggerRepository(this) }
    val speechSettings by lazy { SpeechSettingsRepository(this) }
    val toolPermissions by lazy { ToolPermissionRepository(this) }
    val userQuestions by lazy { UserQuestionBroker() }
    /** 设置项的热快照，供同步的审批摘要与工具逻辑直接读取。 */
    private val deviceModePreference by lazy {
        agentExecutionSettings.deviceModePreference.stateIn(
            applicationScope, SharingStarted.Eagerly, DeviceModePreference.AUTO
        )
    }
    val toolRegistry by lazy {
        ToolCatalog.create(this, conversations, artifacts, deviceGateway, userQuestions, AdGuardEngine, RecipeEngine, triggers) {
            deviceModePreference.value
        }
    }
    val voiceAnnouncer by lazy { xyz.chouxuewei.mobile_agent.voice.VoiceAnnouncer(this) }
    val triggers: xyz.chouxuewei.mobile_agent.triggers.TriggerManager by lazy {
        xyz.chouxuewei.mobile_agent.triggers.TriggerManager(
            context = this,
            repository = triggerSettings,
            runtime = { chatRuntime },
            conversations = conversations,
            scope = applicationScope,
            announcer = voiceAnnouncer,
            announceEnabled = { agentExecutionSettings.currentAnnounceTaskResults() },
        )
    }
    val chatWorkspace by lazy { xyz.chouxuewei.mobile_agent.chat.ChatWorkspace(this) }
    val chatRuntime by lazy {
        ChatRuntime(
            store = conversations,
            connection = { modelProfileId ->
                val resolved = modelSettings.resolveChatConfiguration(modelProfileId)
                // 每个端点自带瞬时故障重试；开启故障切换且存在备用配置时外包一层主备切换。
                val primary = RetryingGateway(OpenAiChatGateway(resolved.config))
                val gateway = if (agentExecutionSettings.currentAutoModelFailover()) {
                    modelSettings.backupConfiguration(resolved.profileId)?.let { backup ->
                        FailoverGateway(primary, RetryingGateway(OpenAiChatGateway(backup.config)))
                    } ?: primary
                } else primary
                ChatConnection(
                    gateway = gateway,
                    policy = resolved.policy,
                    model = resolved.config.model.orEmpty(),
                    modelProfileId = resolved.profileId,
                    modelName = resolved.profileName,
                    supportsImages = resolved.config.supportsImages,
                )
            },
            scope = applicationScope,
            tools = toolRegistry,
            toolPermissions = toolPermissions,
            attachmentLoader = attachments,
            usageRecorder = modelUsage::record,
            personalizedInstructions = personalization::currentInstructions,
            maxStepsPerRun = agentExecutionSettings::currentMaxSteps,
            safetyGate = { connection, runPolicy ->
                decisionGateFactory.safetyGate(connection, runPolicy)
            },
        )
    }
    val decisionSettings by lazy { xyz.chouxuewei.mobile_agent.data.DecisionSettingsRepository(this) }

    /** 专用决策后端闸工厂；审计落库在 task 7 接入，先以内存回调占位。 */
    private val decisionGateFactory by lazy {
        xyz.chouxuewei.mobile_agent.core.DecisionGateFactory(
            settings = { decisionSettings.current() },
            resolveProvider = { backend -> resolveDecisionProvider(backend) },
            legacyGateEnabled = { agentExecutionSettings.currentSafetyGateEnabled() },
            legacyGate = { connection -> LlmDecisionGate(connection.gateway) },
            audit = { decisionAudit.record(it) },
        )
    }

    /** Task 7 替换为 Room 持久化；现在保持可注入的空实现，保证调用链形状稳定。 */
    val decisionAudit by lazy { DecisionAuditRecorder() }

    /**
     * 测试当前所选后端的连通性：发送一道最小的 choice 判定（不含任何用户数据），
     * 返回可读摘要或抛出带原因的错误。不改变任何运行状态。
     */
    suspend fun testDecisionConnection(): String {
        val snapshot = decisionSettings.current()
        val backend = snapshot.backend
        require(backend != xyz.chouxuewei.mobile_agent.core.DecisionBackend.NONE) {
            localizedText("尚未选择专用后端", "No dedicated backend is selected")
        }
        val provider = resolveDecisionProvider(backend) ?: error(
            localizedText(
                "配置不完整：请检查地址、密钥与出站同意",
                "Configuration incomplete: check the endpoint, key, and outbound consent",
            )
        )
        val request = xyz.chouxuewei.mobile_agent.core.DecisionChoiceRequest(
            purpose = xyz.chouxuewei.mobile_agent.core.DecisionPurpose.SAFETY_GATE,
            requestId = "probe-${System.currentTimeMillis()}",
            state = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"probe":"connection_test"}"""
            ).jsonObject,
            instructions = "Connection test. Choose 'allow'.",
            options = mapOf("allow" to "connectivity ok", "block" to "refuse"),
        )
        return when (val outcome = provider.choose(request)) {
            is xyz.chouxuewei.mobile_agent.core.DecisionOutcome.Accepted -> localizedText(
                "已连通（${outcome.choice.modelEcho ?: backend.wireName}，${outcome.choice.latencyMillis}ms，选择 ${outcome.choice.choice}）",
                "Connected (${outcome.choice.modelEcho ?: backend.wireName}, ${outcome.choice.latencyMillis}ms, chose ${outcome.choice.choice})",
            )
            is xyz.chouxuewei.mobile_agent.core.DecisionOutcome.Failed -> error(
                localizedText(
                    "连接失败：${outcome.kind.wireName}${outcome.httpStatus?.let { "（HTTP $it）" } ?: ""}",
                    "Connection failed: ${outcome.kind.wireName}${outcome.httpStatus?.let { " (HTTP $it)" } ?: ""}",
                )
            )
        }
    }

    /** 按后端解析 provider：读取当前配置快照，未同意出站/配置不完整/密钥缺失时返回 null。 */
    private suspend fun resolveDecisionProvider(
        backend: xyz.chouxuewei.mobile_agent.core.DecisionBackend,
    ): xyz.chouxuewei.mobile_agent.core.DecisionProvider? {
        val snapshot = decisionSettings.current()
        if (!snapshot.outboundConsent || snapshot.backend != backend) return null
        val profile = snapshot.profileFor(backend)
        if (!profile.hasValidBaseUrl) return null
        val apiKey = decisionSettings.resolveApiKey(backend)
        return when (backend) {
            xyz.chouxuewei.mobile_agent.core.DecisionBackend.LAYA ->
                xyz.chouxuewei.mobile_agent.model.layaDecisionProvider(
                    baseUrl = profile.baseUrl,
                    model = profile.model.ifBlank { "typed-decisions" },
                    apiKey = apiKey,
                )
            xyz.chouxuewei.mobile_agent.core.DecisionBackend.JEV -> {
                // Jev 为托管 API，密钥缺失直接不可用（服务端会返回 401，提前本地失败更省往返）。
                if (apiKey == null) return null
                xyz.chouxuewei.mobile_agent.model.jevDecisionProvider(
                    apiKey = apiKey,
                    baseUrl = profile.baseUrl,
                    model = profile.model.ifBlank { "jev-latest" },
                )
            }
            xyz.chouxuewei.mobile_agent.core.DecisionBackend.NONE -> null
        }
    }

    private val debugModelConfig by lazy {
        ModelConfig(
            baseUrl = BuildConfig.MODEL_BASE_URL,
            model = BuildConfig.MODEL_NAME.takeIf(String::isNotBlank),
            apiKey = BuildConfig.MODEL_API_KEY,
        )
    }
    val modelSettings by lazy { ModelSettingsRepository(this, debugModelConfig) }
    val modelUsage by lazy { ModelUsageRepository(this) }
    val requestedSettingsPage = MutableStateFlow<String?>(null)
    val voiceInput by lazy {
        VoiceInputController(
            context = this,
            scope = applicationScope,
            settings = speechSettings,
            openAiGateway = SpeechTranscriptionGateway(),
            iflytekGateway = IflytekSpeechTranscriptionGateway(),
            onTranscript = { target, text ->
                when (target.destination) {
                    VoiceInputDestination.CURRENT_CONVERSATION -> chatWorkspace.sendTranscription(
                        target.conversationId,
                        text,
                        target.reasoningEffort,
                        target.modelProfileId,
                    )
                    VoiceInputDestination.NEW_CONVERSATION -> chatWorkspace.sendTranscriptionToNewConversation(
                        text,
                        target.reasoningEffort,
                        target.modelProfileId,
                    )
                }
            },
            onRecordingStopped = DeviceOperationOverlayService::setMicrophoneCaptureInactive,
        )
    }

    suspend fun cleanupStorage(): StorageCleanupResult {
        require(chatRuntime.active.value.isEmpty()) {
            localizedText("正在生成回复，请结束后再清理", "A response is being generated. Stop it before cleaning storage.")
        }
        return artifacts.cleanup() + attachments.cleanup()
    }

    suspend fun exportBackup(uri: android.net.Uri) {
        val json = conversations.exportBackupJson()
        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error(localizedText("无法写入所选位置", "Could not write to the selected location."))
    }

    suspend fun importBackup(uri: android.net.Uri): RoomConversationStore.BackupReport {
        val raw = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error(localizedText("无法读取所选文件", "Could not read the selected file."))
        return conversations.importBackupJson(raw)
    }

    /** 保留策略入口：删除早于指定天数的登记产物（文件与索引一起移除），聊天里的产物卡片随后显示为已失效。 */
    suspend fun pruneGeneratedArtifacts(olderThanDays: Int = 30): StorageCleanupResult {
        require(chatRuntime.active.value.isEmpty()) {
            localizedText("正在生成回复，请结束后再清理", "A response is being generated. Stop it before cleaning storage.")
        }
        return artifacts.pruneArtifacts(System.currentTimeMillis() - olderThanDays * 86_400_000L)
    }

    override fun onCreate() {
        super.onCreate()
        AgentLog.install(AgentLog.Sink { level, tag, message, error ->
            when (level) {
                AgentLog.Level.DEBUG -> if (error == null) Log.d(tag, message) else Log.d(tag, message, error)
                AgentLog.Level.INFO -> if (error == null) Log.i(tag, message) else Log.i(tag, message, error)
                AgentLog.Level.WARN -> if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
                AgentLog.Level.ERROR -> if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
            }
        })
        applicationScope.launch {
            appearance.detailedLogging.collectLatest { enabled ->
                AgentLog.enabled = enabled
                if (enabled) AgentLog.i("App") { "详细日志已开启" }
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            // 启动时没有正在写入的工具任务，适合安全回收上次异常中断留下的孤立文件。
            artifacts.cleanup()
            attachments.cleanup()
        }
        // 广告守卫：持久化配置单向灌入引擎；引擎里的修改经 persister 回写，applyPersisted 自身不触发回写。
        AdGuardEngine.persister = { snapshot ->
            adGuardSettings.save(snapshot.enabled, snapshot.showToast, snapshot.rules)
        }
        applicationScope.launch {
            adGuardSettings.state.collectLatest { persisted ->
                AdGuardEngine.applyPersisted(persisted.enabled, persisted.showToast, persisted.rules)
            }
        }
        // 操作模板：内置模板由引擎自带，自定义模板从持久化灌入；引擎改动经 persister 回写。
        RecipeEngine.persister = { custom -> recipeSettings.save(custom) }
        applicationScope.launch {
            recipeSettings.customRecipes.collectLatest { custom ->
                RecipeEngine.applyPersisted(custom)
            }
        }
        // 触发器：持久化定义驱动排期与事件匹配，通知监听器在线时把事件喂给引擎。
        triggers.start()
        AgentNotificationListenerService.postedListener = { event ->
            applicationScope.launch(Dispatchers.IO) { triggers.onNotification(event) }
        }
    }
}
