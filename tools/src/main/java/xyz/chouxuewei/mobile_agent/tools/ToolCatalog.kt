package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.ConversationStore
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.ToolRegistry
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker

object ToolCatalog {
    fun create(
        context: Context,
        conversations: ConversationStore,
        artifacts: ArtifactStore,
        device: DeviceGateway,
        questions: UserQuestionBroker,
    ): ToolRegistry = ToolRegistry(
        listOf(
            HistoryToolProvider(conversations),
            FileToolProvider(context, conversations, artifacts),
            ImageRenderToolProvider(context, artifacts),
            NetworkToolProvider(),
            DeviceToolProvider(device),
            ClipboardToolProvider(context),
            NotificationToolProvider(context),
            SystemToolProvider(context),
            InteractionToolProvider(questions),
        ),
    )
}
