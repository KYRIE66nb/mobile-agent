package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import xyz.chouxuewei.mobile_agent.core.*

/** 模式在工具协议中必填；缺失或异常时仅做防御性回退，避免意外触碰用户当前主屏。 */
internal fun resolveDeviceExecutionMode(args: JsonObject): ExecutionMode =
    if (args["mode"]?.jsonPrimitive?.contentOrNull == "main") {
        ExecutionMode.MAIN_DISPLAY
    } else {
        ExecutionMode.VIRTUAL_DISPLAY
    }

class DeviceToolProvider(private val gateway: DeviceGateway) : ToolProvider {
    private val sessionGate = Mutex()
    private val sessionsByRun = mutableMapOf<String, MutableSet<String>>()
    override val id = "device"
    override val title get() = localizedText("手机操作", "Phone operation")
    override val description
        get() = localizedText("经你授权后，可把临时界面截图和节点信息交给当前模型分析，并操作手机。需要 Root 权限。", "After your approval, provide temporary screen images and node information to the current model and operate the phone. Root access is required.")

    override suspend fun availability(): ToolAvailability =
        when (val result = gateway.availability()) {
            is DeviceResult.Success -> ToolAvailability(ToolAvailabilityState.AVAILABLE)
            is DeviceResult.Unsupported -> ToolAvailability(
                ToolAvailabilityState.UNSUPPORTED,
                result.reason
            )

            is DeviceResult.SessionExpired -> ToolAvailability(
                ToolAvailabilityState.DEGRADED,
                result.reason
            )

            is DeviceResult.Failure -> ToolAvailability(
                ToolAvailabilityState.NEEDS_SETUP,
                result.reason
            )
        }

