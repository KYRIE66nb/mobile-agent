package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Surface
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

class SystemToolProvider(context: Context) : ToolProvider {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    override val id = "system"
    override val title = "系统功能"
    override val description = "打开安全的深链接和系统面板，分享文字，并读取或调整音量、亮度与屏幕旋转。"
    override val definitions = listOf(
        ToolDefinition(
            "system_get_state",
            "读取系统状态",
            "读取常用音量、亮度、自动旋转状态，以及本应用是否具有修改系统设置权限。",
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = "读取音量和显示设置。",
        ),
        ToolDefinition(
            "system_volume",
            "调整音量",
            "读取、设置或增减指定音频通道音量。level 必须位于 system_get_state 返回的 0..max 范围。",
            """{"type":"object","properties":{"operation":{"type":"string","enum":["get","set","raise","lower","mute","unmute"]},"stream":{"type":"string","enum":["music","ring","alarm","notification","voice"],"default":"music"},"level":{"type":"integer","minimum":0}},"required":["operation"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "读取或调整系统音量。",
        ),
        ToolDefinition(
            "system_display",
            "调整显示设置",
            "读取或调整屏幕亮度、自动旋转及固定方向。修改操作需要用户在系统中授予“修改系统设置”权限；未授权时使用 system_open_panel 打开 write_settings。",
            """{"type":"object","properties":{"operation":{"type":"string","enum":["get","set_brightness","set_auto_rotation","set_rotation"]},"brightness":{"type":"integer","minimum":1,"maximum":255},"enabled":{"type":"boolean"},"degrees":{"type":"integer","enum":[0,90,180,270]}},"required":["operation"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "读取或调整屏幕亮度与旋转。",
        ),
        ToolDefinition(
            "system_open_uri",
            "打开链接或深链接",
            "通过系统解析器打开 http、https、geo、mailto、tel、sms 或已安装应用注册的自定义深链接。禁止 file、content、data、javascript 和 intent URI；可指定真实 package_name 限定目标应用。",
            """{"type":"object","properties":{"uri":{"type":"string","maxLength":4000},"package_name":{"type":"string","maxLength":255}},"required":["uri"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "使用其他应用打开一个链接。",
        ),
        ToolDefinition(
            "system_open_panel",
            "打开系统面板",
            "打开指定系统设置页，实际开关和授权仍由用户或后续可见设备操作完成。",
            """{"type":"object","properties":{"panel":{"type":"string","enum":["internet","wifi","bluetooth","location","notifications","notification_listener","accessibility","overlay","write_settings","app_details","date_time","battery_saver"]}},"required":["panel"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "打开一个系统设置页面。",
        ),
        ToolDefinition(
            "system_share_text",
            "分享文字",
            "打开 Android 系统分享面板发送文字。若指定 package_name，只交给该已安装应用；最终发送对象通常仍需用户选择或确认。",
            """{"type":"object","properties":{"text":{"type":"string","maxLength":20000},"title":{"type":"string","maxLength":200},"package_name":{"type":"string","maxLength":255}},"required":["text"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = "打开系统分享面板并准备分享文字。",
        ),
    )

    override suspend fun availability(): ToolAvailability = if (audio == null) {
        ToolAvailability(ToolAvailabilityState.DEGRADED, "当前设备没有音频服务，其他系统功能仍可使用")
    } else {
        ToolAvailability(ToolAvailabilityState.AVAILABLE)
    }

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "system_get_state" -> stateResult()
            "system_volume" -> volume(args)
            "system_display" -> display(args)
            "system_open_uri" -> openUri(args)
            "system_open_panel" -> openPanel(required(args, "panel"))
            "system_share_text" -> shareText(args)
            else -> error("系统工具不支持 ${call.toolId}")
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "system_get_state" -> "读取音量和显示设置"
            "system_volume" -> "${required(args, "operation")} ${args["stream"]?.jsonPrimitive?.contentOrNull ?: "music"} 音量"
            "system_display" -> "显示设置：${required(args, "operation")}"
            "system_open_uri" -> "打开链接：${safeUriSummary(required(args, "uri"))}"
            "system_open_panel" -> "打开系统面板：${required(args, "panel")}"
            "system_share_text" -> "打开系统分享面板"
            else -> null
        }
    }.getOrNull()

    private fun stateResult(): ToolResult {
        val streams = STREAMS.mapValues { (_, stream) -> audioState(stream) }
        return ToolResult(buildJsonObject {
            put("can_write_settings", Settings.System.canWrite(appContext))
            put("brightness", readSetting(Settings.System.SCREEN_BRIGHTNESS, 128))
            put("auto_rotation", readSetting(Settings.System.ACCELEROMETER_ROTATION, 1) == 1)
            put("rotation_degrees", rotationDegrees(readSetting(Settings.System.USER_ROTATION, Surface.ROTATION_0)))
            putJsonObject("volume") {
                streams.forEach { (name, state) -> putJsonObject(name) {
                    put("level", state.first)
                    put("max", state.second)
                    put("muted", state.third)
                } }
            }
        }.toString(), "已读取系统音量和显示设置")
    }

    private fun volume(args: JsonObject): ToolResult {
        val manager = checkNotNull(audio) { "当前设备没有音频服务" }
        val streamName = args["stream"]?.jsonPrimitive?.contentOrNull ?: "music"
        val stream = STREAMS[streamName] ?: error("不支持的音频通道")
        when (required(args, "operation")) {
            "get" -> Unit
            "set" -> {
                val level = args["level"]?.jsonPrimitive?.intOrNull ?: error("set 操作缺少 level")
                require(level in 0..manager.getStreamMaxVolume(stream)) { "音量超出当前通道范围" }
                manager.setStreamVolume(stream, level, 0)
            }
            "raise" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            "lower" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
            "mute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            else -> error("不支持的音量操作")
        }
        val state = audioState(stream)
        return ToolResult(buildJsonObject {
            put("stream", streamName)
            put("level", state.first)
            put("max", state.second)
            put("muted", state.third)
        }.toString(), "${streamName} 音量为 ${state.first}/${state.second}")
    }

    private fun display(args: JsonObject): ToolResult {
        val operation = required(args, "operation")
        if (operation != "get") {
            check(Settings.System.canWrite(appContext)) {
                "尚未授予修改系统设置权限，请先打开 write_settings 系统面板"
            }
        }
        when (operation) {
            "get" -> Unit
            "set_brightness" -> {
                val value = args["brightness"]?.jsonPrimitive?.intOrNull ?: error("缺少参数 brightness")
                require(value in 1..255) { "亮度必须在 1 到 255 之间" }
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                check(Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)) {
                    "系统拒绝修改亮度"
                }
            }
            "set_auto_rotation" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: error("缺少参数 enabled")
                check(Settings.System.putInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                    if (enabled) 1 else 0)) { "系统拒绝修改自动旋转" }
            }
            "set_rotation" -> {
                val degrees = args["degrees"]?.jsonPrimitive?.intOrNull ?: error("缺少参数 degrees")
                val rotation = when (degrees) {
                    0 -> Surface.ROTATION_0
                    90 -> Surface.ROTATION_90
                    180 -> Surface.ROTATION_180
                    270 -> Surface.ROTATION_270
                    else -> error("旋转角度必须为 0、90、180 或 270")
                }
                Settings.System.putInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                check(Settings.System.putInt(appContext.contentResolver, Settings.System.USER_ROTATION, rotation)) {
                    "系统拒绝修改屏幕方向"
                }
            }
            else -> error("不支持的显示设置操作")
        }
        return stateResult().copy(summary = if (operation == "get") "已读取显示设置" else "显示设置已更新")
    }

    private fun openUri(args: JsonObject): ToolResult {
        val raw = required(args, "uri")
        require(raw.length <= 4_000) { "链接不能超过 4000 字符" }
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase() ?: error("链接缺少 URI scheme")
        require(scheme !in BLOCKED_SCHEMES) { "出于安全原因，不允许打开 $scheme URI" }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { packageName ->
            require(PACKAGE_PATTERN.matches(packageName)) { "应用包名格式无效" }
            intent.setPackage(packageName)
        }
        startResolved(intent)
        return ToolResult(buildJsonObject {
            put("opened", true)
            put("scheme", scheme)
        }.toString(), "已打开链接")
    }

    private fun openPanel(panel: String): ToolResult {
        val intent = when (panel) {
            "internet" -> if (Build.VERSION.SDK_INT >= 29) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "notifications" -> when {
                Build.VERSION.SDK_INT >= 33 -> Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS)
                Build.VERSION.SDK_INT >= 26 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                else -> Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${appContext.packageName}"),
                )
            }
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appContext.packageName}"))
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))
            "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
            "date_time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "battery_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            else -> error("不支持的系统面板")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startResolved(intent)
        return ToolResult(buildJsonObject { put("opened", true); put("panel", panel) }.toString(), "已打开系统面板")
    }

    private fun shareText(args: JsonObject): ToolResult {
        val text = required(args, "text")
        require(text.length <= 20_000) { "分享文字不能超过 20000 字符" }
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.take(200).orEmpty().ifBlank { "分享文字" }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { packageName ->
            require(PACKAGE_PATTERN.matches(packageName)) { "应用包名格式无效" }
            send.setPackage(packageName)
        }
        check(appContext.packageManager.resolveActivity(send, 0) != null) { "没有应用可以接收这次分享" }
        appContext.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("""{"opened":true,"length":${text.length}}""", "已打开系统分享面板")
    }

    private fun startResolved(intent: Intent) {
        check(appContext.packageManager.resolveActivity(intent, 0) != null) { "系统中没有应用可以处理该操作" }
        appContext.startActivity(intent)
    }

    /** 授权面板不显示查询参数、片段或用户信息，避免深链接中的临时令牌出现在其它界面。 */
    private fun safeUriSummary(raw: String): String {
        val uri = Uri.parse(raw)
        val scheme = uri.scheme.orEmpty()
        val authority = uri.host?.let { host ->
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            "//$host$port"
        }.orEmpty()
        return "$scheme:$authority${uri.path.orEmpty()}".take(160)
    }

    private fun audioState(stream: Int): Triple<Int, Int, Boolean> {
        val manager = audio ?: return Triple(0, 0, false)
        return Triple(manager.getStreamVolume(stream), manager.getStreamMaxVolume(stream), manager.isStreamMute(stream))
    }

    private fun readSetting(name: String, fallback: Int): Int =
        runCatching { Settings.System.getInt(appContext.contentResolver, name) }.getOrDefault(fallback)

    private fun rotationDegrees(rotation: Int) = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun required(args: JsonObject, name: String) =
        args[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: error("缺少参数 $name")

    private companion object {
        val STREAMS = linkedMapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "voice" to AudioManager.STREAM_VOICE_CALL,
        )
        val BLOCKED_SCHEMES = setOf("file", "content", "data", "javascript", "intent")
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
