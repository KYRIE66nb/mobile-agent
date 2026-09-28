package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import xyz.chouxuewei.mobile_agent.core.AdGuardController
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.ConversationStore
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.DeviceModePreference
import xyz.chouxuewei.mobile_agent.core.RecipeController
import xyz.chouxuewei.mobile_agent.core.ToolRegistry
import xyz.chouxuewei.mobile_agent.core.TriggerController
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker

object ToolCatalog {
    fun create(
        context: Context,
        conversations: ConversationStore,
        artifacts: ArtifactStore,
        device: DeviceGateway,
        questions: UserQuestionBroker,
        adGuard: AdGuardController,
        recipes: RecipeController,
        triggers: TriggerController,
        deviceModePreference: () -> DeviceModePreference = { DeviceModePreference.AUTO },
    ): ToolRegistry {
        var registry: ToolRegistry? = null
        val providers = listOf(
            HistoryToolProvider(conversations),
            FileToolProvider(context, conversations, artifacts),
            ImageRenderToolProvider(context, artifacts),
            NetworkToolProvider(),
            DeviceToolProvider(device, deviceModePreference),
            ClipboardToolProvider(context),
            NotificationToolProvider(context),
            SystemToolProvider(context, device),
            AdGuardToolProvider(adGuard),
            RecipeToolProvider(recipes),
            PersonalDataToolProvider(context),
            InteractionToolProvider(questions),
            TriggerToolProvider(triggers) {
                registry?.definitions?.mapTo(linkedSetOf()) { it.id }.orEmpty()
            },
        )
        registry = ToolRegistry(providers)
        return registry
    }
}
