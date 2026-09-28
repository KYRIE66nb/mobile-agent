package xyz.chouxuewei.mobile_agent.triggers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import xyz.chouxuewei.mobile_agent.MainActivity
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.core.TriggerSpec
import xyz.chouxuewei.mobile_agent.core.localizedText

/** 触发执行结果的本地通知；本应用包名已在引擎里豁免，不会形成触发回环。 */
class TriggerNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                localizedText("定时任务", "Scheduled tasks"),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = localizedText("触发器执行结果", "Trigger execution results")
            }
        )
    }

    fun notify(spec: TriggerSpec, succeeded: Boolean, summary: String) {
        if (!spec.notifyUser) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = if (succeeded)
            localizedText("任务「${spec.name}」已完成", "Task \"${spec.name}\" finished")
        else
            localizedText("任务「${spec.name}」失败", "Task \"${spec.name}\" failed")
        val openApp = PendingIntent.getActivity(
            context, spec.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.lucide_sparkles)
            .setContentTitle(title)
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(500)))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_BASE + (spec.id.hashCode() and 0xFFFF), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "trigger_results"
        private const val NOTIFICATION_BASE = 0x54_0000
    }
}
