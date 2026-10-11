package xyz.chouxuewei.mobile_agent.device.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService

/**
 * 桌面端 `adb shell content call` 的配对入口。
 * 只有 SHELL_UID（adb shell）能拿到 pairing token；手机上其它应用调用一律 SecurityException。
 * authority 由 manifest 的 ${applicationId}.bridge 提供。
 */
class BridgePairingProvider : ContentProvider() {
    private lateinit var settings: BridgeSettings

    override fun onCreate(): Boolean {
        settings = BridgeSettings(requireNotNull(context))
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw SecurityException("desktop bridge pairing is restricted to adb shell")
        }
        return when (method) {
            METHOD_PAIRING_TOKEN -> Bundle().apply {
                putString("token", settings.pairingToken())
            }
            METHOD_STATUS -> Bundle().apply {
                putBoolean("enabled", settings.enabled)
                putBoolean("accessibility", AgentAccessibilityService.connected != null)
                putInt("device_port", BridgeSettings.DEVICE_PORT)
            }
            else -> Bundle.EMPTY
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_PAIRING_TOKEN = "pairing_token"
        const val METHOD_STATUS = "bridge_status"
    }
}
