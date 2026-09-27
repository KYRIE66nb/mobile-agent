package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import xyz.chouxuewei.mobile_agent.core.AdGuardController
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.ConversationStore
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.DeviceModePreference
import xyz.chouxuewei.mobile_agent.core.ToolRegistry
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker

object ToolCatalog {
    fun create(
        context: Context,
        conversations: ConversationStore,
        artifacts: ArtifactStore,
        device: DeviceGateway,
        questions: UserQuestionBroker,
        adGuard: AdGuardController,
        deviceModePreference: () -> DeviceModePreference = { DeviceModePreference.AUTO },
    ): ToolRegistry = ToolRegistry(
        listOf(
            HistoryToolProvider(conversations),
            FileToolProvider(context, conversations, artifacts),
            ImageRenderToolProvider(context, artifacts),
            NetworkToolProvider(),
            DeviceToolProvider(device, deviceModePreference),
            ClipboardToolProvider(context),
            NotificationToolProvider(context),
            SystemToolProvider(context, device),
            AdGuardToolProvider(adGuard),
            InteractionToolProvider(questions),
        ),
    )
}
