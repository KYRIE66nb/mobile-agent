package xyz.chouxuewei.mobile_agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import xyz.chouxuewei.mobile_agent.chat.ChatApp
import xyz.chouxuewei.mobile_agent.overlay.DeviceOperationOverlayService
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.substitution.SubstitutionTimerCoordinator
import xyz.chouxuewei.mobile_agent.substitution.SubstitutionTimerService
import kotlinx.coroutines.launch

/** 正式入口只装配聊天，不获取原型控制器、设备网关或 Root 会话。 */
class MainActivity : AppCompatActivity() {

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        SubstitutionTimerCoordinator.onConsentResult(result.resultCode, result.data)
    }

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
        // 幂等：仅在需要时推进计时器授权流程，旋转/重组不重复触发
        SubstitutionTimerCoordinator.onAppVisible(projectionConsent)
    }

    override fun onStop() {
        DeviceOperationOverlayService.setAppVisible(this, false)
        SubstitutionTimerCoordinator.onAppHidden()
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
        if (intent.action == SubstitutionTimerService.ACTION_OPEN_SETTINGS) {
            app.requestedSettingsPage.value = "timer"
        }
        app.applicationScope.launch { app.attachments.receive(intent) }
    }
}
