package xyz.chouxuewei.mobile_agent.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.core.TriggerSpec
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication

/** 闹钟到点 → 交给 TriggerManager 执行；执行内部自行滚动排下一班。 */
class TriggerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                (context.applicationContext as? PrototypeApplication)?.triggers?.onAlarm(id)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "xyz.chouxuewei.mobile_agent.action.TRIGGER_FIRE"
        const val EXTRA_ID = "trigger_id"
    }
}

/**
 * AlarmManager 包装：不申请 SCHEDULE_EXACT_ALARM，用 setWindow(RTC_WAKEUP) 一分钟漂移窗。
 * PendingIntent 按触发器 id 区分且 FLAG_UPDATE_CURRENT，重复排期天然去重。
 * 省电模式与厂商后台限制可能延迟触发，这是平台行为而非缺陷。
 */
class TriggerScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(spec: TriggerSpec, atMs: Long) {
        val alarm = alarmManager ?: return
        if (atMs <= System.currentTimeMillis()) return
        alarm.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, pendingIntent(spec.id))
    }

    fun cancel(triggerId: String) {
        alarmManager?.cancel(pendingIntent(triggerId))
    }

    /** 显式组件 intent：非导出接收器没有 intent-filter，隐式 action 无法解析；data 让 PendingIntent 按触发器去重。 */
    private fun pendingIntent(triggerId: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCodeFor(triggerId),
        Intent(appContext, TriggerAlarmReceiver::class.java)
            .setAction(TriggerAlarmReceiver.ACTION_FIRE)
            .setData(android.net.Uri.parse("app://trigger/$triggerId"))
            .putExtra(TriggerAlarmReceiver.EXTRA_ID, triggerId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCodeFor(triggerId: String): Int =
        BASE_REQUEST_CODE + (triggerId.hashCode() and REQUEST_MASK)

    companion object {
        private const val WINDOW_MS = 60_000L
        private const val BASE_REQUEST_CODE = 0x54_0000
        private const val REQUEST_MASK = 0xFFFF
    }
}
