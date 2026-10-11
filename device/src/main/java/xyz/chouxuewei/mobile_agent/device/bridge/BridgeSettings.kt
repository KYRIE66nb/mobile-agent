package xyz.chouxuewei.mobile_agent.device.bridge

import android.content.Context
import java.util.UUID

/**
 * 桌面桥的持久化状态：开关与配对 token 都存在应用私有 SharedPreferences。
 * token 只通过 BridgePairingProvider 发给 adb shell（SHELL_UID），本机其它应用拿不到。
 */
class BridgeSettings(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** 首次读取时生成并持久化；重置配对即重新生成，旧 token 立即失效。 */
    fun pairingToken(): String {
        val existing = preferences.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "")
        preferences.edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    fun resetPairingToken(): String {
        val created = UUID.randomUUID().toString().replace("-", "")
        preferences.edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    fun hasToken(): Boolean = !preferences.getString(KEY_TOKEN, null).isNullOrBlank()

    companion object {
        private const val PREFERENCES = "desktop_bridge"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKEN = "pairing_token"
        const val DEVICE_PORT = 17325
    }
}
