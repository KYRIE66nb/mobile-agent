package xyz.chouxuewei.mobile_agent.tools

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
    override val title = "手机操作"
    override val description =
        "经你授权后，可把临时界面截图和节点信息交给当前模型分析，并操作手机。需要 Root 权限。"

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

    override val definitions = listOf(
        ToolDefinition(
            "device_list_apps", "获取本机应用列表",
            "列出本机具有启动入口的应用名称和真实 package_name。打开应用前必须使用本工具返回的包名，不能翻译或猜测；带 query 返回 0 条时可再调用一次且不传 query。",
            """{"type":"object","properties":{"query":{"type":"string","maxLength":200,"description":"可选，按应用名称或包名筛选；不确定系统显示名称时省略"},"limit":{"type":"integer","minimum":1,"maximum":500,"default":200}},"additionalProperties":false}""",
            ToolSideEffect.READ, "device",
            approvalDescription = "获取本机可以打开的应用列表。",
        ),
        ToolDefinition(
            "device_open",
            "开始手机操作",
            "开启当前执行轮次专用的设备会话。mode 必须根据用户要求和任务目标选择：用户明确指定前台或后台时直接照做；未指定时，打开或切换应用、展示页面，以及需要用户看到当前屏幕结果的任务使用 main；能够在隔离屏独立完成且不应打扰当前屏幕的任务使用 virtual。不得为了选择前台或后台询问用户；后续只使用本次返回的 session_id。",
            """{"type":"object","properties":{"mode":{"type":"string","enum":["main","virtual"],"description":"根据用户要求和任务目标选择。打开或切换应用、展示页面、需要用户看到屏幕结果时使用 main；可在隔离屏独立完成且不应打扰当前屏幕时使用 virtual"}},"required":["mode"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            "device",
            approvalDescription = "开始一次手机操作。执行方式由用户要求和任务目标确定。",
        ),
        ToolDefinition(
            "device_observe",
            "识别手机界面",
            "读取当前设备会话的前台应用、截图、坐标范围和可访问节点，并返回一次性 observation_id。节点和截图只对紧接着的一次模型决策有效，随后自动清除；继续操作必须重新识别。可用 query 或 required_action 筛选复杂界面；nodes_truncated=true 时缩小条件重新识别。",
            """{"type":"object","properties":{"session_id":{"type":"string","description":"当前执行轮次中 device_open 返回的真实会话 ID"},"query":{"type":"string","maxLength":200,"description":"可选，筛选文字、描述、提示、view_id 或类名"},"required_action":{"type":"string","enum":["click","long_click","scroll_forward","scroll_backward","scroll_up","scroll_down","scroll_left","scroll_right","set_text"]},"limit":{"type":"integer","minimum":1,"maximum":200,"default":80}},"required":["session_id"],"additionalProperties":false}""",
            ToolSideEffect.READ,
            "device",
            approvalDescription = "识别当前界面，并把临时截图和节点信息交给当前模型分析。",
            resultLifetime = ToolResultLifetime.SINGLE_MODEL_STEP,
        ),
        ToolDefinition(
            "device_action", "操作手机",
            "每次只执行一个动作。优先使用 click_node、long_click_node、scroll_node 等节点语义动作；节点不支持时才使用坐标。open_app 可省略 observation_id；其他动作必须原样使用最近一次 device_observe 返回的准确 ID。动作成功后该识别结果立即失效，继续操作前必须重新识别。",
            """{"type":"object","properties":{"session_id":{"type":"string","description":"当前执行轮次中 device_open 返回的真实会话 ID"},"observation_id":{"type":"string","description":"open_app 可省略；其他动作必须使用最近一次 device_observe 返回的一次性准确 ID"},"action":{"type":"string","enum":["click_node","long_click_node","scroll_node","tap","long_press","swipe","input_text","back","enter","home","recents","escape","delete","tab","dpad_up","dpad_down","dpad_left","dpad_right","open_app","wait"]},"x":{"type":"integer","description":"点击或滑动起点横坐标，来自当前识别结果"},"y":{"type":"integer","description":"点击或滑动起点纵坐标，来自当前识别结果"},"end_x":{"type":"integer","description":"滑动终点横坐标"},"end_y":{"type":"integer","description":"滑动终点纵坐标"},"duration_ms":{"type":"integer"},"text":{"type":"string","maxLength":500,"description":"仅用于 input_text；replace 模式传空字符串可清空输入框"},"input_mode":{"type":"string","enum":["replace","append"],"default":"replace"},"node_ref":{"type":"string","description":"节点语义动作必须提供；input_text 可选；值必须来自当前识别结果"},"scroll_direction":{"type":"string","enum":["forward","backward","up","down","left","right"],"description":"仅用于 scroll_node"},"package_name":{"type":"string","description":"仅用于 open_app，必须原样复制 device_list_apps 返回的 package_name"}},"required":["session_id","action"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE, "device",
            approvalDescription = "在手机上执行一次操作。",
        ),
        ToolDefinition(
            "device_batch", "批量操作手机",
            "在手机本地连续执行 1 到 20 个步骤，减少每次点击后都请求模型。每步会轻量识别当前界面，并用 target_text、target_description 或 target_view_id 重新定位节点；不得为后续步骤复用旧 node_ref。任一步不匹配、跳到错误应用或执行失败时立即停止，并返回最新界面。坐标动作只能用在确认不会变化的当前布局。不要把支付、验证码、账号安全或系统授权确认放入批量执行。",
            BATCH_SCHEMA,
            ToolSideEffect.EXTERNAL_WRITE, "device",
            approvalDescription = "在手机上连续执行一组已列出的操作，失败时立即停止。",
            resultLifetime = ToolResultLifetime.SINGLE_MODEL_STEP,
        ),
        ToolDefinition(
            "device_gesture", "执行复杂触控",
            "在最近一次识别结果的坐标系中执行一组轨迹。单条多点轨迹可在画板连续画线；多条 start_time_ms 相同的轨迹会并行执行，可用于双指缩放。一次最多 10 条轨迹、共 500 个点、总时长 10 秒。",
            """{"type":"object","properties":{"session_id":{"type":"string"},"observation_id":{"type":"string","description":"最近一次 device_observe 返回的准确 ID"},"strokes":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","properties":{"points":{"type":"array","minItems":2,"maxItems":500,"items":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"],"additionalProperties":false}},"start_time_ms":{"type":"integer","minimum":0,"maximum":10000,"default":0},"duration_ms":{"type":"integer","minimum":1,"maximum":10000}},"required":["points","duration_ms"],"additionalProperties":false}}},"required":["session_id","observation_id","strokes"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE, "device",
            approvalDescription = "在手机上执行连续轨迹或多指手势。",
        ),
        ToolDefinition(
            "device_wait_for", "等待界面状态",
            "在设备会话中轮询识别，直到指定文字、节点、应用出现或消失，并返回仅供下一步使用的最新 observation_id 和截图。适合加载、跳转和弹窗等待，不能用来无限等待。",
            """{"type":"object","properties":{"session_id":{"type":"string"},"state":{"type":"string","enum":["present","absent"],"default":"present"},"text":{"type":"string","maxLength":200,"description":"匹配节点文字、描述或提示文字，忽略大小写"},"node_ref":{"type":"string","description":"匹配先前识别结果中的节点引用"},"package_name":{"type":"string","description":"匹配当前前台应用包名"},"timeout_ms":{"type":"integer","minimum":0,"maximum":10000,"default":5000},"interval_ms":{"type":"integer","minimum":200,"maximum":2000,"default":500}},"required":["session_id"],"additionalProperties":false}""",
            ToolSideEffect.READ, "device",
            approvalDescription = "等待手机界面达到指定状态，并把最终临时截图交给当前模型分析。",
            resultLifetime = ToolResultLifetime.SINGLE_MODEL_STEP,
        ),
        ToolDefinition(
            "device_close",
            "结束手机操作",
            "结束当前执行轮次中 device_open 创建的设备会话并释放资源。只能使用本轮返回的 session_id；正常结束时运行时也会自动清理。",
            """{"type":"object","properties":{"session_id":{"type":"string","description":"当前执行轮次中 device_open 返回的真实会话 ID"}},"required":["session_id"],"additionalProperties":false}""",
            ToolSideEffect.EXTERNAL_WRITE,
            "device",
            approvalDescription = "结束本次手机操作。",
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
            else -> error("设备工具不支持 ${call.toolId}")
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "device_list_apps" -> args["query"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf(String::isNotBlank)?.take(80)?.let { "查找应用：$it" }
                ?: "获取可打开的应用列表"

            "device_action" -> when (args["action"]?.jsonPrimitive?.contentOrNull) {
                "open_app" -> args["package_name"]?.jsonPrimitive?.contentOrNull
                    ?.take(120)?.let { "打开应用：$it" }

                "click_node" -> "点击界面元素"
                "long_click_node" -> "长按界面元素"
                "scroll_node" -> "滚动界面元素"
                "tap" -> "点击当前界面"
                "long_press" -> "长按当前界面"
                "swipe" -> "滑动当前界面"
                "input_text" -> "向当前输入框填写文字"
                "back" -> "返回上一页"
                "enter" -> "执行确认操作"
                "home" -> "返回系统桌面"
                "recents" -> "打开最近任务"
                "wait" -> "等待界面完成响应"
                else -> "操作当前手机界面"
            }

            "device_batch" -> batchApprovalSummary(args)
            "device_open" -> if (resolveDeviceExecutionMode(args) == ExecutionMode.MAIN_DISPLAY) {
                "以前台主屏方式开始手机操作"
            } else {
                "以后台隔离方式开始手机操作"
            }
            "device_observe" -> args["query"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)?.take(80)?.let { "识别当前界面中的：$it" }
                ?: "识别当前手机界面"

            "device_gesture" -> "执行连续轨迹或多指手势"
            "device_wait_for" -> "等待指定界面状态"
            "device_close" -> "结束手机操作"
            else -> null
        }
    }.getOrNull()

    private suspend fun listApps(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(query.length <= 200) { "应用筛选内容不能超过 200 个字符" }
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
                        "找到 ${matches.size} 个可启动应用"
                    } else {
                        "找到 ${matches.size} 个匹配应用"
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
            }.toString(), "手机操作已开始")
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
                "缺少参数 observation_id；请先调用 device_observe，并使用它返回的准确 ID"
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
                    else -> error("不支持的滚动方向")
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
                args["text"]?.jsonPrimitive?.contentOrNull ?: error("缺少参数 text"),
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
            else -> error("不支持的设备动作")
        }
        return when (val result = gateway.execute(session, observation, action)) {
            is ActionResult.Performed -> ToolResult(
                "{\"performed\":true}",
                result.detail.ifBlank { "手机操作已完成" })

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
                    failure = failure.message ?: "批量步骤执行失败",
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
                    "界面已切换到 ${current.foregroundPackage ?: "未知应用"}，期望的应用是 $expected"
                }
            }
            waitForBatchState(session, value)
            return "等待界面状态"
        }
        if (step.action == "wait") {
            expectedPackage?.let { expected ->
                val current = inspectForBatch(session)
                requireBatch(current.foregroundPackage == expected) {
                    "当前应用与批量计划不一致"
                }
            }
            delay(int(value, "duration_ms", 500).coerceIn(0, 5_000).toLong())
            return "等待界面响应"
        }

        val needsObservation = step.action != "open_app" || expectedPackage != null
        val observation = if (needsObservation) inspectForBatch(session) else null
        expectedPackage?.let { expected ->
            requireBatch(observation?.foregroundPackage == expected) {
                "界面已切换到 ${observation?.foregroundPackage ?: "未知应用"}，期望的应用是 $expected"
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
                    ?: throw BatchStepFailure("缺少输入文字"),
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
                    value["strokes"]?.jsonArray ?: throw BatchStepFailure("轨迹缺少 strokes"),
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
            else -> throw BatchStepFailure("不支持的批量动作 ${step.action}")
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
            "wait_until 至少需要 target_text、target_view_id 或 package_name"
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
                throw BatchStepFailure("等待界面状态超时")
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
        requireBatch(candidates.isNotEmpty()) { "当前界面没有匹配的可操作节点" }
        val requestedIndex = value["match_index"]?.jsonPrimitive?.intOrNull
        if (requestedIndex != null) {
            return candidates.getOrNull(requestedIndex)
                ?: throw BatchStepFailure("节点序号超出匹配范围")
        }
        requireBatch(candidates.size == 1) {
            "当前界面匹配到 ${candidates.size} 个节点，请增加 view_id、description 或 match_index"
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
                    if (failure == null) "批量操作已完成，已返回最新界面" else "批量操作已停止，已返回最新界面",
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

            observationFailure != null -> "操作已执行，但最终界面识别失败"
            else -> "已连续执行 ${completed.size} 个手机步骤"
        }
        return ToolResult(content, summary, isError, observation?.images.orEmpty())
    }

    private fun parseBatchSteps(args: JsonObject): List<BatchStep> {
        val values = args["steps"]?.jsonArray ?: error("缺少参数 steps")
        require(values.size in 1..20) { "一次批量操作需要 1 到 20 个步骤" }
        var declaredDuration = 0L
        val steps = values.mapIndexed { index, element ->
            val value = element.jsonObject
            val action = value["action"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("第 ${index + 1} 个步骤缺少 action")
            require(action in BATCH_ACTIONS) { "第 ${index + 1} 个步骤的动作不受支持" }
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
                    value["strokes"]?.jsonArray ?: error("第 ${index + 1} 个步骤缺少 strokes"),
                ).maxOf { it.startTimeMs + it.durationMs }

                else -> 0L
            }
            declaredDuration += actionDuration + settleMs
            BatchStep(value, action, settleMs)
        }
        require(declaredDuration <= 60_000) { "一次批量操作的最长计划时间为 60 秒" }
        return steps
    }

    private fun parseStrokes(strokeValues: JsonArray): List<GestureStroke> {
        require(strokeValues.size in 1..10) { "一次手势需要 1 到 10 条轨迹" }
        val strokes = strokeValues.map { value ->
            val stroke = value.jsonObject
            val points = stroke["points"]?.jsonArray?.map { pointValue ->
                val point = pointValue.jsonObject
                GesturePoint(int(point, "x"), int(point, "y"))
            } ?: error("轨迹缺少 points")
            GestureStroke(
                points = points,
                startTimeMs = int(stroke, "start_time_ms", 0).toLong(),
                durationMs = int(stroke, "duration_ms").toLong(),
            )
        }
        require(strokes.sumOf { it.points.size } <= 500) { "一次手势最多允许 500 个轨迹点" }
        require(strokes.maxOf { it.startTimeMs + it.durationMs } <= 10_000) { "一次手势最长为 10 秒" }
        return strokes
    }

    private fun scrollAction(direction: String): NodeActionKind = when (direction) {
        "forward" -> NodeActionKind.SCROLL_FORWARD
        "backward" -> NodeActionKind.SCROLL_BACKWARD
        "up" -> NodeActionKind.SCROLL_UP
        "down" -> NodeActionKind.SCROLL_DOWN
        "left" -> NodeActionKind.SCROLL_LEFT
        "right" -> NodeActionKind.SCROLL_RIGHT
        else -> throw BatchStepFailure("不支持的滚动方向")
    }

    private fun batchStepLabel(step: BatchStep): String = when (step.action) {
        "click" -> "点击界面元素"
        "long_click" -> "长按界面元素"
        "scroll" -> "滚动界面内容"
        "input_text" -> "填写文字"
        "tap" -> "点击指定位置"
        "long_press" -> "长按指定位置"
        "swipe" -> "滑动界面"
        "gesture" -> "执行连续轨迹"
        "open_app" -> "打开目标应用"
        "back" -> "返回上一页"
        "home" -> "返回系统桌面"
        "recents" -> "打开最近任务"
        "enter" -> "执行确认"
        else -> "执行 ${step.action}"
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
                        ?.take(30)?.let { if (action == "click") "点击“$it”" else "长按“$it”" }
                        ?: if (action == "click") "点击元素" else "长按元素"

                    "open_app" -> "打开应用"
                    "input_text" -> "填写文字"
                    "gesture" -> "执行轨迹"
                    "wait_until" -> "等待界面"
                    else -> action
                }
            }.getOrNull()
        }
        val suffix = if (values.size > labels.size) " …" else ""
        return "连续执行 ${values.size} 步：${labels.joinToString(" → ")}$suffix"
    }

    private inline fun requireBatch(condition: Boolean, message: () -> String) {
        if (!condition) throw BatchStepFailure(message())
    }

    private suspend fun gesture(args: JsonObject, context: ToolExecutionContext): ToolResult {
        val session = required(args, "session_id")
        requireOwned(context, session)
        val observation = required(args, "observation_id")
        val strokes = parseStrokes(args["strokes"]?.jsonArray ?: error("缺少参数 strokes"))
        return when (val result = gateway.execute(
            session,
            observation,
            Action.MultiStrokeGesture(strokes),
        )) {
            is ActionResult.Performed -> ToolResult(
                """{"performed":true,"stroke_count":${strokes.size},"point_count":${strokes.sumOf { it.points.size }}}""",
                "已执行 ${strokes.size} 条连续轨迹",
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
            "text、node_ref、package_name 至少提供一个"
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
                        if (expectPresent) "界面目标已出现" else "界面目标已消失",
                        text,
                    )
                    if (System.currentTimeMillis() >= deadline) {
                        val latest =
                            finalWaitObservation(session, "等待界面状态超时，已返回最新界面")
                        return latest.copy(
                            content = JsonObject(
                                Json.parseToJsonElement(latest.content).jsonObject + mapOf(
                                    "matched" to JsonPrimitive(false),
                                    "error" to JsonPrimitive("等待界面状态超时，请根据最新识别结果调整"),
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
            summary = summary
                ?: "已识别 ${value.foregroundPackage ?: "当前界面"} 中的 ${value.nodes.size} 个可操作项",
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
                ToolResult("{\"closed\":true}", "手机操作已结束")
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
            "设备会话不属于当前执行轮次或已经关闭"
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
            ?: error("缺少参数 $name")

    private fun int(args: JsonObject, name: String, default: Int? = null) =
        args[name]?.jsonPrimitive?.intOrNull ?: default ?: error("缺少参数 $name")

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

        val BATCH_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "session_id":{"type":"string","description":"当前执行轮次的设备会话 ID"},
                "expected_package":{"type":"string","description":"可选，第一步执行前必须处于该应用，否则整批停止"},
                "final_observation":{"type":"boolean","default":true,"description":"完成或失败后是否返回最新截图与节点；失败时始终返回"},
                "steps":{
                  "type":"array","minItems":1,"maxItems":20,
                  "items":{
                    "type":"object",
                    "properties":{
                      "action":{"type":"string","enum":["click","long_click","scroll","input_text","tap","long_press","swipe","gesture","back","enter","home","recents","escape","delete","tab","dpad_up","dpad_down","dpad_left","dpad_right","open_app","wait","wait_until"]},
                      "expected_package":{"type":"string","description":"该步执行前必须处于该应用"},
                      "target_text":{"type":"string","maxLength":200,"description":"点击、长按、输入、滚动或等待的可见文字/提示"},
                      "target_description":{"type":"string","maxLength":200,"description":"目标的访问描述"},
                      "target_view_id":{"type":"string","maxLength":300,"description":"目标 view_id"},
                      "match_mode":{"type":"string","enum":["contains","exact"],"default":"contains"},
                      "match_index":{"type":"integer","minimum":0,"maximum":199,"description":"匹配多个节点时按从上到下、从左到右的序号"},
                      "scroll_direction":{"type":"string","enum":["forward","backward","up","down","left","right"]},
                      "x":{"type":"integer"},"y":{"type":"integer"},
                      "end_x":{"type":"integer"},"end_y":{"type":"integer"},
                      "duration_ms":{"type":"integer","minimum":0,"maximum":10000},
                      "settle_ms":{"type":"integer","minimum":0,"maximum":1500,"description":"动作后在本地等待界面稳定的时间"},
                      "text":{"type":"string","maxLength":500,"description":"仅 input_text 使用"},
                      "input_mode":{"type":"string","enum":["replace","append"],"default":"replace"},
                      "package_name":{"type":"string","description":"open_app 的包名，或 wait_until 等待的前台应用"},
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
        """.trimIndent()
    }
}
