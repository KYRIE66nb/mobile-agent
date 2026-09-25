/*
 * Virtual display construction and flags adapted from scrcpy v4.1.
 * Copyright (C) 2018 Genymobile; Copyright (C) 2018-2026 Romain Vimont.
 * Licensed under the Apache License, Version 2.0.
 * See THIRD_PARTY_NOTICES.md and licenses/scrcpy-LICENSE.
 */
package xyz.chouxuewei.mobile_agent.device.root

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Surface

/** 隐藏 API 和显示标志仅放在这里；原型先针对 Android 14 以上实现。 */
internal object DisplayAdapter {
    const val WIDTH = 720
    const val HEIGHT = 1280
    const val DENSITY = 240

    fun create(context: Context, surface: Surface): VirtualDisplay {
        check(Build.VERSION.SDK_INT >= 34) { localizedText("系统原型需要 Android 14 或以上", "This system prototype requires Android 14 or later.") }
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or // SUPPORTS_TOUCH
            (1 shl 8) or // DESTROY_CONTENT_ON_REMOVAL：关闭后不把应用搬回主屏
            (1 shl 10) or // TRUSTED
            (1 shl 11) or // OWN_DISPLAY_GROUP
            (1 shl 13) or // TOUCH_FEEDBACK_DISABLED
            (1 shl 14) or // OWN_FOCUS
            (1 shl 15)   // DEVICE_DISPLAY_GROUP
        if (Build.VERSION.SDK_INT >= 35) flags = flags or (1 shl 16) // STEAL_TOP_FOCUS_DISABLED
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        val manager = constructor.newInstance(context)
        val display = checkNotNull(manager.createVirtualDisplay(
            "MobileAgent", WIDTH, HEIGHT, DENSITY, surface, flags
        )) { localizedText("系统未返回虚拟屏", "The system returned no virtual display.") }
        try {
            // 虚拟屏通过节点写入文字，隐藏该屏 IME，避免回退到主屏弹出键盘。
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "window")
            val windowManager = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            Class.forName("android.view.IWindowManager")
                .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(windowManager, display.display.displayId, 2) // DISPLAY_IME_POLICY_HIDE
            return display
        } catch (error: Exception) {
            display.release()
            throw error
        }
    }
}
