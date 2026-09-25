package xyz.chouxuewei.mobile_agent.device.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import xyz.chouxuewei.mobile_agent.core.AppTarget
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** libsu 和 AIDL 只在设备模块内出现，上层只看到 DeviceGateway。 */
internal class RootBridge(private val context: Context) {
    private var remote: IRootDevice? = null
    private var connection: ServiceConnection? = null

    suspend fun connect(): IRootDevice {
        remote?.let { return it }
        return withTimeout(60_000) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            val service = IRootDevice.Stub.asInterface(binder)
                            remote = service
                            if (continuation.isActive) continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName) {
                            remote = null
                        }

                        override fun onNullBinding(name: ComponentName) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IllegalStateException("Root 服务未返回 Binder"))
                            }
                        }
                    }
                    connection = callback
                    try {
                        RootService.bind(Intent(context, RootDeviceService::class.java), callback)
                    } catch (error: Exception) {
                        connection = null
                        continuation.resumeWithException(error)
                    }
                    continuation.invokeOnCancellation {
                        if (remote == null && connection === callback) {
                            runCatching { RootService.unbind(callback) }
                            connection = null
                        }
                    }
                }
            }
        }
    }

    fun service(): IRootDevice = checkNotNull(remote) { "Root 连接已断开" }

    fun enableNodeService() = service().enableNodeService()
    fun createDisplay(surface: Surface): Int = service().createDisplay(surface)
    fun releaseDisplay(displayId: Int) = service().releaseDisplay(displayId)
    fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        service().gesture(displayId, x1, y1, x2, y2, durationMs)
    fun pressKey(displayId: Int, keyCode: Int) = service().pressKey(displayId, keyCode)
    fun launch(displayId: Int, target: AppTarget): String = service().launchApp(displayId, target.packageName)
    fun captureMain(): ParcelFileDescriptor = service().captureMain()

    suspend fun close() {
        val callback = connection ?: return
        remote = null
        connection = null
        withContext(Dispatchers.Main.immediate) { RootService.unbind(callback) }
    }
}
