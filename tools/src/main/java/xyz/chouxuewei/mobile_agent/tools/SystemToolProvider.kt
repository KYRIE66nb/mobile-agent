package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

class SystemToolProvider(context: Context, private val device: DeviceGateway? = null) : ToolProvider {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    override val id = "system"
    override val title get() = localizedText("系统功能", "System features")
    override val description get() = localizedText("打开安全的深链接和系统面板，分享文字，读取或调整音量、亮度与屏幕旋转，查询存储占用、清理应用缓存、释放内存。", "Open safe deep links and system panels, share text, read or adjust volume, brightness, and screen rotation, and query storage usage, clear app caches, or free memory.")
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
            localizedText("打开枚举中的系统设置/授权面板，实际开关和授权仍由用户或后续可见设备操作完成。app_details 可携带 package_name 打开指定应用详情页。本工具不能打开设置 App 本体或其它普通应用——打开应用应先用 device_list_apps 查真实包名，再用 device_open。", "Open one of the enumerated system settings/permission panels. The user or a later visible device action must still change settings or grant access. app_details accepts package_name to open a specific app's details page. This tool cannot open the Settings app itself or any regular app — to open an app, look up its real package name with device_list_apps, then use device_open."),
            """{"type":"object","properties":{"panel":{"type":"string","enum":["internet","wifi","bluetooth","location","notifications","notification_listener","accessibility","overlay","write_settings","app_details","date_time","battery_saver","usage_access"]},"package_name":{"type":"string","maxLength":255,"description":localizedText("仅用于 app_details：目标应用真实包名，缺省为本应用", "Only for app_details: real package name of the target app; defaults to this app")}},"required":["panel"],"additionalProperties":false}""",
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
        ToolDefinition(
            "system_storage_stats",
            localizedText("查询存储占用", "Query storage usage"),
            localizedText("查询应用占用的存储空间。不传参数返回本应用占用；传 package_name 查指定应用；传 top 返回占用最大的前 N 个应用。查询其他应用需要用量访问权限，缺失时用 system_open_panel 打开 usage_access 由用户授权后再查。", "Query app storage usage. With no arguments it reports this app; pass package_name for one app, or top for the N largest consumers. Querying other apps requires Usage access; when missing, open the usage_access system panel so the user can grant it, then retry."),
            localizedJsonSchema("""{"type":"object","properties":{"package_name":{"type":"string","maxLength":255,"description":localizedText("可选，目标应用真实包名", "Optional real package name of the target app")},"top":{"type":"integer","minimum":1,"maximum":50,"description":localizedText("可选，返回占用最大的前 N 个应用", "Optional: return the N largest storage consumers")}},"additionalProperties":false}"""),
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取应用存储占用。", "Read app storage usage."),
        ),
        ToolDefinition(
            "system_clear_cache",
            localizedText("清理应用缓存", "Clear app cache"),
            localizedText("清空应用缓存目录以释放存储空间，不删除登录状态和用户数据。不传 package_name 清理本应用；传真实包名清理指定应用，需要已开启 Root；未开启时改用 system_open_panel 打开该应用的 app_details 面板，再用界面操作点清除缓存。", "Empty an app's cache directories to free storage without removing sign-in state or user data. Omit package_name to clean this app; pass a real package name for another app, which requires enabled Root; otherwise open its app_details panel and clear the cache via on-screen actions."),
            localizedJsonSchema("""{"type":"object","properties":{"package_name":{"type":"string","maxLength":255,"description":localizedText("可选，目标应用真实包名；缺省清理本应用", "Optional real package name of the target app; defaults to this app")}},"additionalProperties":false}"""),
            ToolSideEffect.DESTRUCTIVE,
            id,
            approvalDescription = localizedText("清空应用缓存目录。", "Empty the app's cache directories."),
        ),
        ToolDefinition(
            "system_free_memory",
            localizedText("释放内存", "Free memory"),
            localizedText("结束后台应用进程释放运行内存，不影响前台应用。不传 package_name 清理所有可启动应用的后台进程；传真实包名只清理该应用。返回操作前后的可用内存。", "Stop background app processes to free RAM without affecting the foreground app. Omit package_name to stop all launchable apps' background processes; pass a real package name for a single app. Returns available memory before and after."),
            localizedJsonSchema("""{"type":"object","properties":{"package_name":{"type":"string","maxLength":255,"description":localizedText("可选，目标应用真实包名；缺省清理全部可启动应用", "Optional real package name; defaults to all launchable apps")}},"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("结束后台应用进程以释放内存。", "Stop background app processes to free memory."),
        ),
        ToolDefinition(
            "system_shell",
            localizedText("执行受限 shell 命令", "Run a restricted shell command"),
            localizedText("执行一条白名单内的 Android 管理命令，用于查询系统状态、读取应用信息或执行安全的管理动作。通道自动选择：已开 Root 走 Root，否则尝试 Shizuku（会弹出授权框由用户批准）。只允许 am、pm、dumpsys、settings、getprop、input、cmd、wm、content、appops、uiautomator、cat、ls、df、ps、free、top、id、logcat 开头的单条命令；禁止管道、重定向、命令拼接和 shell 元字符。需要遍历应用列表优先用 device_open 的 list_apps，而不是 pm list。不要用它绕过付费、验证码或系统授权确认。", "Run a single allow-listed Android management command to query system state, read app info, or perform safe management actions. Channel is chosen automatically: Root if enabled, otherwise Shizuku (the user approves in a Shizuku prompt). Only commands starting with am, pm, dumpsys, settings, getprop, input, cmd, wm, content, appops, uiautomator, cat, ls, df, ps, free, top, id, or logcat; pipes, redirects, chaining, and shell metacharacters are forbidden. Prefer device_open list_apps over pm list for enumerating apps. Do not use it to bypass payments, verification codes, or system permission confirmations."),
            localizedJsonSchema("""{"type":"object","properties":{"command":{"type":"string","minLength":2,"maxLength":400,"description":localizedText("白名单内的单条命令，例如 dumpsys meminfo 或 pm path com.xxx", "One allow-listed command, e.g. dumpsys meminfo or pm path com.xxx")},"timeout_ms":{"type":"integer","minimum":1000,"maximum":60000,"default":15000}},"required":["command"],"additionalProperties":false}"""),
            ToolSideEffect.DESTRUCTIVE,
            id,
            approvalDescription = localizedText("以 Root 或 Shizuku 身份执行一条受限 shell 命令。", "Run a restricted shell command as Root or Shizuku."),
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
            "system_open_panel" -> openPanel(args)
            "system_share_text" -> shareText(args)
            "system_storage_stats" -> storageStats(args)
            "system_clear_cache" -> clearCache(args)
            "system_free_memory" -> freeMemory(args)
            "system_shell" -> shellCommand(args)
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
            "system_storage_stats" -> localizedText("读取存储占用", "Read storage usage")
            "system_clear_cache" -> localizedText("清空缓存：", "Clear cache: ") +
                (args["package_name"]?.jsonPrimitive?.contentOrNull ?: localizedText("本应用", "this app"))
            "system_free_memory" -> localizedText("结束后台进程：", "Stop background processes: ") +
                (args["package_name"]?.jsonPrimitive?.contentOrNull ?: localizedText("全部可启动应用", "all launchable apps"))
            "system_shell" -> localizedText("执行受限命令：", "Run restricted command: ") +
                (args["command"]?.jsonPrimitive?.contentOrNull?.take(160) ?: "")
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

    private fun openPanel(args: JsonObject): ToolResult {
        val panel = required(args, "panel")
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
            "app_details" -> {
                // 默认打开本应用详情页；带合法 package_name 时打开指定应用详情页，供界面化清理缓存等操作。
                val target = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: appContext.packageName
                require(PACKAGE_PATTERN.matches(target)) { localizedText("应用包名格式无效", "Invalid app package name.") }
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$target"))
            }
            "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "date_time" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "battery_saver" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            else -> error(localizedText(
                "不支持的系统面板：$panel；支持 ${SUPPORTED_PANELS.joinToString("、")}。打开普通应用（含设置 App）请用 device_open",
                "Unsupported system panel: $panel. Supported: ${SUPPORTED_PANELS.joinToString(", ")}. To open a regular app (including the Settings app) use device_open"))
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

    private fun storageStats(args: JsonObject): ToolResult {
        check(Build.VERSION.SDK_INT >= 26) {
            localizedText("查询存储占用需要 Android 8.0 或更高版本", "Storage stats require Android 8.0 or later.")
        }
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val top = args["top"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50)
        if (packageName != null) {
            require(PACKAGE_PATTERN.matches(packageName)) { localizedText("应用包名格式无效", "Invalid app package name.") }
        }
        // 只有查询别的应用才需要用量访问权限；查本应用始终可用。
        if (packageName != null || top != null) checkUsageAccess()
        val manager = checkNotNull(appContext.getSystemService(StorageStatsManager::class.java))
        fun statsJson(pkg: String) = runCatching {
            val info = appContext.packageManager.getApplicationInfo(pkg, 0)
            val stats = manager.queryStatsForPackage(info.storageUuid, pkg, Process.myUserHandle())
            buildJsonObject {
                put("package_name", pkg)
                put("app_name", appContext.packageManager.getApplicationLabel(info).toString())
                put("app_bytes", stats.appBytes)
                put("data_bytes", stats.dataBytes)
                put("cache_bytes", stats.cacheBytes)
                put("total_bytes", stats.appBytes + stats.dataBytes + stats.cacheBytes)
            }
        }.getOrNull()

        return when {
            top != null -> {
                val apps = appContext.packageManager.getInstalledApplications(0)
                    .mapNotNull { statsJson(it.packageName) }
                    .sortedByDescending { it["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L }
                    .take(top)
                ToolResult(buildJsonObject { putJsonArray("apps") { apps.forEach { add(it) } } }.toString(),
                    localizedText("已列出占用最大的 ${apps.size} 个应用", "Listed the ${apps.size} largest apps"))
            }
            else -> {
                val stats = statsJson(packageName ?: appContext.packageName)
                    ?: error(localizedText("目标应用未安装或无法读取", "The target app is not installed or unreadable."))
                ToolResult(stats.toString(), localizedText("已读取存储占用", "Storage usage read"))
            }
        }
    }

    private fun checkUsageAccess() {
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        val allowed = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        } == AppOpsManager.MODE_ALLOWED
        check(allowed) {
            localizedText("查询其他应用的存储占用需要用量访问权限，请用 system_open_panel 打开 usage_access 面板请用户授权后重试", "Reading other apps' storage usage requires Usage access. Open the usage_access system panel for the user to grant it, then retry.")
        }
    }

    private suspend fun clearCache(args: JsonObject): ToolResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        if (packageName == null) {
            var freed = 0L
            val dirs = listOfNotNull(
                appContext.cacheDir,
                appContext.externalCacheDir,
                if (Build.VERSION.SDK_INT >= 21) appContext.codeCacheDir else null,
            )
            for (dir in dirs) {
                dir.listFiles()?.forEach { child ->
                    freed += deepSize(child)
                    child.deleteRecursively()
                }
            }
            return ToolResult(buildJsonObject {
                put("package_name", appContext.packageName)
                put("freed_bytes", freed)
            }.toString(), localizedText("已清理本应用缓存", "This app's cache cleared"))
        }
        require(PACKAGE_PATTERN.matches(packageName)) { localizedText("应用包名格式无效", "Invalid app package name.") }
        val gateway = device ?: error(localizedText("当前环境不支持清理其他应用缓存", "Clearing other apps' caches is not supported here."))
        return when (val result = gateway.clearPackageCache(packageName)) {
            is DeviceResult.Success -> ToolResult(buildJsonObject {
                put("package_name", packageName)
                put("freed_bytes", result.value)
            }.toString(), localizedText("已清理应用缓存", "App cache cleared"))

            is DeviceResult.Unsupported -> error(result.reason + localizedText("；也可以用 system_open_panel 打开该应用的 app_details 页面，再用界面操作点清除缓存", "; alternatively open the app's app_details panel and clear the cache via on-screen actions"))
            is DeviceResult.SessionExpired -> error(result.reason)
            is DeviceResult.Failure -> error(result.reason)
        }
    }

    private fun deepSize(file: java.io.File): Long =
        if (file.isFile) file.length() else file.listFiles()?.sumOf(::deepSize) ?: 0L

    private suspend fun freeMemory(args: JsonObject): ToolResult {
        val manager = checkNotNull(appContext.getSystemService(ActivityManager::class.java))
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val targets = if (packageName != null) {
            require(PACKAGE_PATTERN.matches(packageName)) { localizedText("应用包名格式无效", "Invalid app package name.") }
            listOf(packageName)
        } else {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != appContext.packageName }
        }
        // killBackgroundProcesses 只结束缓存/后台进程，不会杀掉前台或持久运行的系统组件。
        val before = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        targets.forEach { runCatching { manager.killBackgroundProcesses(it) } }
        // 进程回收是异步的，稍等片刻再读可用内存才能得到有意义的差值。
        delay(400)
        val after = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        return ToolResult(buildJsonObject {
            put("target_count", targets.size)
            put("available_before_bytes", before)
            put("available_after_bytes", after)
            put("freed_bytes", after - before)
        }.toString(), localizedText("已清理 ${targets.size} 个应用的后台进程", "Stopped background processes of ${targets.size} apps"))
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

    /** 允许以 shell/root 身份执行的首词命令；其余一律拒绝。 */
    private val shellWhitelist = setOf(
        "am", "pm", "dumpsys", "settings", "getprop", "input", "cmd", "wm",
        "content", "appops", "uiautomator", "cat", "ls", "df", "ps", "free",
        "top", "id", "logcat",
    )

    /** 拒绝 shell 元字符：白名单针对单条命令，绝不允许拼接逃逸。 */
    private val shellMetachars = Regex("[;|&<>`]|\n|\\$\\(|\\$\\{")

    private suspend fun shellCommand(args: JsonObject): ToolResult {
        val command = required(args, "command").trim()
        val timeout = (args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 15_000L).coerceIn(1_000, 60_000)
        require(!shellMetachars.containsMatchIn(command)) {
            localizedText("命令包含不允许的 shell 元字符（; | & > < ` $()），只能执行单条白名单命令", "The command contains forbidden shell metacharacters (; | & > < ` $()); only a single allow-listed command is allowed.")
        }
        val firstToken = command.split(Regex("\\s+")).firstOrNull()?.substringBefore('/')
            ?: error(localizedText("命令为空", "The command is empty."))
        require(firstToken in shellWhitelist) {
            localizedText("命令 $firstToken 不在白名单内", "Command $firstToken is not on the allow-list.")
        }
        val gateway = device
            ?: error(localizedText("设备通道不可用", "The device channel is unavailable."))
        return when (val result = gateway.shell(command)) {
            is DeviceResult.Success -> {
                val out = result.value
                ToolResult(
                    buildJsonObject {
                        put("command", command)
                        put("exit_code", out.exitCode)
                        put("stdout", out.stdout.take(8_000))
                        put("stderr", out.stderr.take(2_000))
                        put("stdout_truncated", out.stdout.length > 8_000)
                    }.toString(),
                    localizedText("命令已执行，退出码 ${out.exitCode}", "Command finished with exit code ${out.exitCode}"),
                )
            }
            is DeviceResult.Unsupported -> error(result.reason)
            is DeviceResult.Failure -> error(result.reason)
            is DeviceResult.SessionExpired -> error(result.reason)
        }
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
        val SUPPORTED_PANELS = listOf(
            "internet", "wifi", "bluetooth", "location", "notifications",
            "notification_listener", "accessibility", "overlay", "write_settings",
            "app_details", "date_time", "battery_saver", "usage_access",
        )
    }
}