    override val definitions get() = listOf(
        ToolDefinition(
            "device_list_apps", localizedText("获取本机应用列表", "Get installed app list"),
            localizedText("列出本机具有启动入口的应用名称和真实 package_name。打开应用前必须使用本工具返回的包名，不能翻译或猜测；带 query 返回 0 条时可再调用一次且不传 query。", "List app names and real package_name values for apps with launcher entries. Before opening an app, use the package name returned here without translating or guessing. If a query returns no result, call once more without query."),
            localizedJsonSchema("""{"type":"object","properties":{"query":{"type":"string","maxLength":200,"description":localizedText("可选，按应用名称或包名筛选；不确定系统显示名称时省略", "Optional filter by app name or package name; omit when the system display name is uncertain")},"limit":{"type":"integer","minimum":1,"maximum":500,"default":200}},"additionalProperties":false}"""),
            ToolSideEffect.READ, "device",
            approvalDescription = localizedText("获取本机可以打开的应用列表。", "Get the list of apps that can be launched on this device."),
        ),
        ToolDefinition(
            "device_open",
            localizedText("开始手机操作", "Start phone operation"),
            localizedText("开启当前执行轮次专用的设备会话。mode 必须根据用户要求和任务目标选择：用户明确指定前台或后台时直接照做；未指定时，打开或切换应用、展示页面，以及需要用户看到当前屏幕结果的任务使用 main；能够在隔离屏独立完成且不应打扰当前屏幕的任务使用 virtual。不得为了选择前台或后台询问用户；后续只使用本次返回的 session_id。", "Open a device session dedicated to the current run. Choose mode from the user request and task goal: follow explicit foreground or background requests; otherwise use main for opening or switching apps, presenting pages, or results the user must see, and virtual for independent work on an isolated display that should not interrupt the current screen. Do not ask the user merely to choose foreground or background. Use only the returned session_id afterward."),
            localizedJsonSchema("""{"type":"object","properties":{"mode":{"type":"string","enum":["main","virtual"],"description":localizedText("根据用户要求和任务目标选择。打开或切换应用、展示页面、需要用户看到屏幕结果时使用 main；可在隔离屏独立完成且不应打扰当前屏幕时使用 virtual", "Choose from the user request and task goal. Use main when opening or switching apps, presenting a page, or when the user must see the result. Use virtual when the work can finish independently on an isolated display without interrupting the current screen.")}},"required":["mode"],"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE,
            "device",
            approvalDescription = localizedText("开始一次手机操作。执行方式由用户要求和任务目标确定。", "Start a phone operation. The user request and task goal determine the execution mode."),
        ),
        ToolDefinition(
            "device_observe",
            localizedText("识别手机界面", "Inspect phone screen"),
            localizedText("读取当前设备会话的前台应用、截图、坐标范围和可访问节点，并返回一次性 observation_id。节点和截图只对紧接着的一次模型决策有效，随后自动清除；继续操作必须重新识别。可用 query 或 required_action 筛选复杂界面；nodes_truncated=true 时缩小条件重新识别。", "Read the foreground app, screenshot, coordinate range, and accessible nodes for the current device session, returning a one-time observation_id. Nodes and screenshots are valid only for the immediately following model decision and are then cleared; inspect again before continuing. Use query or required_action to filter complex screens, and narrow the filter when nodes_truncated=true."),
            localizedJsonSchema("""{"type":"object","properties":{"session_id":{"type":"string","description":localizedText("当前执行轮次中 device_open 返回的真实会话 ID", "Actual session ID returned by device_open in the current run")},"query":{"type":"string","maxLength":200,"description":localizedText("可选，筛选文字、描述、提示、view_id 或类名", "Optional filter for text, description, hint, view_id, or class name")},"required_action":{"type":"string","enum":["click","long_click","scroll_forward","scroll_backward","scroll_up","scroll_down","scroll_left","scroll_right","set_text"]},"limit":{"type":"integer","minimum":1,"maximum":200,"default":80}},"required":["session_id"],"additionalProperties":false}"""),
            ToolSideEffect.READ,
            "device",
            approvalDescription = localizedText("识别当前界面，并把临时截图和节点信息交给当前模型分析。", "Inspect the current screen and provide the temporary screenshot and node information to the current model."),
            resultLifetime = ToolResultLifetime.SINGLE_MODEL_STEP,
        ),
        ToolDefinition(
            "device_action", localizedText("操作手机", "Phone control"),
            localizedText("每次只执行一个动作。优先使用 click_node、long_click_node、scroll_node 等节点语义动作；节点不支持时才使用坐标。open_app 可省略 observation_id；其他动作必须原样使用最近一次 device_observe 返回的准确 ID。动作成功后该识别结果立即失效，继续操作前必须重新识别。", "Run exactly one action. Prefer semantic node actions such as click_node, long_click_node, and scroll_node; use coordinates only when the node does not support the action. open_app may omit observation_id; every other action must copy the exact ID from the latest device_observe. A successful action immediately expires that observation, so inspect again before continuing."),
            localizedJsonSchema("""{"type":"object","properties":{"session_id":{"type":"string","description":localizedText("当前执行轮次中 device_open 返回的真实会话 ID", "Actual session ID returned by device_open in the current run")},"observation_id":{"type":"string","description":localizedText("open_app 可省略；其他动作必须使用最近一次 device_observe 返回的一次性准确 ID", "open_app may omit this; every other action must use the one-time exact ID from the latest device_observe")},"action":{"type":"string","enum":["click_node","long_click_node","scroll_node","tap","long_press","swipe","input_text","back","enter","home","recents","escape","delete","tab","dpad_up","dpad_down","dpad_left","dpad_right","open_app","wait"]},"x":{"type":"integer","description":localizedText("点击或滑动起点横坐标，来自当前识别结果", "Tap or swipe start X coordinate from the current observation")},"y":{"type":"integer","description":localizedText("点击或滑动起点纵坐标，来自当前识别结果", "Tap or swipe start Y coordinate from the current observation")},"end_x":{"type":"integer","description":localizedText("滑动终点横坐标", "Swipe end X coordinate")},"end_y":{"type":"integer","description":localizedText("滑动终点纵坐标", "Swipe end Y coordinate")},"duration_ms":{"type":"integer"},"text":{"type":"string","maxLength":500,"description":localizedText("仅用于 input_text；replace 模式传空字符串可清空输入框", "Used only for input_text; an empty string in replace mode clears the field")},"input_mode":{"type":"string","enum":["replace","append"],"default":"replace"},"node_ref":{"type":"string","description":localizedText("节点语义动作必须提供；input_text 可选；值必须来自当前识别结果", "Node semantic action is required; input_text is optional; values must come from the current observation")},"scroll_direction":{"type":"string","enum":["forward","backward","up","down","left","right"],"description":localizedText("仅用于 scroll_node", "Used only for scroll_node")},"package_name":{"type":"string","description":localizedText("仅用于 open_app，必须原样复制 device_list_apps 返回的 package_name", "Used only for open_app; copy package_name exactly from device_list_apps")}},"required":["session_id","action"],"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE, "device",
            approvalDescription = localizedText("在手机上执行一次操作。", "Run one action on the phone."),
        ),
        ToolDefinition(
            "device_batch", localizedText("批量操作手机", "Batch phone operations"),
            localizedText("在手机本地连续执行 1 到 20 个步骤，减少每次点击后都请求模型。每步会轻量识别当前界面，并用 target_text、target_description 或 target_view_id 重新定位节点；不得为后续步骤复用旧 node_ref。任一步不匹配、跳到错误应用或执行失败时立即停止，并返回最新界面。坐标动作只能用在确认不会变化的当前布局。不要把支付、验证码、账号安全或系统授权确认放入批量执行。", "Execute 1 to 20 steps locally on the phone to avoid a model request after every tap. Each step performs a lightweight screen inspection and relocates nodes with target_text, target_description, or target_view_id; never reuse an old node_ref for later steps. Stop immediately and return the latest screen if a step does not match, reaches the wrong app, or fails. Use coordinate actions only on a confirmed stable layout. Never batch payments, verification codes, account security, or system permission confirmations."),
            BATCH_SCHEMA,
            ToolSideEffect.EXTERNAL_WRITE, "device",
            approvalDescription = localizedText("在手机上连续执行一组已列出的操作，失败时立即停止。", "Execute a listed sequence of phone actions and stop immediately on failure."),
            resultLifetime = ToolResultLifetime.SINGLE_MODEL_STEP,
        ),
        ToolDefinition(
            "device_gesture", localizedText("执行复杂触控", "Run complex touch gesture"),
            localizedText("在最近一次识别结果的坐标系中执行一组轨迹。单条多点轨迹可在画板连续画线；多条 start_time_ms 相同的轨迹会并行执行，可用于双指缩放。一次最多 10 条轨迹、共 500 个点、总时长 10 秒。", "Execute gesture paths in the coordinate system of the latest observation. A multi-point path can draw continuously; paths with the same start_time_ms run in parallel for gestures such as pinch zoom. Up to 10 paths, 500 points total, and 10 seconds."),
            localizedJsonSchema("""{"type":"object","properties":{"session_id":{"type":"string"},"observation_id":{"type":"string","description":localizedText("最近一次 device_observe 返回的准确 ID", "Exact ID returned by the latest device_observe")},"strokes":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","properties":{"points":{"type":"array","minItems":2,"maxItems":500,"items":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"],"additionalProperties":false}},"start_time_ms":{"type":"integer","minimum":0,"maximum":10000,"default":0},"duration_ms":{"type":"integer","minimum":1,"maximum":10000}},"required":["points","duration_ms"],"additionalProperties":false}}},"required":["session_id","observation_id","strokes"],"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE, "device",
            approvalDescription = localizedText("在手机上执行连续轨迹或多指手势。", "Run continuous paths or multi-touch gestures on the phone."),
        ),
        ToolDefinition(
            "device_wait_for", localizedText("等待界面状态", "Wait for screen state"),
            localizedText("在设备会话中轮询识别，直到指定文字、节点、应用出现或消失，并返回仅供下一步使用的最新 observation_id 和截图。适合加载、跳转和弹窗等待，不能用来无限等待。", "Poll screen observations in the device session until specified text, node, or app appears or disappears, returning the latest observation_id and screenshot for the next step only. Use for loading, navigation, and dialogs, not unbounded waits."),
            localizedJsonSchema("""{"type":"object","properties":{"session_id":{"type":"string"},"state":{"type":"string","enum":["present","absent"],"default":"present"},"text":{"type":"string","maxLength":200,"description":localizedText("匹配节点文字、描述或提示文字，忽略大小写", "Match node text, description, or hint, ignoring case")},"node_ref":{"type":"string","description":localizedText("匹配先前识别结果中的节点引用", "Match a node reference from the prior observation")},"package_name":{"type":"string","description":localizedText("匹配当前前台应用包名", "Match current foreground app package name")},"timeout_ms":{"type":"integer","minimum":0,"maximum":10000,"default":5000},"interval_ms":{"type":"integer","minimum":200,"maximum":2000,"default":500}},"required":["session_id"],"additionalProperties":false}"""),
            ToolSideEffect.READ, "device",
            approvalDescription = localizedText("等待手机界面达到指定状态，并把最终临时截图交给当前模型分析。", "Wait for the phone screen to reach a specified state and provide the final temporary screenshot to the current model."),
            resultLifetime = ToolResultLifetime.SINGLE_MODEL_STEP,
        ),
        ToolDefinition(
            "device_close",
            localizedText("结束手机操作", "End phone operation"),
            localizedText("结束当前执行轮次中 device_open 创建的设备会话并释放资源。只能使用本轮返回的 session_id；正常结束时运行时也会自动清理。", "End the device session created by device_open in the current run and release resources. Use only the session_id returned in this run; the runtime also cleans it up after normal completion."),
            localizedJsonSchema("""{"type":"object","properties":{"session_id":{"type":"string","description":localizedText("当前执行轮次中 device_open 返回的真实会话 ID", "Actual session ID returned by device_open in the current run")}},"required":["session_id"],"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE,
            "device",
            approvalDescription = localizedText("结束本次手机操作。", "End this phone operation."),
        ),
    )

    override suspend fun execute(
        call: RequestedToolCall,
        context: ToolExecutionContext
    ): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "device_list_apps" -> listApps(args)
            "device_open" -> open(args, context)
            "device_observe" -> observe(args, context)
            "device_action" -> action(args, context)
            "device_batch" -> batch(args, context)
            "device_gesture" -> gesture(args, context)
            "device_wait_for" -> waitFor(args, context)
            "device_close" -> close(args, context)
            else -> error(localizedText("设备工具不支持 ${call.toolId}", "Device tools do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "device_list_apps" -> args["query"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotBlank)?.take(80)?.let { localizedText("查找应用：$it", "Find app: $it") }
                ?: localizedText("获取可打开的应用列表", "Get launchable app list")

            "device_action" -> when (args["action"]?.jsonPrimitive?.contentOrNull) {
                "open_app" -> args["package_name"]?.jsonPrimitive?.contentOrNull
                    ?.take(120)?.let { localizedText("打开应用：$it", "Open app: $it") }

                "click_node" -> localizedText("点击界面元素", "Tap screen element")
                "long_click_node" -> localizedText("长按界面元素", "Long-press screen element")
                "scroll_node" -> localizedText("滚动界面元素", "Scroll screen element")
                "tap" -> localizedText("点击当前界面", "Tap current screen")
                "long_press" -> localizedText("长按当前界面", "Long-press current screen")
                "swipe" -> localizedText("滑动当前界面", "Swipe current screen")
                "input_text" -> localizedText("向当前输入框填写文字", "Enter text into the current field")
                "back" -> localizedText("返回上一页", "Go back")
                "enter" -> localizedText("执行确认操作", "Run confirmation action")
                "home" -> localizedText("返回系统桌面", "Go home")
                "recents" -> localizedText("打开最近任务", "Open recent apps")
                "wait" -> localizedText("等待界面完成响应", "Wait for the screen to finish responding")
                else -> localizedText("操作当前手机界面", "Operate the current phone screen")
            }

            "device_batch" -> batchApprovalSummary(args)
            "device_open" -> if (resolveDeviceExecutionMode(args) == ExecutionMode.MAIN_DISPLAY) {
                localizedText("以前台主屏方式开始手机操作", "Start phone operation on the foreground main display")
            } else {
                localizedText("以后台隔离方式开始手机操作", "Start phone operation on an isolated background display")
            }
            "device_observe" -> args["query"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)?.take(80)?.let { localizedText("识别当前界面中的：$it", "Inspect on the current screen: $it") }
                ?: localizedText("识别当前手机界面", "Inspect current phone screen")

            "device_gesture" -> localizedText("执行连续轨迹或多指手势", "Run continuous or multi-touch gesture")
            "device_wait_for" -> localizedText("等待指定界面状态", "Wait for specified screen state")
            "device_close" -> localizedText("结束手机操作", "End phone operation")
            else -> null
        }
    }.getOrNull()

    private suspend fun listApps(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(query.length <= 200) { localizedText("应用筛选内容不能超过 200 个字符", "The app filter cannot exceed 200 characters.") }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 500)
        return when (val result = gateway.listApps()) {
            is DeviceResult.Success -> {
                val matches = result.value.filter { app ->
                    query.isBlank() || app.label.contains(query, ignoreCase = true) ||
                            app.packageName.contains(query, ignoreCase = true)
                }
                val returned = matches.take(limit)
                ToolResult(
                    buildJsonObject {
                        put("total", matches.size)
                        put("returned", returned.size)
                        put("truncated", returned.size < matches.size)
                        putJsonArray("apps") {
                            returned.forEach { app ->
                                add(buildJsonObject {
                                    put("name", app.label)
                                    put("package_name", app.packageName)
                                })
                            }
                        }
                    }.toString(), if (query.isBlank()) {
                        localizedText("找到 ${matches.size} 个可启动应用", "Found ${matches.size} launchable apps")
                    } else {
                        localizedText("找到 ${matches.size} 个匹配应用", "Found ${matches.size} matching apps")
                    }
                )
            }

            is DeviceResult.Unsupported -> failed(result.reason)
            is DeviceResult.SessionExpired -> failed(result.reason)
            is DeviceResult.Failure -> failed(result.reason)
        }
    }

    private suspend fun open(args: JsonObject, context: ToolExecutionContext): ToolResult = when (
        val result = gateway.openSession(resolveDeviceExecutionMode(args))
    ) {
        is DeviceResult.Success -> {
            // 会话已经由设备层创建后，即使此刻收到停止，也必须先登记，随后由 Run 清理路径关闭。
            withContext(NonCancellable) {
                sessionGate.withLock {
                    sessionsByRun.getOrPut(context.runId, ::mutableSetOf).add(result.value.id)
                }
            }
            ToolResult(buildJsonObject {
                put("session_id", result.value.id)
                put("mode", result.value.mode.name.lowercase())
                putJsonArray("capabilities") { result.value.capabilities.forEach { add(it.name.lowercase()) } }
            }.toString(), localizedText("手机操作已开始", "Phone operation started"))
        }

        is DeviceResult.Unsupported -> failed(result.reason)
        is DeviceResult.SessionExpired -> failed(result.reason)
        is DeviceResult.Failure -> failed(result.reason)
    }

    private suspend fun observe(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val id = required(args, "session_id")
        requireOwned(context, id)
        return when (val result = gateway.observe(id)) {
            is DeviceResult.Success -> observationResult(
                result.value,
                query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf(String::isNotBlank),
                requiredAction = args["required_action"]?.jsonPrimitive?.contentOrNull?.let {
                    NodeActionKind.valueOf(it.uppercase())
                },
                limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 80).coerceIn(1, 200),
            )

            is DeviceResult.Unsupported -> failed(result.reason)
            is DeviceResult.SessionExpired -> failed(result.reason)
            is DeviceResult.Failure -> failed(result.reason)
        }
    }

    private suspend fun action(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val session = required(args, "session_id")
        requireOwned(context, session)
        val actionName = required(args, "action")
        val observation =
            args["observation_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        if (actionName != "open_app") {
            require(observation != null) {
                localizedText("缺少参数 observation_id；请先调用 device_observe，并使用它返回的准确 ID", "Missing observation_id. Call device_observe first and use the exact ID it returns.")
            }
        }
        val action = when (actionName) {
            "click_node" -> Action.PerformNodeAction(
                NodeRef(required(args, "node_ref")),
                NodeActionKind.CLICK,
            )

            "long_click_node" -> Action.PerformNodeAction(
                NodeRef(required(args, "node_ref")),
                NodeActionKind.LONG_CLICK,
            )

            "scroll_node" -> Action.PerformNodeAction(
                NodeRef(required(args, "node_ref")),
                when (required(args, "scroll_direction")) {
                    "forward" -> NodeActionKind.SCROLL_FORWARD
                    "backward" -> NodeActionKind.SCROLL_BACKWARD
                    "up" -> NodeActionKind.SCROLL_UP
                    "down" -> NodeActionKind.SCROLL_DOWN
                    "left" -> NodeActionKind.SCROLL_LEFT
                    "right" -> NodeActionKind.SCROLL_RIGHT
                    else -> error(localizedText("不支持的滚动方向", "Unsupported scroll direction"))
                },
            )

            "tap" -> Action.Tap(int(args, "x"), int(args, "y"))
            "long_press" -> Action.LongPress(
                int(args, "x"),
                int(args, "y"),
                int(args, "duration_ms", 700)
            )

            "swipe" -> Action.Swipe(
                int(args, "x"),
                int(args, "y"),
                int(args, "end_x"),
                int(args, "end_y"),
                int(args, "duration_ms", 350)
            )

            "input_text" -> Action.InputText(
                args["text"]?.jsonPrimitive?.contentOrNull ?: error(localizedText("缺少参数 text", "Missing parameter: text")),
                args["node_ref"]?.jsonPrimitive?.contentOrNull?.let(::NodeRef),
                if (args["input_mode"]?.jsonPrimitive?.contentOrNull == "append") {
                    TextInputMode.APPEND
                } else {
                    TextInputMode.REPLACE
                },
            )

            "back" -> Action.PressKey(DeviceKey.BACK)
            "enter" -> Action.PressKey(DeviceKey.ENTER)
            "home" -> Action.PressKey(DeviceKey.HOME)
            "recents" -> Action.PressKey(DeviceKey.RECENTS)
            "escape" -> Action.PressKey(DeviceKey.ESCAPE)
            "delete" -> Action.PressKey(DeviceKey.DELETE)
            "tab" -> Action.PressKey(DeviceKey.TAB)
            "dpad_up" -> Action.PressKey(DeviceKey.DPAD_UP)
            "dpad_down" -> Action.PressKey(DeviceKey.DPAD_DOWN)
            "dpad_left" -> Action.PressKey(DeviceKey.DPAD_LEFT)
            "dpad_right" -> Action.PressKey(DeviceKey.DPAD_RIGHT)
            "open_app" -> Action.OpenApp(AppTarget(required(args, "package_name")))
            "wait" -> Action.Wait(int(args, "duration_ms", 500).toLong())
            else -> error(localizedText("不支持的设备动作", "Unsupported device action"))
        }
        return when (val result = gateway.execute(session, observation, action)) {
            is ActionResult.Performed -> ToolResult(
                "{\"performed\":true}",
                result.detail.ifBlank { localizedText("手机操作已完成", "Phone operation completed") })

            is ActionResult.Unsupported -> failed(result.reason)
            is ActionResult.SessionExpired -> failed(result.reason)
            is ActionResult.ObservationMismatch -> failed(result.reason)
            is ActionResult.TargetMismatch -> failed(result.reason)
            is ActionResult.Failure -> failed(result.reason)
        }
    }

    private suspend fun batch(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val session = required(args, "session_id")
        requireOwned(context, session)
        val steps = parseBatchSteps(args)
        val initialPackage =
            args["expected_package"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val finalObservation = args["final_observation"]?.jsonPrimitive?.booleanOrNull ?: true
        val completed = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        AgentLog.i("DeviceBatch") { "batch_start session=$session steps=${steps.size}" }

        for ((index, step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val expectedPackage = step.value["expected_package"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: initialPackage.takeIf { index == 0 }
            try {
                AgentLog.d("DeviceBatch") {
                    "step_start session=$session index=$index action=${step.action}"
                }
                completed += executeBatchStep(session, step, expectedPackage)
                AgentLog.d("DeviceBatch") {
                    "step_finish session=$session index=$index action=${step.action}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AgentLog.w("DeviceBatch", failure) {
                    "batch_stopped session=$session index=$index action=${step.action} completed=${completed.size}"
                }
                return batchResult(
                    session = session,
                    plannedSteps = steps.size,
                    completed = completed,
                    failedStep = index,
                    failure = failure.message ?: localizedText("批量步骤执行失败", "Batch step failed"),
                    includeObservation = true,
                )
            }
        }
        AgentLog.i("DeviceBatch") {
            "batch_finish session=$session completed=${completed.size} duration_ms=${System.currentTimeMillis() - startedAt}"
        }
        return batchResult(
            session = session,
            plannedSteps = steps.size,
            completed = completed,
            includeObservation = finalObservation,
        )
    }

    private suspend fun executeBatchStep(
        session: String,
        step: BatchStep,
        expectedPackage: String?,
    ): String {
        val value = step.value
        if (step.action == "wait_until") {
            expectedPackage?.let { expected ->
                val current = inspectForBatch(session)
                requireBatch(current.foregroundPackage == expected) {
                    val actual = current.foregroundPackage ?: localizedText("未知应用", "unknown app")
                    localizedText(
                        "界面已切换到 $actual，期望的应用是 $expected",
                        "The screen switched to $actual; expected app: $expected",
                    )
                }
            }
            waitForBatchState(session, value)
            return localizedText("等待界面状态", "Wait for screen state")
        }
        if (step.action == "wait") {
            expectedPackage?.let { expected ->
                val current = inspectForBatch(session)
                requireBatch(current.foregroundPackage == expected) {
                    localizedText("当前应用与批量计划不一致", "The current app does not match the batch plan.")
                }
            }
            delay(int(value, "duration_ms", 500).coerceIn(0, 5_000).toLong())
            return localizedText("等待界面响应", "Wait for screen response")
        }

        val needsObservation = step.action != "open_app" || expectedPackage != null
        val observation = if (needsObservation) inspectForBatch(session) else null
        expectedPackage?.let { expected ->
            requireBatch(observation?.foregroundPackage == expected) {
                val actual = observation?.foregroundPackage ?: localizedText("未知应用", "unknown app")
                localizedText(
                    "界面已切换到 $actual，期望的应用是 $expected",
                    "The screen switched to $actual; expected app: $expected",
                )
            }
        }
        val action = when (step.action) {
            "click" -> Action.PerformNodeAction(
                selectBatchNode(requireNotNull(observation), value, NodeActionKind.CLICK).ref,
                NodeActionKind.CLICK,
            )

            "long_click" -> Action.PerformNodeAction(
                selectBatchNode(requireNotNull(observation), value, NodeActionKind.LONG_CLICK).ref,
                NodeActionKind.LONG_CLICK,
            )

            "scroll" -> {
                val kind = scrollAction(required(value, "scroll_direction"))
                Action.PerformNodeAction(
                    selectBatchNode(
                        requireNotNull(observation),
                        value,
                        kind
                    ).ref, kind
                )
            }

            "input_text" -> Action.InputText(
                value["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw BatchStepFailure(localizedText("缺少输入文字", "Input text is missing.")),
                selectBatchNode(requireNotNull(observation), value, NodeActionKind.SET_TEXT).ref,
                if (value["input_mode"]?.jsonPrimitive?.contentOrNull == "append") {
                    TextInputMode.APPEND
                } else {
                    TextInputMode.REPLACE
                },
            )

            "tap" -> Action.Tap(int(value, "x"), int(value, "y"))
            "long_press" -> Action.LongPress(
                int(value, "x"),
                int(value, "y"),
                int(value, "duration_ms", 700)
            )

            "swipe" -> Action.Swipe(
                int(value, "x"), int(value, "y"), int(value, "end_x"), int(value, "end_y"),
                int(value, "duration_ms", 350),
            )

            "gesture" -> Action.MultiStrokeGesture(
                parseStrokes(
                    value["strokes"]?.jsonArray ?: throw BatchStepFailure(localizedText("轨迹缺少 strokes", "Gesture is missing strokes.")),
                )
            )

            "back" -> Action.PressKey(DeviceKey.BACK)
            "enter" -> Action.PressKey(DeviceKey.ENTER)
            "home" -> Action.PressKey(DeviceKey.HOME)
            "recents" -> Action.PressKey(DeviceKey.RECENTS)
            "escape" -> Action.PressKey(DeviceKey.ESCAPE)
            "delete" -> Action.PressKey(DeviceKey.DELETE)
            "tab" -> Action.PressKey(DeviceKey.TAB)
            "dpad_up" -> Action.PressKey(DeviceKey.DPAD_UP)
            "dpad_down" -> Action.PressKey(DeviceKey.DPAD_DOWN)
            "dpad_left" -> Action.PressKey(DeviceKey.DPAD_LEFT)
            "dpad_right" -> Action.PressKey(DeviceKey.DPAD_RIGHT)
            "open_app" -> Action.OpenApp(AppTarget(required(value, "package_name")))
            else -> throw BatchStepFailure(localizedText("不支持的批量动作 ${step.action}", "Unsupported batch action ${step.action}"))
        }
        val observationId = if (action is Action.OpenApp) null else requireNotNull(observation).id
        when (val result = gateway.execute(session, observationId, action)) {
            is ActionResult.Performed -> Unit
            is ActionResult.Unsupported -> throw BatchStepFailure(result.reason)
            is ActionResult.SessionExpired -> throw BatchStepFailure(result.reason)
            is ActionResult.ObservationMismatch -> throw BatchStepFailure(result.reason)
            is ActionResult.TargetMismatch -> throw BatchStepFailure(result.reason)
            is ActionResult.Failure -> throw BatchStepFailure(result.reason)
        }
        if (step.settleMs > 0) delay(step.settleMs.toLong())
        return batchStepLabel(step)
    }

    private suspend fun inspectForBatch(session: String): Observation =
        when (val result = gateway.inspect(session)) {
            is DeviceResult.Success -> result.value
            is DeviceResult.Unsupported -> throw BatchStepFailure(result.reason)
            is DeviceResult.SessionExpired -> throw BatchStepFailure(result.reason)
            is DeviceResult.Failure -> throw BatchStepFailure(result.reason)
        }

    private suspend fun waitForBatchState(session: String, value: JsonObject) {
        val text =
            value["target_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val viewId = value["target_view_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
        val packageName =
            value["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        requireBatch(text != null || viewId != null || packageName != null) {
            localizedText("wait_until 至少需要 target_text、target_view_id 或 package_name", "wait_until requires at least one of target_text, target_view_id, or package_name.")
        }
        val expectPresent = value["state"]?.jsonPrimitive?.contentOrNull != "absent"
        val timeoutMs = int(value, "timeout_ms", 5_000).coerceIn(0, 10_000)
        val intervalMs = int(value, "interval_ms", 300).coerceIn(150, 2_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val observation = inspectForBatch(session)
            val matched = (packageName == null || observation.foregroundPackage == packageName) &&
                    (viewId == null || observation.nodes.any { it.viewId == viewId }) &&
                    (text == null || observation.nodes.any { node ->
                        listOf(node.text, node.contentDescription, node.hintText)
                            .filterNotNull().any { it.contains(text, ignoreCase = true) }
                    })
            if (matched == expectPresent) return
            if (System.currentTimeMillis() >= deadline) {
                throw BatchStepFailure(localizedText("等待界面状态超时", "Timed out waiting for the screen state."))
            }
            delay(intervalMs.toLong())
        }
    }

    private fun selectBatchNode(
        observation: Observation,
        value: JsonObject,
        requiredAction: NodeActionKind,
    ): NodeSnapshot {
        val targetText =
            value["target_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val targetDescription = value["target_description"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotEmpty)
        val targetViewId = value["target_view_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
        val exact = value["match_mode"]?.jsonPrimitive?.contentOrNull == "exact"
        fun String?.matches(expected: String?) = expected == null || this?.let {
            if (exact) it.equals(expected, ignoreCase = true) else it.contains(
                expected,
                ignoreCase = true
            )
        } == true

        val candidates = observation.nodes.asSequence()
            .filter { it.enabled && it.visible }
            .filter { node ->
                if (requiredAction == NodeActionKind.SET_TEXT) node.editable
                else requiredAction in node.supportedActions
            }
            .filter { node ->
                val textMatches = targetText == null || listOf(node.text, node.hintText)
                    .any { it.matches(targetText) }
                textMatches && node.contentDescription.matches(targetDescription) &&
                        node.viewId.matches(targetViewId)
            }
            .filter { node ->
                targetText != null || targetDescription != null || targetViewId != null ||
                        (requiredAction == NodeActionKind.SET_TEXT && node.focused)
            }
            .sortedWith(compareBy<NodeSnapshot> { it.bounds.top }.thenBy { it.bounds.left })
            .toList()
        requireBatch(candidates.isNotEmpty()) { localizedText("当前界面没有匹配的可操作节点", "No matching actionable node was found on the current screen.") }
        val requestedIndex = value["match_index"]?.jsonPrimitive?.intOrNull
        if (requestedIndex != null) {
            return candidates.getOrNull(requestedIndex)
                ?: throw BatchStepFailure(localizedText("节点序号超出匹配范围", "Node index is outside the matched range."))
        }
        requireBatch(candidates.size == 1) {
            localizedText("当前界面匹配到 ${candidates.size} 个节点，请增加 view_id、description 或 match_index", "The current screen matched ${candidates.size} nodes. Add view_id, description, or match_index.")
        }
        return candidates.single()
    }

    private suspend fun batchResult(
        session: String,
        plannedSteps: Int,
        completed: List<String>,
        failedStep: Int? = null,
        failure: String? = null,
        includeObservation: Boolean,
    ): ToolResult {
        var observation: ToolResult? = null
        var observationFailure: String? = null
        if (includeObservation) {
            when (val result = gateway.observe(session)) {
                is DeviceResult.Success -> observation = observationResult(
                    result.value,
                    if (failure == null) localizedText("批量操作已完成，已返回最新界面", "Batch operation completed; returned the latest screen.") else localizedText("批量操作已停止，已返回最新界面", "Batch operation stopped; returned the latest screen."),
                )

                is DeviceResult.Unsupported -> observationFailure = result.reason
                is DeviceResult.SessionExpired -> observationFailure = result.reason
                is DeviceResult.Failure -> observationFailure = result.reason
            }
        }
        val isError = failure != null || observationFailure != null
        val content = buildJsonObject {
            put("performed", !isError)
            put("planned_steps", plannedSteps)
            put("completed_steps", completed.size)
            putJsonArray("step_summaries") { completed.forEach { add(it) } }
            failedStep?.let { put("failed_step", it) }
            failure?.let { put("error", it) }
            observationFailure?.let { put("observation_error", it) }
            observation?.let { put("observation", Json.parseToJsonElement(it.content)) }
        }.toString()
        val summary = when {
            failure != null -> "批量操作在第 ${requireNotNull(failedStep) + 1} 步停止：${
                toolErrorSummary(
                    failure
                )
            }"

            observationFailure != null -> localizedText("操作已执行，但最终界面识别失败", "The action ran, but the final screen inspection failed.")
            else -> localizedText("已连续执行 ${completed.size} 个手机步骤", "Completed ${completed.size} consecutive phone steps")
        }
        return ToolResult(content, summary, isError, observation?.images.orEmpty())
    }

    private fun parseBatchSteps(args: JsonObject): List<BatchStep> {
        val values = args["steps"]?.jsonArray ?: error(localizedText("缺少参数 steps", "Missing parameter: steps"))
        require(values.size in 1..20) { localizedText("一次批量操作需要 1 到 20 个步骤", "A batch operation requires 1 to 20 steps.") }
        var declaredDuration = 0L
        val steps = values.mapIndexed { index, element ->
            val value = element.jsonObject
            val action = value["action"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error(localizedText("第 ${index + 1} 个步骤缺少 action", "Step ${index + 1} is missing action."))
            require(action in BATCH_ACTIONS) { localizedText("第 ${index + 1} 个步骤的动作不受支持", "Action in step ${index + 1} is unsupported.") }
            val settleMs = (value["settle_ms"]?.jsonPrimitive?.intOrNull ?: when (action) {
                "open_app" -> 500
                "wait", "wait_until" -> 0
                else -> 200
            }).coerceIn(0, 1_500)
            val actionDuration = when (action) {
                "wait" -> int(value, "duration_ms", 500).coerceIn(0, 5_000).toLong()
                "wait_until" -> int(value, "timeout_ms", 5_000).coerceIn(0, 10_000).toLong()
                "long_press" -> int(value, "duration_ms", 700).coerceIn(1, 10_000).toLong()
                "swipe" -> int(value, "duration_ms", 350).coerceIn(1, 10_000).toLong()
                "gesture" -> parseStrokes(
                    value["strokes"]?.jsonArray ?: error(localizedText("第 ${index + 1} 个步骤缺少 strokes", "Step ${index + 1} is missing strokes.")),
                ).maxOf { it.startTimeMs + it.durationMs }

                else -> 0L
            }
            declaredDuration += actionDuration + settleMs
            BatchStep(value, action, settleMs)
        }
        require(declaredDuration <= 60_000) { localizedText("一次批量操作的最长计划时间为 60 秒", "A batch plan can last at most 60 seconds.") }
        return steps
    }

    private fun parseStrokes(strokeValues: JsonArray): List<GestureStroke> {
        require(strokeValues.size in 1..10) { localizedText("一次手势需要 1 到 10 条轨迹", "A gesture requires 1 to 10 paths.") }
        val strokes = strokeValues.map { value ->
            val stroke = value.jsonObject
            val points = stroke["points"]?.jsonArray?.map { pointValue ->
                val point = pointValue.jsonObject
                GesturePoint(int(point, "x"), int(point, "y"))
            } ?: error(localizedText("轨迹缺少 points", "Gesture path is missing points."))
            GestureStroke(
                points = points,
                startTimeMs = int(stroke, "start_time_ms", 0).toLong(),
                durationMs = int(stroke, "duration_ms").toLong(),
            )
        }
        require(strokes.sumOf { it.points.size } <= 500) { localizedText("一次手势最多允许 500 个轨迹点", "A gesture may contain at most 500 points.") }
        require(strokes.maxOf { it.startTimeMs + it.durationMs } <= 10_000) { localizedText("一次手势最长为 10 秒", "A gesture can last at most 10 seconds.") }
        return strokes
    }

    private fun scrollAction(direction: String): NodeActionKind = when (direction) {
        "forward" -> NodeActionKind.SCROLL_FORWARD
        "backward" -> NodeActionKind.SCROLL_BACKWARD
        "up" -> NodeActionKind.SCROLL_UP
        "down" -> NodeActionKind.SCROLL_DOWN
        "left" -> NodeActionKind.SCROLL_LEFT
        "right" -> NodeActionKind.SCROLL_RIGHT
        else -> throw BatchStepFailure(localizedText("不支持的滚动方向", "Unsupported scroll direction"))
    }

    private fun batchStepLabel(step: BatchStep): String = when (step.action) {
        "click" -> localizedText("点击界面元素", "Tap screen element")
        "long_click" -> localizedText("长按界面元素", "Long-press screen element")
        "scroll" -> localizedText("滚动界面内容", "Scroll screen content")
        "input_text" -> localizedText("填写文字", "Enter text")
        "tap" -> localizedText("点击指定位置", "Tap specified position")
        "long_press" -> localizedText("长按指定位置", "Long-press specified position")
        "swipe" -> localizedText("滑动界面", "Swipe screen")
        "gesture" -> localizedText("执行连续轨迹", "Run continuous gesture")
        "open_app" -> localizedText("打开目标应用", "Open target app")
        "back" -> localizedText("返回上一页", "Go back")
        "home" -> localizedText("返回系统桌面", "Go home")
        "recents" -> localizedText("打开最近任务", "Open recent apps")
        "enter" -> localizedText("执行确认", "Confirm")
        else -> localizedText("执行 ${step.action}", "Run ${step.action}")
    }

    private fun batchApprovalSummary(args: JsonObject): String {
        val values = args["steps"]?.jsonArray.orEmpty()
        val labels = values.take(5).mapNotNull { element ->
            runCatching {
                val value = element.jsonObject
                val action =
                    value["action"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                when (action) {
                    "click", "long_click" -> value["target_text"]?.jsonPrimitive?.contentOrNull
                        ?.take(30)?.let { if (action == "click") localizedText("点击“$it”", "Tap “$it”") else localizedText("长按“$it”", "Long-press “$it”") }
                        ?: if (action == "click") localizedText("点击元素", "Tap element") else localizedText("长按元素", "Long-press element")

                    "open_app" -> localizedText("打开应用", "Open app")
                    "input_text" -> localizedText("填写文字", "Enter text")
                    "gesture" -> localizedText("执行轨迹", "Run gesture path")
                    "wait_until" -> localizedText("等待界面", "Wait for screen")
                    else -> action
                }
            }.getOrNull()
        }
        val suffix = if (values.size > labels.size) " …" else ""
        return localizedText(
            "连续执行 ${values.size} 步：${labels.joinToString(" → ")}$suffix",
            "Run ${values.size} steps: ${labels.joinToString(" → ")}$suffix",
        )
    }

    private inline fun requireBatch(condition: Boolean, message: () -> String) {
        if (!condition) throw BatchStepFailure(message())
    }

    private suspend fun gesture(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val session = required(args, "session_id")
        requireOwned(context, session)
        val observation = required(args, "observation_id")
        val strokes = parseStrokes(args["strokes"]?.jsonArray ?: error(localizedText("缺少参数 strokes", "Missing parameter: strokes")))
        return when (val result = gateway.execute(
            session,
            observation,
            Action.MultiStrokeGesture(strokes),
        )) {
            is ActionResult.Performed -> ToolResult(
                """{"performed":true,"stroke_count":${strokes.size},"point_count":${strokes.sumOf { it.points.size }}}""",
                localizedText("已执行 ${strokes.size} 条连续轨迹", "Executed ${strokes.size} continuous gesture paths"),
            )

            is ActionResult.Unsupported -> failed(result.reason)
            is ActionResult.SessionExpired -> failed(result.reason)
            is ActionResult.ObservationMismatch -> failed(result.reason)
            is ActionResult.TargetMismatch -> failed(result.reason)
            is ActionResult.Failure -> failed(result.reason)
        }
    }

    private suspend fun waitFor(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val session = required(args, "session_id")
        requireOwned(context, session)
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val nodeRef = args["node_ref"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val packageName =
            args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        require(text != null || nodeRef != null || packageName != null) {
            localizedText("text、node_ref、package_name 至少提供一个", "Provide at least one of text, node_ref, or package_name.")
        }
        val expectPresent = args["state"]?.jsonPrimitive?.contentOrNull != "absent"
        val timeoutMs = int(args, "timeout_ms", 5_000).coerceIn(0, 10_000)
        val intervalMs = int(args, "interval_ms", 500).coerceIn(200, 2_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // 轮询期间只读节点，条件达成或超时时才生成一张要发给模型的截图。
            when (val result = gateway.inspect(session)) {
                is DeviceResult.Success -> {
                    val value = result.value
                    val matched = (packageName == null || value.foregroundPackage == packageName) &&
                            (nodeRef == null || value.nodes.any { it.ref.value == nodeRef }) &&
                            (text == null || value.nodes.any { node ->
                                listOf(node.text, node.contentDescription, node.hintText)
                                    .filterNotNull().any { it.contains(text, ignoreCase = true) }
                            })
                    if (matched == expectPresent) return finalWaitObservation(
                        session,
                        if (expectPresent) localizedText("界面目标已出现", "Screen target appeared") else localizedText("界面目标已消失", "Screen target disappeared"),
                        text,
                    )
                    if (System.currentTimeMillis() >= deadline) {
                        val latest =
                            finalWaitObservation(session, localizedText("等待界面状态超时，已返回最新界面", "Timed out waiting for the screen state; returned the latest screen."))
                        return latest.copy(
                            content = JsonObject(
                                Json.parseToJsonElement(latest.content).jsonObject + mapOf(
                                    "matched" to JsonPrimitive(false),
                                    "error" to JsonPrimitive(localizedText("等待界面状态超时，请根据最新识别结果调整", "Timed out waiting for the screen state. Adjust based on the latest observation.")),
                                ),
                            ).toString(),
                            isError = true,
                        )
                    }
                }

                is DeviceResult.Unsupported -> return failed(result.reason)
                is DeviceResult.SessionExpired -> return failed(result.reason)
                is DeviceResult.Failure -> return failed(result.reason)
            }
            delay(intervalMs.toLong())
        }
    }

    private suspend fun finalWaitObservation(
        session: String,
        summary: String,
        query: String? = null,
    ): ToolResult = when (val result = gateway.observe(session)) {
        is DeviceResult.Success -> observationResult(result.value, summary, query = query)
        is DeviceResult.Unsupported -> failed(result.reason)
        is DeviceResult.SessionExpired -> failed(result.reason)
        is DeviceResult.Failure -> failed(result.reason)
    }

    private fun observationResult(
        value: Observation,
        summary: String? = null,
        query: String? = null,
        requiredAction: NodeActionKind? = null,
        limit: Int = 80,
    ): ToolResult {
        val matchingNodes = value.nodes.asSequence()
            .filter { node ->
                query == null || listOf(
                    node.text,
                    node.contentDescription,
                    node.hintText,
                    node.viewId,
                    node.className,
                ).filterNotNull().any { it.contains(query, ignoreCase = true) }
            }
            .filter { node -> requiredAction == null || requiredAction in node.supportedActions }
            .sortedWith(compareByDescending<NodeSnapshot> { it.supportedActions.isNotEmpty() }
                .thenByDescending { it.editable }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left })
            .toList()
        val returnedNodes = matchingNodes.take(limit.coerceIn(1, 200))
        val screenshot = value.screenshot
        return ToolResult(
            content = buildJsonObject {
                put("observation_id", value.id)
                put("session_id", value.sessionId)
                put("foreground_package", value.foregroundPackage)
                put("captured_at_epoch_ms", value.capturedAtEpochMillis)
                put("content_revision", value.contentRevision)
                put("width", value.viewport.width)
                put("height", value.viewport.height)
                put("rotation_degrees", value.rotationDegrees)
                put("screenshot_attached", screenshot != null)
                put("node_count", value.nodes.size)
                put("matching_node_count", matchingNodes.size)
                put("nodes_returned", returnedNodes.size)
                put("nodes_truncated", returnedNodes.size < matchingNodes.size)
                putJsonArray("nodes") {
                    returnedNodes.forEach { node ->
                        add(buildJsonObject {
                            put("ref", node.ref.value)
                            put("text", node.text)
                            put("description", node.contentDescription)
                            put("hint", node.hintText)
                            put("view_id", node.viewId)
                            put("class", node.className)
                            put("package", node.packageName)
                            put("left", node.bounds.left)
                            put("top", node.bounds.top)
                            put("right", node.bounds.right)
                            put("bottom", node.bounds.bottom)
                            put("editable", node.editable)
                            put("focused", node.focused)
                            put("enabled", node.enabled)
                            put("visible", node.visible)
                            put("clickable", node.clickable)
                            put("long_clickable", node.longClickable)
                            put("scrollable", node.scrollable)
                            put("selected", node.selected)
                            put("checkable", node.checkable)
                            put("checked", node.checked)
                            putJsonArray("actions") {
                                node.supportedActions.sortedBy { it.name }
                                    .forEach { add(it.name.lowercase()) }
                            }
                        })
                    }
                }
            }.toString(),
            summary = summary ?: run {
                val target = value.foregroundPackage ?: localizedText("当前界面", "current screen")
                localizedText(
                    "已识别 $target 中的 ${value.nodes.size} 个可操作项",
                    "Inspected $target and found ${value.nodes.size} actionable nodes",
                )
            },
            images = screenshot?.let { image ->
                listOf(
                    ChatImage(
                        name = "device-observation-${value.id}.${if (image.mimeType == "image/png") "png" else "jpg"}",
                        mimeType = image.mimeType,
                        bytes = image.bytes,
                        width = image.width,
                        height = image.height,
                    )
                )
            }.orEmpty(),
        )
    }

    private suspend fun close(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val id = required(args, "session_id")
        requireOwned(context, id)
        return when (val result = gateway.closeSession(id)) {
            is DeviceResult.Success -> {
                forget(context.runId, id)
                ToolResult("{\"closed\":true}", localizedText("手机操作已结束", "Phone operation ended"))
            }

            is DeviceResult.Unsupported -> failed(result.reason)
            is DeviceResult.SessionExpired -> {
                forget(context.runId, id)
                failed(result.reason)
            }

            is DeviceResult.Failure -> failed(result.reason)
        }
    }

    override suspend fun finish(context: ToolExecutionContext) {
        val sessions =
            sessionGate.withLock { sessionsByRun.remove(context.runId)?.toList().orEmpty() }
        sessions.forEach { id -> runCatching { gateway.closeSession(id) } }
    }

    private suspend fun requireOwned(context: ToolExecutionContext, sessionId: String) {
        require(sessionGate.withLock { sessionsByRun[context.runId]?.contains(sessionId) == true }) {
            localizedText("设备会话不属于当前执行轮次或已经关闭", "The device session does not belong to the current run or is already closed.")
        }
    }

    private suspend fun forget(runId: String, sessionId: String) {
        sessionGate.withLock {
            sessionsByRun[runId]?.let { sessions ->
                sessions.remove(sessionId)
                if (sessions.isEmpty()) sessionsByRun.remove(runId)
            }
        }
    }

    private fun required(args: JsonObject, name: String) =
        args[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 $name", "Missing parameter: $name"))

    private fun int(args: JsonObject, name: String, default: Int? = null) =
        args[name]?.jsonPrimitive?.intOrNull ?: default ?: error(localizedText("缺少参数 $name", "Missing parameter: $name"))

    private fun failed(reason: String) = ToolResult(
        buildJsonObject { put("error", reason) }.toString(),
        toolErrorSummary(reason),
        true,
    )

    private data class BatchStep(
        val value: JsonObject,
        val action: String,
        val settleMs: Int,
    )

    private class BatchStepFailure(message: String) : IllegalStateException(message)

    private companion object {
        val BATCH_ACTIONS = setOf(
            "click", "long_click", "scroll", "input_text", "tap", "long_press", "swipe", "gesture",
            "back", "enter", "home", "recents", "escape", "delete", "tab",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "open_app", "wait", "wait_until",
        )

        val BATCH_SCHEMA: String
            get() = localizedJsonSchema("""
            {
              "type":"object",
              "properties":{
                "session_id":{"type":"string","description":localizedText("当前执行轮次的设备会话 ID", "Device session ID for the current run")},
                "expected_package":{"type":"string","description":localizedText("可选，第一步执行前必须处于该应用，否则整批停止", "Optional app that must be foreground before the first step, otherwise stop the batch")},
                "final_observation":{"type":"boolean","default":true,"description":localizedText("完成或失败后是否返回最新截图与节点；失败时始终返回", "Whether to return the latest screenshot and nodes after completion or failure; failures always return them")},
                "steps":{
                  "type":"array","minItems":1,"maxItems":20,
                  "items":{
                    "type":"object",
                    "properties":{
                      "action":{"type":"string","enum":["click","long_click","scroll","input_text","tap","long_press","swipe","gesture","back","enter","home","recents","escape","delete","tab","dpad_up","dpad_down","dpad_left","dpad_right","open_app","wait","wait_until"]},
                      "expected_package":{"type":"string","description":localizedText("该步执行前必须处于该应用", "App that must be foreground before this step")},
                      "target_text":{"type":"string","maxLength":200,"description":localizedText("点击、长按、输入、滚动或等待的可见文字/提示", "Visible text or hint to tap, long-press, type into, scroll, or wait for")},
                      "target_description":{"type":"string","maxLength":200,"description":localizedText("目标的访问描述", "Target accessibility description")},
                      "target_view_id":{"type":"string","maxLength":300,"description":localizedText("目标 view_id", "Target view_id")},
                      "match_mode":{"type":"string","enum":["contains","exact"],"default":"contains"},
                      "match_index":{"type":"integer","minimum":0,"maximum":199,"description":localizedText("匹配多个节点时按从上到下、从左到右的序号", "Index among multiple matches ordered top-to-bottom, then left-to-right")},
                      "scroll_direction":{"type":"string","enum":["forward","backward","up","down","left","right"]},
                      "x":{"type":"integer"},"y":{"type":"integer"},
                      "end_x":{"type":"integer"},"end_y":{"type":"integer"},
                      "duration_ms":{"type":"integer","minimum":0,"maximum":10000},
                      "settle_ms":{"type":"integer","minimum":0,"maximum":1500,"description":localizedText("动作后在本地等待界面稳定的时间", "Local delay after the action for the screen to settle")},
                      "text":{"type":"string","maxLength":500,"description":localizedText("仅 input_text 使用", "Used only by input_text")},
                      "input_mode":{"type":"string","enum":["replace","append"],"default":"replace"},
                      "package_name":{"type":"string","description":localizedText("open_app 的包名，或 wait_until 等待的前台应用", "Package name for open_app, or foreground app to wait for in wait_until")},
                      "state":{"type":"string","enum":["present","absent"],"default":"present"},
                      "timeout_ms":{"type":"integer","minimum":0,"maximum":10000,"default":5000},
                      "interval_ms":{"type":"integer","minimum":150,"maximum":2000,"default":300},
                      "strokes":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","properties":{"points":{"type":"array","minItems":2,"maxItems":500,"items":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"],"additionalProperties":false}},"start_time_ms":{"type":"integer","minimum":0,"maximum":10000,"default":0},"duration_ms":{"type":"integer","minimum":1,"maximum":10000}},"required":["points","duration_ms"],"additionalProperties":false}}
                    },
                    "required":["action"],
                    "additionalProperties":false
                  }
                }
              },
              "required":["session_id","steps"],
              "additionalProperties":false
            }
        """.trimIndent())
    }
}
