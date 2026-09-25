package xyz.chouxuewei.mobile_agent.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 只恢复用户明确开启的常驻入口；开机后不恢复或重放上一次未完成的任务。 */
class OverlayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as PrototypeApplication
                if (app.appearance.persistentOverlay.first()) {
                    DeviceOperationOverlayService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
