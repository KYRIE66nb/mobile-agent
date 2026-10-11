package xyz.chouxuewei.mobile_agent.device.bridge

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.device.accessibility.AccessibilityDeviceGateway

/**
 * 桌面桥前台服务：启动即在 127.0.0.1:17325 监听，通知栏常驻"电脑控制已开启"可一键停止。
 * adb shell 可直接 `am start-foreground-service -n <pkg>/.bridge.BridgeService`（shell 可启动
 * 非导出组件），与设置页开关走同一条 setEnabled 路径。
 */
class BridgeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: BridgeSettings
    private var server: BridgeServer? = null
    private var controller: BridgeController? = null

    override fun onCreate() {
        super.onCreate()
        settings = BridgeSettings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.enabled = false
                stopServer()
                updateState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 任何来源的显式启动（设置开关或授权 adb）都视为开启桌面桥。
                settings.enabled = true
                settings.pairingToken()
                startServer()
                updateState()
                return START_STICKY
            }
        }
    }

    private fun startServer() {
        if (server != null) {
            updateNotification()
            return
        }
        val gateway = AccessibilityDeviceGateway(this)
        val bridgeController = BridgeController(gateway)
        val bridgeServer = BridgeServer(bridgeController, tokenProvider = { settings.pairingToken() })
        controller = bridgeController
        server = bridgeServer
        try {
            bridgeServer.start(serviceScope)
        } catch (error: Exception) {
            server = null
            controller = null
            AgentLog.e("Bridge", error) { "start_failed" }
            settings.enabled = false
            updateState()
            stopSelf()
            return
        }
        startForegroundWithNotification()
        updateState()
    }

    private fun stopServer() {
        server?.stop()
        server = null
        controller = null
    }

    private fun updateState() {
        _state.value = BridgeState(
            enabled = settings.enabled,
            listening = server?.listening == true,
        )
    }

    private fun startForegroundWithNotification() {
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopAction = NotificationCompat.Action.Builder(
            null,
            localizedText("停止", "Stop"),
            PendingIntent.getService(
                this, 1,
                Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(localizedText("电脑控制已开启", "Desktop control active"))
            .setContentText(localizedText("USB 通道可读取屏幕并注入操作，不使用时可停止", "A USB session can read the screen and inject actions; stop it when done"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localizedText("桌面控制桥", "Desktop control bridge"),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onDestroy() {
        stopServer()
        _state.value = BridgeState(enabled = settings.enabled, listening = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class BridgeState(
        val enabled: Boolean,
        val listening: Boolean,
    )

    companion object {
        const val ACTION_START = "xyz.chouxuewei.mobile_agent.bridge.START"
        const val ACTION_STOP = "xyz.chouxuewei.mobile_agent.bridge.STOP"
        private const val CHANNEL_ID = "desktop_bridge"
        private const val NOTIFICATION_ID = 73

        private val _state = MutableStateFlow(BridgeState(enabled = false, listening = false))
        val state: StateFlow<BridgeState> = _state

        /** 设置开关与 adb 启动共用入口；开启状态持久化在 BridgeSettings。 */
        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, BridgeService::class.java)
            if (enabled) {
                context.startForegroundService(intent.setAction(ACTION_START))
            } else {
                context.startService(intent.setAction(ACTION_STOP))
            }
        }

        /** App 进程启动时恢复上次开启状态（如系统回收过进程）。 */
        fun restoreIfEnabled(context: Context) {
            if (BridgeSettings(context).enabled) {
                runCatching { context.startForegroundService(Intent(context, BridgeService::class.java).setAction(ACTION_START)) }
            }
        }
    }
}
