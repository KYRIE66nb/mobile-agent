package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
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
    override val title get() = localizedText("系统功能", "System features")
    override val description get() = localizedText("打开安全的深链接和系统面板，分享文字，并读取或调整音量、亮度与屏幕旋转。", "Open safe deep links and system panels, share text, and read or adjust volume, brightness, and screen rotation.")
    override val definitions get() = listOf(
        ToolDefinition(
            "system_get_state",
            localizedText("读取系统状态", "Read system state"),
            localizedText("读取常用音量、亮度、自动旋转状态，以及本应用是否具有修改系统设置权限。", "Read common volume levels, brightness, auto-rotate state, and whether the app can modify system settings."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取音量和显示设置。", "Read volume and display settings."),
        ),
        ToolDefinition(
            "system_volume",
            localizedText("调整音量", "Adjust volume"),
            localizedText("读取、设置或增减指定音频通道音量。level 必须位于 system_get_state 返回的 0..max 范围。", "Read, set, increase, or decrease an audio stream. level must be within the 0..max range returned by system_get_state."),
            """{"type":"object","properties":{"operation":{"type":"string","enum":["get","set","raise","lower","mute","unmute"]},"stream":{"type":"string","enum":["music","ring","alarm","notification","voice"],"default":"music"},"level":{"type":"integer","minimum":0}},"required":["operation"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("读取或调整系统音量。", "Read or adjust system volume."),
        ),
        ToolDefinition(
            "system_display",
            localizedText("调整显示设置", "Adjust display settings"),
            localizedText("读取或调整屏幕亮度、自动旋转及固定方向。修改操作需要用户在系统中授予“修改系统设置”权限；未授权时使用 system_open_panel 打开 write_settings。", "Read or adjust screen brightness, auto-rotate, and fixed orientation. Changes require Modify system settings access; when unavailable, use system_open_panel with write_settings."),
            """{"type":"object","properties":{"operation":{"type":"string","enum":["get","set_brightness","set_auto_rotation","set_rotation"]},"brightness":{"type":"integer","minimum":1,"maximum":255},"enabled":{"type":"boolean"},"degrees":{"type":"integer","enum":[0,90,180,270]}},"required":["operation"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("读取或调整屏幕亮度与旋转。", "Read or adjust screen brightness and rotation."),
        ),
        ToolDefinition(
            "system_open_uri",
            localizedText("打开链接或深链接", "Open link or deep link"),
            localizedText("通过系统解析器打开 http、https、geo、mailto、tel、sms 或已安装应用注册的自定义深链接。禁止 file、content、data、javascript 和 intent URI；可指定真实 package_name 限定目标应用。", "Open http, https, geo, mailto, tel, sms, or an installed app custom deep link through the system resolver. file, content, data, javascript, and intent URIs are forbidden; a real package_name may restrict the target app."),
            """{"type":"object","properties":{"uri":{"type":"string","maxLength":4000},"package_name":{"type":"string","maxLength":255}},"required":["uri"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("使用其他应用打开一个链接。", "Open a link in another app."),
        ),
        ToolDefinition(
            "system_open_panel",
            localizedText("打开系统面板", "Open system panel"),
            localizedText("打开指定系统设置页，实际开关和授权仍由用户或后续可见设备操作完成。", "Open a specified system settings page. The user or a later visible device action must still change settings or grant access."),
            """{"type":"object","properties":{"panel":{"type":"string","enum":["internet","wifi","bluetooth","location","notifications","notification_listener","accessibility","overlay","write_settings","app_details","date_time","battery_saver"]}},"required":["panel"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("打开一个系统设置页面。", "Open a system settings page."),
        ),
        ToolDefinition(
            "system_share_text",
            localizedText("分享文字", "Share text"),
            localizedText("打开 Android 系统分享面板发送文字。若指定 package_name，只交给该已安装应用；最终发送对象通常仍需用户选择或确认。", "Open the Android share sheet to send text. If package_name is specified, pass it only to that installed app; the user usually still chooses or confirms the recipient."),
            """{"type":"object","properties":{"text":{"type":"string","maxLength":20000},"title":{"type":"string","maxLength":200},"package_name":{"type":"string","maxLength":255}},"required":["text"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("打开系统分享面板并准备分享文字。", "Open the system share sheet and prepare text to share."),
        ),
    )

    override suspend fun availability(): ToolAvailability = if (audio == null) {
        ToolAvailability(ToolAvailabilityState.DEGRADED, localizedText("当前设备没有音频服务，其他系统功能仍可使用", "The audio service is unavailable on this device; other system features remain available."))
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
            else -> error(localizedText("系统工具不支持 ${call.toolId}", "System tools do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "system_get_state" -> localizedText("读取音量和显示设置", "Read volume and display settings")
            "system_volume" -> "${required(args, "operation")} ${args["stream"]?.jsonPrimitive?.contentOrNull ?: "music"} " +
                localizedText("音量", "volume")
            "system_display" -> localizedText("显示设置：", "Display settings: ") + required(args, "operation")
            "system_open_uri" -> localizedText("打开链接：", "Open link: ") + safeUriSummary(required(args, "uri"))
            "system_open_panel" -> localizedText("打开系统面板：", "Open system panel: ") + required(args, "panel")
            "system_share_text" -> localizedText("打开系统分享面板", "Open system share sheet")
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
        }.toString(), localizedText("已读取系统音量和显示设置", "System volume and display settings read"))
    }

    private fun volume(args: JsonObject): ToolResult {
        val manager = checkNotNull(audio) { localizedText("当前设备没有音频服务", "The audio service is unavailable on this device.") }
        val streamName = args["stream"]?.jsonPrimitive?.contentOrNull ?: "music"
        val stream = STREAMS[streamName] ?: error(localizedText("不支持的音频通道", "Unsupported audio stream"))
        when (required(args, "operation")) {
            "get" -> Unit
            "set" -> {
                val level = args["level"]?.jsonPrimitive?.intOrNull ?: error(localizedText("set 操作缺少 level", "The set action is missing level."))
                require(level in 0..manager.getStreamMaxVolume(stream)) { localizedText("音量超出当前通道范围", "Volume is outside the range for this stream.") }
                manager.setStreamVolume(stream, level, 0)
            }
            "raise" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            "lower" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
            "mute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            else -> error(localizedText("不支持的音量操作", "Unsupported volume action"))
        }
        val state = audioState(stream)
        return ToolResult(buildJsonObject {
            put("stream", streamName)
            put("level", state.first)
            put("max", state.second)
            put("muted", state.third)
        }.toString(), localizedText("${streamName} 音量为 ${state.first}/${state.second}", "${streamName} volume is ${state.first}/${state.second}"))
    }

    private fun display(args: JsonObject): ToolResult {
        val operation = required(args, "operation")
        if (operation != "get") {
            check(Settings.System.canWrite(appContext)) {
                localizedText("尚未授予修改系统设置权限，请先打开 write_settings 系统面板", "Modify system settings access is not granted. Open the write_settings system panel first.")
            }
        }
        when (operation) {
            "get" -> Unit
            "set_brightness" -> {
                val value = args["brightness"]?.jsonPrimitive?.intOrNull ?: error(localizedText("缺少参数 brightness", "Missing parameter: brightness"))
                require(value in 1..255) { localizedText("亮度必须在 1 到 255 之间", "Brightness must be between 1 and 255.") }
                Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                check(Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)) {
                    localizedText("系统拒绝修改亮度", "The system rejected the brightness change.")
                }
            }
            "set_auto_rotation" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull ?: error(localizedText("缺少参数 enabled", "Missing parameter: enabled"))
                check(Settings.System.putInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                    if (enabled) 1 else 0)) { localizedText("系统拒绝修改自动旋转", "The system rejected the auto-rotate change.") }
            }
            "set_rotation" -> {
                val degrees = args["degrees"]?.jsonPrimitive?.intOrNull ?: error(localizedText("缺少参数 degrees", "Missing parameter: degrees"))
                val rotation = when (degrees) {
                    0 -> Surface.ROTATION_0
                    90 -> Surface.ROTATION_90
                    180 -> Surface.ROTATION_180
                    270 -> Surface.ROTATION_270
                    else -> error(localizedText("旋转角度必须为 0、90、180 或 270", "Rotation must be 0, 90, 180, or 270."))
                }
                Settings.System.putInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                check(Settings.System.putInt(appContext.contentResolver, Settings.System.USER_ROTATION, rotation)) {
                    localizedText("系统拒绝修改屏幕方向", "The system rejected the screen orientation change.")
                }
            }
            else -> error(localizedText("不支持的显示设置操作", "Unsupported display settings action"))
        }
        return stateResult().copy(summary = if (operation == "get") localizedText("已读取显示设置", "Display settings read") else localizedText("显示设置已更新", "Display settings updated"))
    }

    private fun openUri(args: JsonObject): ToolResult {
        val raw = required(args, "uri")
        require(raw.length <= 4_000) { localizedText("链接不能超过 4000 字符", "A link cannot exceed 4000 characters.") }
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase() ?: error(localizedText("链接缺少 URI scheme", "The link is missing a URI scheme."))
        require(scheme !in BLOCKED_SCHEMES) { localizedText("出于安全原因，不允许打开 $scheme URI", "For security, $scheme URIs cannot be opened.") }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { packageName ->
            require(PACKAGE_PATTERN.matches(packageName)) { localizedText("应用包名格式无效", "Invalid app package name.") }
            intent.setPackage(packageName)
        }
        startResolved(intent)
        return ToolResult(buildJsonObject {
            put("opened", true)
            put("scheme", scheme)
        }.toString(), localizedText("已打开链接", "Link opened"))
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
            else -> error(localizedText("不支持的系统面板", "Unsupported system panel"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startResolved(intent)
        return ToolResult(buildJsonObject { put("opened", true); put("panel", panel) }.toString(), localizedText("已打开系统面板", "System panel opened"))
    }

    private fun shareText(args: JsonObject): ToolResult {
        val text = required(args, "text")
        require(text.length <= 20_000) { localizedText("分享文字不能超过 20000 字符", "Shared text cannot exceed 20000 characters.") }
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.take(200).orEmpty().ifBlank { localizedText("分享文字", "Share text") }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { packageName ->
            require(PACKAGE_PATTERN.matches(packageName)) { localizedText("应用包名格式无效", "Invalid app package name.") }
            send.setPackage(packageName)
        }
        check(appContext.packageManager.resolveActivity(send, 0) != null) { localizedText("没有应用可以接收这次分享", "No app can receive this share.") }
        appContext.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("""{"opened":true,"length":${text.length}}""", localizedText("已打开系统分享面板", "System share sheet opened"))
    }

    private fun startResolved(intent: Intent) {
        check(appContext.packageManager.resolveActivity(intent, 0) != null) { localizedText("系统中没有应用可以处理该操作", "No installed app can handle this action.") }
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
        args[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: error(localizedText("缺少参数 $name", "Missing parameter: $name"))

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
