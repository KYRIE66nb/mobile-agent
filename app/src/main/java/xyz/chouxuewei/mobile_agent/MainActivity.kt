package xyz.chouxuewei.mobile_agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import xyz.chouxuewei.mobile_agent.chat.ChatApp
import xyz.chouxuewei.mobile_agent.overlay.DeviceOperationOverlayService
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import kotlinx.coroutines.launch

/** 正式入口只装配聊天，不获取原型控制器、设备网关或 Root 会话。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PrototypeApplication
        setContent { ChatApp(app) }
        if (savedInstanceState == null || intent.action == DeviceOperationOverlayService.ACTION_OPEN_CONVERSATION ||
            intent.action == DeviceOperationOverlayService.ACTION_OPEN_SPEECH_SETTINGS
        ) {
            receiveIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        DeviceOperationOverlayService.setAppVisible(this, true)
    }

    override fun onStop() {
        DeviceOperationOverlayService.setAppVisible(this, false)
        super.onStop()
    }

    private fun receiveIntent(intent: Intent) {
        val app = application as PrototypeApplication
        if (intent.action == DeviceOperationOverlayService.ACTION_OPEN_CONVERSATION) {
            intent.getStringExtra(DeviceOperationOverlayService.EXTRA_CONVERSATION_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(app.chatWorkspace::select)
        }
        if (intent.action == DeviceOperationOverlayService.ACTION_OPEN_SPEECH_SETTINGS) {
            app.requestedSettingsPage.value = "voice"
        }
        app.applicationScope.launch { app.attachments.receive(intent) }
    }
}
