package xyz.chouxuewei.mobile_agent.prototype

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import xyz.chouxuewei.mobile_agent.core.ChatConnection
import xyz.chouxuewei.mobile_agent.core.ChatRuntime
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
import xyz.chouxuewei.mobile_agent.model.SpeechTranscriptionGateway
import xyz.chouxuewei.mobile_agent.model.IflytekSpeechTranscriptionGateway
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
    val speechSettings by lazy { SpeechSettingsRepository(this) }
    val toolPermissions by lazy { ToolPermissionRepository(this) }
    val userQuestions by lazy { UserQuestionBroker() }
    val toolRegistry by lazy {
        ToolCatalog.create(this, conversations, artifacts, deviceGateway, userQuestions)
    }
    val chatWorkspace by lazy { xyz.chouxuewei.mobile_agent.chat.ChatWorkspace(this) }
    val chatRuntime by lazy {
        ChatRuntime(
            store = conversations,
            connection = { modelProfileId ->
                val resolved = modelSettings.resolveChatConfiguration(modelProfileId)
                ChatConnection(
                    gateway = OpenAiChatGateway(resolved.config),
                    policy = resolved.policy,
                    model = resolved.config.model.orEmpty(),
                    modelProfileId = resolved.profileId,
                    modelName = resolved.profileName,
                )
            },
            scope = applicationScope,
            tools = toolRegistry,
            toolPermissions = toolPermissions,
            attachmentLoader = attachments,
            usageRecorder = modelUsage::record,
            personalizedInstructions = personalization::currentInstructions,
            maxStepsPerRun = agentExecutionSettings::currentMaxSteps,
        )
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
        require(chatRuntime.active.value.isEmpty()) { "正在生成回复，请结束后再清理" }
        return artifacts.cleanup() + attachments.cleanup()
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
    }
}
