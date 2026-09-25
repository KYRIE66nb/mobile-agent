package xyz.chouxuewei.mobile_agent.device.root

import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.Surface
import com.topjohnwu.superuser.ipc.RootService
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService

class RootDeviceService : RootService() {
    private val lock = Any()
    private var display: VirtualDisplay? = null
    private var ownedSurface: Surface? = null
    private var closed = false

    // Binder 入口先验证调用方，再清除继承的调用身份，以 Root 工作进程身份调用系统。
    private fun <T> call(operation: () -> T): T {
        check(Binder.getCallingUid() == applicationInfo.uid) { "拒绝其他应用调用 Root 接口" }
        val identity = Binder.clearCallingIdentity()
        try {
            return synchronized(lock) {
                check(!closed) { "Root 服务已停止" }
                try { operation() } catch (error: Exception) {
                    val cause = error.cause ?: error
                    AgentLog.e("Root", cause) { "设备操作失败" }
                    throw IllegalStateException(cause.message ?: cause.javaClass.simpleName)
                }
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun requireDisplay(id: Int) {
        check(id == 0 || (display?.display?.displayId == id && display?.display?.isValid == true)) {
            "虚拟屏会话已失效，拒绝操作 displayId=$id"
        }
    }

    private fun requireCoordinates(displayId: Int, vararg coordinates: Pair<Int, Int>) {
        val target = checkNotNull(getSystemService(DisplayManager::class.java).getDisplay(displayId)) {
            "找不到目标显示，拒绝操作 displayId=$displayId"
        }
        val size = Point()
        // 主屏分辨率与虚拟屏不同，必须从实际目标显示读取尺寸，不能套用虚拟屏常量。
        @Suppress("DEPRECATION")
        target.getRealSize(size)
        check(size.x > 0 && size.y > 0) { "目标显示尺寸无效：${size.x}x${size.y}" }
        require(coordinates.all { (x, y) -> x in 0 until size.x && y in 0 until size.y }) {
            "坐标超出目标显示 ${size.x}x${size.y}"
        }
    }

    private val binder = object : IRootDevice.Stub() {
        override fun enableNodeService() = call {
            val userId = (applicationInfo.uid / 100000).toString()
            val component = "$packageName/${AgentAccessibilityService::class.java.name}"
            val current = DeviceCommands.run("/system/bin/settings", "--user", userId,
                "get", "secure", "enabled_accessibility_services").trim()
            // 只加入自己的节点服务，保留用户已经启用的其他无障碍服务。
            val services = current.takeUnless { it == "null" || it.isBlank() }
                ?.split(':')?.toMutableSet() ?: mutableSetOf()
            services.remove("$packageName/$packageName.prototype.accessibility.AgentAccessibilityService")
            if (services.remove(component)) {
                // 更新 APK 或测试进程退出后，系统可能保留 enabled 但不再绑定；仅重启自己的服务。
                DeviceCommands.run("/system/bin/settings", "--user", userId,
                    "put", "secure", "enabled_accessibility_services", services.joinToString(":"))
                android.os.SystemClock.sleep(250)
            }
            services += component
            DeviceCommands.run("/system/bin/settings", "--user", userId,
                "put", "secure", "enabled_accessibility_services", services.joinToString(":"))
            DeviceCommands.run("/system/bin/settings", "--user", userId,
                "put", "secure", "accessibility_enabled", "1")
            Unit
        }

        override fun createDisplay(surface: Surface): Int = call {
            check(display == null) { "只允许一个虚拟屏" }
            check(surface.isValid) { "画面接收 Surface 无效" }
            try {
                display = DisplayAdapter.create(this@RootDeviceService, surface)
                ownedSurface = surface
                display!!.display.displayId.also { AgentLog.i("Root") { "created_display id=$it" } }
            } catch (error: Exception) {
                surface.release()
                throw error
            }
        }

        override fun releaseDisplay(displayId: Int) = call {
            requireDisplay(displayId)
            release()
        }

        override fun launchApp(displayId: Int, packageName: String): String = call {
            require(packageName.length <= 255 && PACKAGE_PATTERN.matches(packageName)) { "应用包名格式无效" }
            val launchIntent = checkNotNull(packageManager.getLaunchIntentForPackage(packageName)) {
                "$packageName 未安装或没有可启动入口"
            }
            val component = launchIntent.component ?: launchIntent.resolveActivity(packageManager)
            checkNotNull(component) { "无法解析 $packageName 的启动入口" }
            @Suppress("DEPRECATION")
            val label = runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
            launchOnDisplay(displayId, label, packageName, component.flattenToShortString())
        }

        override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = call {
            requireDisplay(displayId)
            requireCoordinates(displayId, x1 to y1, x2 to y2)
            require(durationMs in 0..2000) { "手势时长超出限制" }
            if (durationMs == 0) {
                DeviceCommands.run("/system/bin/input", "-d", displayId.toString(), "tap", x1.toString(), y1.toString())
            } else {
                DeviceCommands.run("/system/bin/input", "-d", displayId.toString(), "swipe",
                    x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
            }
            Unit
        }

        override fun pressKey(displayId: Int, keyCode: Int) = call {
            requireDisplay(displayId)
            require(keyCode in SUPPORTED_KEYS) { "不支持的系统按键" }
            DeviceCommands.run("/system/bin/input", "-d", displayId.toString(), "keyevent", keyCode.toString())
            Unit
        }

        override fun captureMain(): ParcelFileDescriptor = call {
            val pipe = ParcelFileDescriptor.createPipe()
            Thread({
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val process = ProcessBuilder("/system/bin/screencap", "-p").redirectErrorStream(false).start()
                    process.inputStream.use { it.copyTo(output) }
                    check(process.waitFor() == 0) { "主屏截图失败" }
                }
            }, "main-display-capture").apply { isDaemon = true; start() }
            pipe[0]
        }
    }

    private fun launchOnDisplay(displayId: Int, label: String, packageName: String, component: String): String {
        requireDisplay(displayId)
        val before = DeviceCommands.packageDisplays(
            DeviceCommands.run("/system/bin/dumpsys", "activity", "activities"), packageName
        )
        check(before.all { it == displayId }) { "${label}在其他显示中有任务，请先手动关闭该任务" }
        val result = DeviceCommands.run(
            "/system/bin/am", "start", "-W", "--display", displayId.toString(), "-n", component
        )
        val windows = DeviceCommands.run("/system/bin/dumpsys", "window", "displays")
        val after = DeviceCommands.packageWindowDisplays(windows, packageName)
        check(after == setOf(displayId)) { "${label}未留在目标会话显示，停止后续操作" }
        return result.take(600)
    }

    private fun release() {
        try { display?.release() } finally {
            display = null
            ownedSurface?.release()
            ownedSurface = null
        }
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val SUPPORTED_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) { closed = true; release() }
        AgentLog.i("Root") { "stopped display_released=true" }
    }
}
