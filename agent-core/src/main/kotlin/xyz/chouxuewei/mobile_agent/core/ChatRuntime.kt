package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ChatConnection(
    val gateway: ChatModelGateway,
    val policy: ContextPolicy,
    val model: String,
    val modelProfileId: String? = null,
    val modelName: String = model,
    /** 模型不支持图片输入时丢弃工具返回的截图，避免服务商拒绝请求。 */
    val supportsImages: Boolean = true,
)

/** 一次由模型服务明确返回的真实用量；持久化由应用层注入，核心运行时不依赖具体存储。 */
data class ModelUsageRecord(
    val modelProfileId: String?,
    val modelName: String,
    val modelId: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val recordedAt: Long = System.currentTimeMillis(),
)

private const val MAX_INLINE_TOOL_RESULT_BYTES = 12_000
private const val TOOL_RESULT_EXCERPT_BYTES = 8_000
/** 保底截断长度（字符）：服务端判定超限或前两段压缩无效时，单个 tool 结果只保留这段摘录。 */
private const val TOOL_RESULT_HARD_EXCERPT_CHARS = 1_500
private const val MAX_INLINE_IMAGE_BYTES = 20L * 1024 * 1024
private val TOOL_IMAGE_MARKER: String
    get() = localizedText(
        "[设备识别图片，仅作为上一组工具结果",
        "[Device screenshot from the preceding tool results",
    )
private val ACTIVE_COMPACTION_MARKER: String
    get() = localizedText("[较早执行步骤已自动压缩", "[Earlier execution steps were compressed automatically")
private val SINGLE_STEP_RESULT_PLACEHOLDER: String
    get() {
        val message = localizedText(
            "界面识别结果仅在紧接着的一次模型决策中有效；节点和图片已自动清除，需要继续操作时请重新识别界面。",
            "A screen observation is valid only for the immediately following model decision. Its nodes and image were cleared; inspect the screen again before continuing.",
        )
        return "{\"expired\":true,\"message\":\"$message\"}"
    }

private fun isSingleStepResultPlaceholder(value: String): Boolean =
    value == "{\"expired\":true,\"message\":\"界面识别结果仅在紧接着的一次模型决策中有效；节点和图片已自动清除，需要继续操作时请重新识别界面。\"}" ||
        value == "{\"expired\":true,\"message\":\"A screen observation is valid only for the immediately following model decision. Its nodes and image were cleared; inspect the screen again before continuing.\"}"

private data class RequestPreferences(
    val reasoningEffort: String?,
    val modelProfileId: String?,
)

/** Application 拥有此运行时；页面、主题以及 Activity 重建都不拥有请求的生命周期。 */
class ChatRuntime(
    private val store: ConversationStore,
    private val connection: suspend (modelProfileId: String?) -> ChatConnection,
    private val scope: CoroutineScope,
    private val tools: ToolRegistry? = null,
    private val toolPermissions: ToolPermissionStore? = null,
    private val attachmentLoader: ChatAttachmentLoader? = null,
    private val usageRecorder: suspend (ModelUsageRecord) -> Unit = {},
    private val personalizedInstructions: suspend () -> String = { "" },
    private val maxStepsPerRun: suspend () -> Int = { DEFAULT_SINGLE_RUN_MAX_STEPS },
    /** 每轮解析出模型连接后调用；返回 null 表示安全闸关闭。 */
    private val safetyGate: suspend (connection: ChatConnection, runPolicy: RunPolicy?) -> DecisionGate? = { _, _ -> null },
    /** 低风险导航快路径能力；null 或未开启加速时不启用。 */
    private val fastPath: FastPathSupport? = null,
) {
    private val gate = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val preferencesByTrigger = mutableMapOf<String, RequestPreferences>()
    private val policiesByTrigger = mutableMapOf<String, RunPolicy>()
    private val mutableActive = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = mutableActive
    private val mutableNotices = MutableStateFlow<Map<String, String>>(emptyMap())
    val notices: StateFlow<Map<String, String>> = mutableNotices
    private val mutableContextUsage = MutableStateFlow<Map<String, ContextUsage>>(emptyMap())
    val contextUsage: StateFlow<Map<String, ContextUsage>> = mutableContextUsage
    private val approvalWaiters = mutableMapOf<String, CompletableDeferred<ToolApprovalDecision>>()
    private val mutableApprovals = MutableStateFlow<Map<String, ToolApprovalRequest>>(emptyMap())
    val approvals: StateFlow<Map<String, ToolApprovalRequest>> = mutableApprovals
    private val context = ContextManager(store, personalizedInstructions)
    private val recovery = scope.async {
        store.recoverInterrupted()
        // 进程若在识别完成后被结束，启动恢复仍会清除上次遗留的一次性节点结果。
        store.expireToolResults(singleStepToolIds(), SINGLE_STEP_RESULT_PLACEHOLDER)
    }

    suspend fun ready() { recovery.await() }

    suspend fun send(
        id: String,
        text: String,
        attachments: List<AttachmentRef>,
        reasoningEffort: String? = null,
        modelProfileId: String? = null,
        policy: RunPolicy? = null,
    ) {
        require(text.isNotBlank() || attachments.any(AttachmentRef::isImage)) {
            localizedText("请输入文字说明你想如何处理附件", "Describe how you want to handle the attachment.")
        }
        recovery.await()
        gate.withLock {
            val trigger = store.enqueue(id, text.trim(), attachments)
            // 队列中的消息绑定发送时的模型与思考强度，随后切换只影响新消息。
            preferencesByTrigger[trigger.id] = RequestPreferences(reasoningEffort, modelProfileId)
            if (policy != null) policiesByTrigger[trigger.id] = policy
            // 被取消的协程仍可能正在落盘；等 finally 移除所有权后才可启动下一轮。
            if (id !in jobs) startLocked(id)
        }
    }

    private fun startLocked(id: String, manual: Boolean = false) {
        mutableNotices.update { it - id }
        mutableActive.update { it + id }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var continueQueued = true
            try {
                if (manual) {
                    val c = connection(null)
                    val definitions = enabledDefinitions()
                    val turns = context.prepare(id, Long.MAX_VALUE, c.policy, c.model, c.gateway, manual = true,
                        onStatus = { notice(id, it) }, tools = definitions)
                    publishContextUsage(id, c.policy, c.modelProfileId, turns, definitions, compacted = true)
                } else drain(id)
            } catch (cancelled: CancellationException) {
                notice(id, localizedText("已停止回复，已生成内容和对话记录均已保留", "Response stopped. Generated content and conversation history were preserved."))
            } catch (error: Exception) {
                continueQueued = false
                notice(id, userFacingMessage(error, localizedText("请求未完成，请重试", "The request was not completed. Please try again.")))
            } finally {
                withContext(NonCancellable) {
                    gate.withLock {
                        jobs.remove(id)
                        mutableActive.update { it - id }
                        // 无论自然结束还是主动停止，已收下的补充消息都在安全边界继续处理。
                        if (continueQueued && store.messages(id).any { it.status == MessageStatus.QUEUED }) startLocked(id)
                    }
                }
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun drain(id: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val trigger = store.messages(id).firstOrNull { it.status == MessageStatus.QUEUED } ?: return
            val (preferences, runPolicy) = gate.withLock {
                preferencesByTrigger.remove(trigger.id) to policiesByTrigger.remove(trigger.id)
            }
            val reasoningEffort = preferences?.reasoningEffort
            var run: Run? = null
            val output = StringBuilder()
            val assistantSteps = mutableListOf<StreamingAssistantStep>()
            var workingTurnsForCleanup: MutableList<ChatTurn>? = null
            val pendingSingleStepResults = linkedSetOf<String>()
            fun stepsSnapshot(now: Long = System.currentTimeMillis()) = assistantSteps.map { it.snapshot(now) }
            fun reasoningDuration(now: Long = System.currentTimeMillis()) =
                stepsSnapshot(now).mapNotNull(AssistantStep::reasoningDurationMillis).sum().takeIf { it > 0L }
            try {
                // 配置失败也要消费本次排队并保留失败记录，避免无限重试循环。
                val startedRun = withContext(NonCancellable) { store.beginRun(id, trigger.id, localizedText("待连接", "Waiting to connect")) }
                run = startedRun
                currentCoroutineContext().ensureActive()
                val c = connection(preferences?.modelProfileId)
                store.setRunModel(startedRun, c.model)
                val activeRun = startedRun.copy(model = c.model)
                run = activeRun
                // 无人值守运行的 scope 先于工具表生效：模型只能看到被授权的工具。
                val definitions = enabledDefinitions().let { defs ->
                    runPolicy?.let { p -> defs.filter { it.id in p.allowedToolIds } } ?: defs
                }
                val activeGate = runPolicy?.gate ?: safetyGate(c, runPolicy)
                // 每轮开始时只读取一次，避免用户在执行中修改设置导致当前任务的上限突然变化。
                val maxSteps = requireValidSingleRunMaxSteps(maxStepsPerRun())
                AgentLog.i("Runtime") {
                    "run_start run=${activeRun.id} conversation=$id model=${c.model} tools=${definitions.size} max_steps=$maxSteps"
                }
                val workingTurns = resolveImages(context.prepare(
                    id, trigger.sequence, c.policy, c.model, c.gateway,
                    onStatus = { notice(id, it) }, tools = definitions,
                )).toMutableList()
                workingTurnsForCleanup = workingTurns
                val activeBaseTurnCount = workingTurns.size
                var contextWasCompacted = false
                var toolRound = 0
                var overflowRecovery = 1
                while (true) {
                    check(toolRound <= maxSteps) {
                        localizedText("已达到单轮最大步骤（$maxSteps），可在设置中调整后重试", "The maximum steps for one run ($maxSteps) was reached. Adjust it in Settings and try again.")
                    }
                    var finished = false
                    var finishReason = ""
                    var overflowed = false
                    val step = StreamingAssistantStep()
                    assistantSteps += step
                    val requestedCalls = mutableListOf<RequestedToolCall>()
                    if (compactActiveContext(workingTurns, activeBaseTurnCount, c.policy, definitions)) {
                        contextWasCompacted = true
                        notice(id, localizedText("上下文已自动压缩，任务继续执行", "Context was compressed automatically and the task will continue."))
                    }
                    context.requireRequestFits(workingTurns, c.policy, definitions)
                    publishContextUsage(
                        id, c.policy, c.modelProfileId, workingTurns, definitions,
                        compacted = contextWasCompacted,
                    )
                    val requestStartedAt = System.currentTimeMillis()
                    var reportedUsage: ModelEvent.Usage? = null
                    AgentLog.d("Runtime") {
                        "model_request run=${activeRun.id} round=$toolRound turns=${workingTurns.size} tools=${definitions.size} images=${workingTurns.sumOf { it.images.size }}"
                    }
                    try {
                        c.gateway.stream(ChatRequest(
                            workingTurns, c.policy.outputReserve, reasoningEffort, definitions,
                        )).collect { event ->
                            when (event) {
                                is ModelEvent.TextDelta -> {
                                    step.finishReasoning()
                                    step.text.append(event.text)
                                    output.append(event.text)
                                    store.updateReply(activeRun, output.toString(), stepsSnapshot())
                                }
                                is ModelEvent.ReasoningDelta -> {
                                    step.appendReasoning(event.text)
                                    store.updateReply(activeRun, output.toString(), stepsSnapshot())
                                }
                                is ModelEvent.ToolCall -> requestedCalls += event.call
                                is ModelEvent.Error -> {
                                    // 服务端判定上下文超限且本轮尚未产出内容：放弃本次请求，
                                    // 压缩后按原轮次重试一次，而不是直接判死整个 run。
                                    if (event.contextOverflow && overflowRecovery > 0 &&
                                        step.text.isEmpty() && step.reasoning.isEmpty() && requestedCalls.isEmpty()
                                    ) {
                                        overflowRecovery = 0
                                        overflowed = true
                                    } else error(event.message)
                                }
                                is ModelEvent.Completed -> {
                                    finished = true
                                    finishReason = event.reason
                                }
                                is ModelEvent.Usage -> {
                                    // 某些兼容服务会分多次发送 usage；同一请求只累计一次，并保留最后出现的各字段。
                                    reportedUsage = ModelEvent.Usage(
                                        inputTokens = event.inputTokens ?: reportedUsage?.inputTokens,
                                        outputTokens = event.outputTokens ?: reportedUsage?.outputTokens,
                                    )
                                    event.inputTokens?.let { actual ->
                                        AgentLog.d("Runtime") {
                                            "model_usage run=${activeRun.id} round=$toolRound input=$actual output=${event.outputTokens ?: -1}"
                                        }
                                        publishContextUsage(
                                            id, c.policy, c.modelProfileId, workingTurns, definitions,
                                            actualInputTokens = actual,
                                            compacted = contextWasCompacted,
                                        )
                                    }
                                }
                            }
                        }
                    } finally {
                        // 模型完成本次决策后，节点和截图已经完成使命；下一步只能重新识别。
                        val expired = withContext(NonCancellable) {
                            reportedUsage?.let { recordModelUsage(c, it) }
                            expireSingleStepResults(workingTurns, pendingSingleStepResults)
                        }
                        // 清理改变了下一次请求的上下文；若没有清理，则保留服务端返回的真实 usage。
                        if (expired) {
                            publishContextUsage(
                                id, c.policy, c.modelProfileId, workingTurns, definitions,
                                compacted = contextWasCompacted,
                            )
                        }
                    }
                    AgentLog.d("Runtime") {
                        "model_response run=${activeRun.id} round=$toolRound duration_ms=${System.currentTimeMillis() - requestStartedAt} tool_calls=${requestedCalls.size} finish=$finishReason"
                    }
                    step.finishReasoning()
                    if (overflowed) {
                        // 本次请求未产出任何内容：丢弃空步骤、强力压缩（含最新轮工具结果截断）、原轮重试；
                        // 实在无可压缩内容时不再浪费一次往返，按原错误失败。
                        assistantSteps.removeAt(assistantSteps.lastIndex)
                        if (compactActiveContext(workingTurns, activeBaseTurnCount, c.policy, definitions, aggressive = true)) {
                            contextWasCompacted = true
                            notice(id, localizedText("服务端判定上下文超限，已压缩上下文并重试", "The service reported a context overflow; context was compressed and the request retried."))
                            continue
                        }
                        error(localizedText("服务端判定上下文超限且无可压缩内容，请缩短输入或增大上下文长度", "The service reported a context overflow and nothing more could be compressed. Shorten the input or increase the context length."))
                    }
                    check(finished) { localizedText("回复意外中断，已生成的内容已保留", "The response was interrupted. Generated content was preserved.") }
                    if (requestedCalls.isEmpty()) {
                        store.updateReply(activeRun, output.toString(), stepsSnapshot())
                        if (finishReason == "length") notice(id, localizedText("本次回复已达到长度上限，你可以继续追问", "This response reached the length limit. You can ask a follow-up."))
                        break
                    }
                    check(definitions.isNotEmpty()) { localizedText("模型请求了本轮未提供的工具", "The model requested a tool that was not provided for this run.") }
                    check(toolRound < maxSteps) {
                        localizedText("已达到单轮最大步骤（$maxSteps），可在设置中调整后重试", "The maximum steps for one run ($maxSteps) was reached. Adjust it in Settings and try again.")
                    }
                    toolRound++
                    AgentLog.i("Runtime") {
                        "tool_round run=${activeRun.id} round=$toolRound tools=${requestedCalls.joinToString(",") { it.toolId }}"
                    }
                    // 部分兼容服务会在不同响应里复用 call ID；轮次和序号一起进入本地 ID，避免覆盖旧记录。
                    val requestedRecords = requestedCalls.mapIndexed { index, requested ->
                        requested to "${activeRun.id}:$toolRound:$index:${requested.id}"
                    }
                    step.toolCallIds += requestedRecords.map { it.second }
                    store.updateReply(activeRun, output.toString(), stepsSnapshot())
                    workingTurns += ChatTurn("assistant", step.text.toString(), requestedCalls)
                    // 同一轮全是只读工具时并行执行（如同时读多个文件/抓多个网页）；
                    // 任何写操作保持顺序执行，保证动作之间的确定性。
                    val allReadOnly = requestedRecords.all { (requested, _) ->
                        definitions.firstOrNull { it.id == requested.toolId }?.sideEffect == ToolSideEffect.READ
                    }
                    val results = if (allReadOnly && requestedRecords.size > 1) {
                        coroutineScope {
                            requestedRecords.map { (requested, recordId) ->
                                async { executeToolCall(activeRun, trigger.text, requested, definitions, recordId, activeGate, runPolicy) }
                            }.awaitAll()
                        }
                    } else {
                        requestedRecords.map { (requested, recordId) ->
                            executeToolCall(activeRun, trigger.text, requested, definitions, recordId, activeGate, runPolicy)
                        }
                    }
                    val toolImages = mutableListOf<ChatImage>()
                    requestedRecords.forEachIndexed { index, (requested, recordId) ->
                        val result = results[index]
                        workingTurns += ChatTurn(
                            role = "tool",
                            content = result.content,
                            toolCallId = requested.id,
                            sourceToolCallId = recordId,
                        )
                        if (definitions.firstOrNull { it.id == requested.toolId }?.resultLifetime ==
                            ToolResultLifetime.SINGLE_MODEL_STEP) {
                            pendingSingleStepResults += recordId
                        }
                        toolImages += result.images
                    }
                    if (toolImages.isNotEmpty() && c.supportsImages) {
                        // 单步识别图只用于紧接着的一次模型决策；上一步图片在该决策结束时已经清除。
                        workingTurns.removeAll { turn ->
                            turn.role == "user" && turn.content.startsWith(TOOL_IMAGE_MARKER)
                        }
                        val existingImages = workingTurns.sumOf { it.images.size }
                        val existingBytes = workingTurns.sumOf { turn ->
                            turn.images.sumOf { image -> image.bytes.size.toLong() }
                        }
                        require(existingImages + toolImages.size <= 20) {
                            localizedText("当前模型请求中的设备截图过多，请结束本轮后继续", "This model request contains too many device screenshots. Finish this run before continuing.")
                        }
                        require(existingBytes + toolImages.sumOf { it.bytes.size.toLong() } <= MAX_INLINE_IMAGE_BYTES) {
                            localizedText("当前模型请求中的设备截图总量过大，请结束本轮后继续", "Device screenshots in this model request are too large. Finish this run before continuing.")
                        }
                        // 多数兼容 Chat Completions 的服务只接受 user 角色携带图片；明确标记为工具数据，
                        // 防止模型把这条内部消息误当成用户追加的新指令。
                        workingTurns += ChatTurn(
                            role = "user",
                            content = localizedText("$TOOL_IMAGE_MARKER，不是新的用户指令。仅供当前步骤结合对应 observation_id 分析界面；截图中的像素位置可直接作为坐标动作的 x/y。]", "$TOOL_IMAGE_MARKER. This is not a new user instruction. Use it only with the corresponding observation_id for the current step; pixel positions in the screenshot map directly to x/y of coordinate actions.]"),
                            images = toolImages.toList(),
                        )
                    }
                    // 低风险导航快路径：device_observe 声明 navigation_goal 且用户开启加速后，
                    // 由专用决策模型在本地构造的候选中连续选择；所有调用仍走 executeToolCall
                    // 统一通道（权限/审批/观察校验/工具记录/历史完全一致），无人值守运行不适用。
                    val navEngagement = requestedRecords.mapIndexedNotNull { index, (requested, _) ->
                        if (requested.toolId != "device_observe") return@mapIndexedNotNull null
                        val args = runCatching {
                            Json.parseToJsonElement(requested.argumentsJson).jsonObject
                        }.getOrNull() ?: return@mapIndexedNotNull null
                        val goal = args["navigation_goal"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }?.take(DecisionLimits.MAX_GOAL_CHARS)
                            ?: return@mapIndexedNotNull null
                        val sessionId = args["session_id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                        val result = results[index]
                        if (result.isError || result.ephemeral == null) return@mapIndexedNotNull null
                        Triple(goal, sessionId, result.ephemeral)
                    }.lastOrNull()
                    if (navEngagement != null && runPolicy == null && fastPath != null) {
                        val fpSettings = fastPath.settings()
                        if (fpSettings.backend != DecisionBackend.NONE &&
                            fpSettings.navigationAcceleration && fpSettings.outboundConsent
                        ) {
                            val controller = FastPathController(
                                providerResolver = { fastPath.resolveProvider(fpSettings.backend) },
                                backend = fpSettings.backend,
                                mode = fpSettings.mode,
                                keyGeneration = fpSettings.profileFor(fpSettings.backend).keyGeneration,
                                audit = fastPath.audit,
                            )
                            var fpSeq = 0
                            val fpResult = controller.run(
                                goal = navEngagement.first,
                                sessionId = navEngagement.second,
                                userRequest = trigger.text,
                                initialObservation = navEngagement.third,
                            ) { toolId, args ->
                                val seq = ++fpSeq
                                val call = RequestedToolCall(
                                    id = "fastpath:${activeRun.id}:$seq",
                                    toolId = toolId,
                                    argumentsJson = args.toString(),
                                )
                                val recordId = "${activeRun.id}:fp:$seq"
                                step.toolCallIds += recordId
                                workingTurns += ChatTurn("assistant", "", listOf(call))
                                val r = executeToolCall(
                                    activeRun, trigger.text, call, definitions, recordId,
                                    activeGate, runPolicy,
                                )
                                workingTurns += ChatTurn(
                                    role = "tool",
                                    content = r.content,
                                    toolCallId = call.id,
                                    sourceToolCallId = recordId,
                                )
                                if (definitions.firstOrNull { it.id == toolId }?.resultLifetime ==
                                    ToolResultLifetime.SINGLE_MODEL_STEP
                                ) {
                                    pendingSingleStepResults += recordId
                                }
                                store.updateReply(activeRun, output.toString(), stepsSnapshot())
                                r
                            }
                            when (fpResult) {
                                FastPathController.Result.Completed -> notice(
                                    id, localizedText("导航目标已达成", "Navigation goal reached"),
                                )
                                is FastPathController.Result.Fallback -> AgentLog.d("Runtime") {
                                    "fast path fell back to planner: ${fpResult.reason}"
                                }
                            }
                        }
                    }
                }
                check(output.isNotBlank()) { localizedText("模型没有返回正文，请检查回复预留和模型设置", "The model returned no response text. Check the output reserve and model settings.") }
                store.finishRun(
                    activeRun,
                    output.toString(),
                    stepsSnapshot(),
                    reasoningDuration(),
                    RunStatus.SUCCEEDED,
                )
                AgentLog.i("Runtime") {
                    "run_finish run=${activeRun.id} status=succeeded rounds=$toolRound output_chars=${output.length}"
                }
            } catch (cancelled: CancellationException) {
                AgentLog.w("Runtime", cancelled) { "run_finish run=${run?.id} status=cancelled" }
                withContext(NonCancellable) {
                    run?.let {
                        store.finishRun(
                            it,
                            output.toString(),
                            stepsSnapshot(),
                            reasoningDuration(),
                            RunStatus.CANCELLED,
                            localizedText("用户已停止", "Stopped by user"),
                        )
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                AgentLog.e("Runtime", error) { "run_finish run=${run?.id} status=failed" }
                val reason = userFacingMessage(error, localizedText("请求未完成，请重试", "The request was not completed. Please try again."))
                run?.let {
                    store.finishRun(
                        it,
                        output.toString(),
                        stepsSnapshot(),
                        reasoningDuration(),
                        RunStatus.FAILED,
                        reason,
                    )
                }
                if (run == null) throw error
                // 失败原因已经跟随消息展示，避免时间线再重复显示同一段错误。
                mutableNotices.update { it - id }
            } finally {
                val cleanupTurns = workingTurnsForCleanup
                if (cleanupTurns != null && pendingSingleStepResults.isNotEmpty()) {
                    withContext(NonCancellable) {
                        expireSingleStepResults(cleanupTurns, pendingSingleStepResults)
                    }
                }
                run?.let { activeRun ->
                    // 工具资源只属于当前 Run；正常结束、失败和停止都执行同一条兜底清理路径。
                    withContext(NonCancellable) {
                        tools?.finish(ToolExecutionContext(
                            activeRun.conversationId,
                            activeRun.id,
                            activeRun.replyMessageId,
                            trigger.text,
                        ))
                    }
                }
            }
        }
    }

    private suspend fun executeToolCall(
        run: Run,
        userRequest: String,
        requested: RequestedToolCall,
        definitions: List<ToolDefinition>,
        recordId: String,
        activeGate: DecisionGate? = null,
        runPolicy: RunPolicy? = null,
    ): ToolResult {
        val now = System.currentTimeMillis()
        var record = ToolCallRecord(
            id = recordId,
            conversationId = run.conversationId,
            runId = run.id,
            replyMessageId = run.replyMessageId,
            toolId = requested.toolId,
            argumentsJson = requested.argumentsJson,
            status = ToolCallStatus.RECEIVED,
            createdAt = now,
        )
        store.saveToolCall(record)
        val definition = definitions.firstOrNull { it.id == requested.toolId }
        if (definition == null || requested.argumentsJson.length > 100_000) {
            val internalMessage = if (definition == null) localizedText("本轮未提供工具 ${requested.toolId}", "Tool ${requested.toolId} was not provided for this run") else localizedText("工具参数过大", "Tool arguments are too large")
            val message = if (definition == null) localizedText("模型请求了当前不可用的工具", "The model requested a tool that is currently unavailable") else localizedText("工具请求内容过大，无法执行", "The tool request is too large to execute")
            record = record.copy(status = ToolCallStatus.FAILED, error = internalMessage, displaySummary = message,
                updatedAt = System.currentTimeMillis())
            store.updateToolCall(record)
            return ToolResult("{\"error\":\"$internalMessage\"}", message, true)
        }

        val access = toolPermissions?.access(definition.providerId) ?: ToolAccess()
        if (!access.enabled) {
            val message = localizedText("“${definition.title}”已关闭", "“${definition.title}” is disabled")
            record = record.copy(status = ToolCallStatus.DENIED, error = message,
                displaySummary = message, updatedAt = System.currentTimeMillis())
            store.updateToolCall(record)
            val errorContent = localizedText("能力已关闭", "Capability disabled")
            return ToolResult("{\"error\":\"$errorContent\"}", message, true)
        }

        // 外部副作用动作先过安全闸：拒绝直接结束，需要确认则并入审批通道。
        var gateConfirmReason: String? = null
        if (activeGate != null && definition.sideEffect in GATED_SIDE_EFFECTS) {
            when (val verdict = activeGate.gate(
                GateRequest(
                    userRequest = userRequest,
                    toolId = requested.toolId,
                    toolTitle = definition.title,
                    argumentsSummary = tools?.approvalSummary(requested)
                        ?: requested.argumentsJson.take(500),
                )
            )) {
                is GateVerdict.Block -> {
                    val reason = verdict.reason
                    val message = localizedText("安全策略已阻止该操作：$reason", "Safety policy blocked this action: $reason")
                    record = record.copy(status = ToolCallStatus.DENIED, error = message,
                        displaySummary = message, updatedAt = System.currentTimeMillis())
                    store.updateToolCall(record)
                    return ToolResult(
                        "{\"error\":\"${localizedText("操作被安全策略阻止", "Action blocked by safety policy")}\",\"reason\":\"${reason.replace("\"", "'")}\"}",
                        message, true,
                    )
                }
                is GateVerdict.Confirm -> gateConfirmReason = verdict.reason
                GateVerdict.Allow -> Unit
            }
        }

        // 无人值守策略下没有审批界面：scope 内工具按预授权执行，闸仍要求确认的一律拒绝，
        // 需要模式选择的取第一个可选项保证确定性。
        val autoApprove = runPolicy?.autoApproveWithinScope == true
        val requiresPermissionApproval = definition.requiresPermissionApproval &&
            access.permission == ToolPermissionMode.REQUEST_APPROVAL && !autoApprove
        if (requiresPermissionApproval || gateConfirmReason != null || definition.userChoices.isNotEmpty()) {
            if (autoApprove) {
                if (gateConfirmReason != null) {
                    val message = localizedText("安全闸要求人工确认，该触发器无法执行此操作", "The safety gate requires manual confirmation; this trigger cannot perform the action.")
                    record = record.copy(status = ToolCallStatus.DENIED, error = message,
                        displaySummary = message, updatedAt = System.currentTimeMillis())
                    store.updateToolCall(record)
                    return ToolResult("{\"error\":\"$message\"}", message, true)
                }
                if (definition.userChoices.isNotEmpty()) {
                    record = record.copy(argumentsJson = definition.userChoices.first().argumentsJson,
                        updatedAt = System.currentTimeMillis())
                    store.updateToolCall(record)
                }
            } else {
            record = record.copy(status = ToolCallStatus.WAITING_APPROVAL, updatedAt = System.currentTimeMillis())
            store.updateToolCall(record)
            val waiter = CompletableDeferred<ToolApprovalDecision>()
            gate.withLock {
                approvalWaiters[record.id] = waiter
                mutableApprovals.update { it + (record.id to ToolApprovalRequest(
                    callId = record.id,
                    conversationId = record.conversationId,
                    toolId = record.toolId,
                    capabilityTitle = tools?.capabilityTitle(definition.providerId) ?: definition.title,
                    actionTitle = definition.title,
                    description = listOfNotNull(definition.approvalDescription, gateConfirmReason)
                        .joinToString("\n"),
                    argumentsJson = record.argumentsJson,
                    argumentsSummary = tools?.approvalSummary(requested),
                    choices = definition.userChoices,
                    requiresPermissionApproval = requiresPermissionApproval,
                )) }
            }
            val decision = try {
                waiter.await()
            } catch (cancelled: CancellationException) {
                record = record.copy(status = ToolCallStatus.CANCELLED, error = localizedText("等待确认时已停止", "Stopped while waiting for approval"),
                    displaySummary = localizedText("已停止等待确认", "Stopped waiting for approval"), updatedAt = System.currentTimeMillis())
                withContext(NonCancellable) { store.updateToolCall(record) }
                throw cancelled
            } finally {
                gate.withLock {
                    approvalWaiters.remove(record.id)
                    mutableApprovals.update { it - record.id }
                }
            }
            if (!decision.allowed) {
                val message = localizedText("未允许“${definition.title}”", "“${definition.title}” was not allowed")
                record = record.copy(status = ToolCallStatus.DENIED, error = message,
                    displaySummary = message, updatedAt = System.currentTimeMillis())
                store.updateToolCall(record)
                val errorContent = localizedText("用户拒绝了本次工具调用", "The user denied this tool call")
                return ToolResult("{\"error\":\"$errorContent\"}", message, true)
            }
            if (definition.userChoices.isNotEmpty()) {
                val choice = definition.userChoices.firstOrNull { it.id == decision.choiceId }
                if (choice == null) {
                    val message = localizedText("未选择操作方式", "No operation mode was selected")
                    record = record.copy(status = ToolCallStatus.DENIED, error = message,
                        displaySummary = message, updatedAt = System.currentTimeMillis())
                    store.updateToolCall(record)
                    return ToolResult("{\"error\":\"$message\"}", message, true)
                }
                record = record.copy(argumentsJson = choice.argumentsJson, updatedAt = System.currentTimeMillis())
                store.updateToolCall(record)
            }
            if (decision.permanentlyAllowCapability && requiresPermissionApproval) {
                try {
                    requireNotNull(toolPermissions) { localizedText("工具授权存储不可用", "Tool permission storage is unavailable") }
                        .setPermission(definition.providerId, ToolPermissionMode.FULL_ACCESS)
                } catch (error: Exception) {
                    val message = localizedText("未能保存“始终允许”设置，请重试", "Could not save the Always allow setting. Please try again.")
                    record = record.copy(
                        status = ToolCallStatus.FAILED,
                        error = message,
                        displaySummary = message,
                        updatedAt = System.currentTimeMillis(),
                    )
                    store.updateToolCall(record)
                    val errorContent = localizedText("永久授权保存失败", "Failed to save permanent permission")
                    return ToolResult("{\"error\":\"$errorContent\"}", message, true)
                }
            }
            }
        }

        // 用户可能在批准卡片等待期间关闭能力，执行前再次读取持久化状态。
        if (toolPermissions?.access(definition.providerId)?.enabled == false) {
            val message = localizedText("“${definition.title}”已关闭", "“${definition.title}” is disabled")
            record = record.copy(status = ToolCallStatus.DENIED, error = message,
                displaySummary = message, updatedAt = System.currentTimeMillis())
            store.updateToolCall(record)
            val errorContent = localizedText("能力已关闭", "Capability disabled")
            return ToolResult("{\"error\":\"$errorContent\"}", message, true)
        }

        record = record.copy(status = ToolCallStatus.EXECUTING, updatedAt = System.currentTimeMillis())
        store.updateToolCall(record)
        val toolStartedAt = System.currentTimeMillis()
        AgentLog.i("Tool") { "tool_start run=${run.id} call=$recordId tool=${requested.toolId}" }
        val result = try {
            requireNotNull(tools).execute(
                requested.copy(argumentsJson = record.argumentsJson),
                ToolExecutionContext(
                    run.conversationId,
                    run.id,
                    run.replyMessageId,
                    userRequest,
                    record.id,
                ),
            )
        } catch (cancelled: CancellationException) {
            record = record.copy(status = ToolCallStatus.CANCELLED, error = localizedText("工具执行已停止", "Tool execution stopped"),
                displaySummary = localizedText("操作已停止", "Operation stopped"), updatedAt = System.currentTimeMillis())
            withContext(NonCancellable) { store.updateToolCall(record) }
            throw cancelled
        } catch (error: Exception) {
            val errorContent = localizedText("工具执行失败", "Tool execution failed")
            ToolResult("{\"error\":\"$errorContent\"}", userFacingMessage(error), true)
        }
        record = record.copy(
            status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.SUCCEEDED,
            result = result.content,
            displaySummary = result.summary,
            error = result.summary.takeIf { result.isError },
            updatedAt = System.currentTimeMillis(),
        )
        store.updateToolCall(record)
        AgentLog.i("Tool") {
            "tool_finish run=${run.id} call=$recordId tool=${requested.toolId} error=${result.isError} duration_ms=${System.currentTimeMillis() - toolStartedAt} result_chars=${result.content.length} images=${result.images.size}"
        }
        return result.copy(content = result.content.forModel(
            toolCallId = record.id,
            summary = result.summary,
            // 设备截图旁的节点语义是理解和执行下一步所必需的数据，允许比普通网页摘录更大的内联片段。
            inlineLimit = if (result.images.isEmpty()) MAX_INLINE_TOOL_RESULT_BYTES else 48_000,
            excerptLimit = if (result.images.isEmpty()) TOOL_RESULT_EXCERPT_BYTES else 40_000,
        ))
    }

    /**
     * 活跃工具循环达到 80% 时先收缩较早工具结果；仍偏高时把已经完成的旧步骤折叠成引用。
     * 最新一轮默认完整保留，因为模型还需要依据它决定紧接着的动作；
     * aggressive（服务端已判定超限，或前两段后仍超预算）时把最新轮的大工具结果也截断成摘录，
     * 宁可损失细节也不杀死整个 run——完整结果经 history_read 引用可取回。
     */
    private fun compactActiveContext(
        turns: MutableList<ChatTurn>,
        activeBaseTurnCount: Int,
        policy: ContextPolicy,
        definitions: List<ToolDefinition>,
        aggressive: Boolean = false,
    ): Boolean {
        if (!aggressive && !context.shouldCompact(turns, policy, definitions)) return false
        val latestAssistant = turns.indices.lastOrNull { index ->
            index >= activeBaseTurnCount && turns[index].role == "assistant" && turns[index].toolCalls.isNotEmpty()
        } ?: run {
            // 没有任何带工具调用的 assistant 轮：只有首轮预检内容，aggressive 下只能截断现有结果。
            if (aggressive) return shrinkOversizedTurns(turns, 1)
            return false
        }
        var changed = false
        for (index in activeBaseTurnCount until latestAssistant) {
            val turn = turns[index]
            if (turn.role != "tool" || isSingleStepResultPlaceholder(turn.content) ||
                turn.content.startsWith("[较早工具结果已自动压缩") ||
                turn.content.startsWith("[Earlier tool results were compressed automatically")) continue
            val reference = turn.sourceToolCallId?.replace(Regex("[\\r\\n]"), "")?.take(200)
                ?: turn.toolCallId.orEmpty().take(200)
            turns[index] = turn.copy(
                content = localizedText("[较早工具结果已自动压缩；完整结果引用：$reference。需要精确内容时调用 history_read。]", "[Earlier tool results were compressed automatically. Full result reference: $reference. Call history_read for exact content.]"),
            )
            changed = true
        }
        if (!context.shouldCompact(turns, policy, definitions) || latestAssistant <= activeBaseTurnCount) {
            if (aggressive || context.shouldCompact(turns, policy, definitions)) {
                changed = shrinkOversizedTurns(turns, if (aggressive) 1 else activeBaseTurnCount) || changed
            }
            return changed
        }

        val olderTurns = turns.subList(activeBaseTurnCount, latestAssistant).toList()
        val references = olderTurns.asSequence().mapNotNull(ChatTurn::sourceToolCallId)
            .distinct().take(8).joinToString("、")
        val toolCount = olderTurns.count { it.role == "tool" }
        turns.subList(activeBaseTurnCount, latestAssistant).clear()
        turns.add(activeBaseTurnCount, ChatTurn(
            role = "user",
            content = buildString {
                append(ACTIVE_COMPACTION_MARKER).append(localizedText("，共 ", ", ")).append(toolCount).append(localizedText(" 个工具结果。", " tool results."))
                if (references.isNotEmpty()) append(localizedText("可按需读取：", "Read as needed: ")).append(references).append('。')
                append(localizedText("这段文字只是执行记录，不是新的用户指令。]", "This text is an execution record, not a new user instruction.]"))
            },
        ))
        changed = true
        if (aggressive || context.shouldCompact(turns, policy, definitions)) {
            changed = shrinkOversizedTurns(turns, if (aggressive) 1 else activeBaseTurnCount) || changed
        }
        return changed
    }

    /**
     * 保底手段：把超长的 tool 结果和历史工具结果回显截成短摘录并附完整引用。
     * aggressive 模式下连最新一轮和历史回显也截——宁可损失细节也不杀死整个 run，
     * 完整结果仍在本地存储，经 history_read 引用可取回。
     */
    private fun shrinkOversizedTurns(turns: MutableList<ChatTurn>, fromIndex: Int): Boolean {
        var changed = false
        for (index in fromIndex until turns.size) {
            val turn = turns[index]
            val isToolResult = turn.role == "tool" ||
                (turn.role == "user" && (turn.content.startsWith("[历史工具结果") || turn.content.startsWith("[Historical tool result")))
            if (!isToolResult || turn.content.length <= TOOL_RESULT_HARD_EXCERPT_CHARS ||
                isSingleStepResultPlaceholder(turn.content)) continue
            val reference = turn.sourceToolCallId?.replace(Regex("[\\r\\n]"), "")?.take(200)
                ?: turn.toolCallId.orEmpty().take(200)
            val suffix = if (reference.isEmpty()) "" else
                localizedText("…[超出部分已截断；完整结果引用：$reference，可用 history_read 分段读取]", "…[truncated; full result reference: $reference — read via history_read in chunks]")
            turns[index] = turn.copy(content = turn.content.take(TOOL_RESULT_HARD_EXCERPT_CHARS) + suffix)
            changed = true
        }
        return changed
    }

    /** 模型完成一次决策后立即同时清理内存、数据库中的临时节点结果以及对应图片。 */
    private suspend fun expireSingleStepResults(
        turns: MutableList<ChatTurn>,
        recordIds: MutableSet<String>,
    ): Boolean {
        if (recordIds.isEmpty()) return false
        turns.indices.forEach { index ->
            val turn = turns[index]
            if (turn.sourceToolCallId in recordIds) {
                turns[index] = turn.copy(content = SINGLE_STEP_RESULT_PLACEHOLDER)
            }
        }
        turns.removeAll { turn -> turn.role == "user" && turn.content.startsWith(TOOL_IMAGE_MARKER) }
        recordIds.forEach { recordId ->
            store.toolCall(recordId)?.let { record ->
                if (record.result != SINGLE_STEP_RESULT_PLACEHOLDER) {
                    store.updateToolCall(record.copy(
                        result = SINGLE_STEP_RESULT_PLACEHOLDER,
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            }
        }
        recordIds.clear()
        return true
    }

    private fun publishContextUsage(
        conversationId: String,
        policy: ContextPolicy,
        modelProfileId: String?,
        turns: List<ChatTurn>,
        definitions: List<ToolDefinition>,
        actualInputTokens: Int? = null,
        compacted: Boolean = false,
    ) {
        mutableContextUsage.update { current ->
            current + (conversationId to ContextUsage(
                inputTokens = actualInputTokens ?: context.estimateRequest(turns, definitions),
                outputReserve = policy.outputReserve,
                windowTokens = policy.windowTokens,
                exact = actualInputTokens != null,
                modelProfileId = modelProfileId,
                compacted = compacted,
            ))
        }
    }

    private suspend fun recordModelUsage(connection: ChatConnection, usage: ModelEvent.Usage) {
        if (usage.inputTokens == null && usage.outputTokens == null) return
        try {
            usageRecorder(
                ModelUsageRecord(
                    modelProfileId = connection.modelProfileId,
                    modelName = connection.modelName,
                    modelId = connection.model,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                ),
            )
        } catch (error: Exception) {
            // 用量统计失败不能让已经完成的模型回复变成失败；保留诊断信息即可。
            AgentLog.w("Runtime", error) { "model_usage_record_failed model=${connection.model}" }
        }
    }

    private fun singleStepToolIds(): Set<String> = tools?.definitions.orEmpty()
        .filter { it.resultLifetime == ToolResultLifetime.SINGLE_MODEL_STEP }
        .mapTo(linkedSetOf(), ToolDefinition::id)

    suspend fun stop(id: String) { gate.withLock { jobs[id]?.cancel() } }
    suspend fun decideTool(
        callId: String,
        allow: Boolean,
        choiceId: String? = null,
        permanentlyAllowCapability: Boolean = false,
    ) {
        gate.withLock {
            approvalWaiters[callId]?.complete(
                ToolApprovalDecision(allow, choiceId, permanentlyAllowCapability),
            )
        }
    }
    suspend fun compact(id: String) {
        recovery.await()
        gate.withLock {
            if (id in jobs) { notice(id, localizedText("请等待当前回复完成，或停止回复后再整理", "Wait for the current response to finish, or stop it before summarizing.")); return }
            startLocked(id, manual = true)
        }
    }
    private suspend fun enabledDefinitions(): List<ToolDefinition> {
        val available = tools?.availableDefinitions().orEmpty()
        val accessByCapability = available.map(ToolDefinition::providerId).distinct().associateWith { capabilityId ->
            toolPermissions?.access(capabilityId) ?: ToolAccess()
        }
        return available.filter { accessByCapability.getValue(it.providerId).enabled }
    }

    private suspend fun resolveImages(turns: List<ChatTurn>): List<ChatTurn> {
        val imageCount = turns.sumOf { turn -> turn.attachmentRefs.count(AttachmentRef::isImage) }
        require(imageCount <= 20) { localizedText("当前对话中的图片过多，请新建对话或减少附件后重试", "This conversation contains too many images. Start a new conversation or remove attachments and try again.") }
        if (imageCount == 0) return turns
        val loader = requireNotNull(attachmentLoader) { localizedText("当前版本未配置图片读取能力", "Image reading is not configured in this version.") }
        var loadedBytes = 0L
        return turns.map { turn ->
            val refs = turn.attachmentRefs.filter(AttachmentRef::isImage)
            if (refs.isEmpty()) turn else turn.copy(images = refs.map { attachment ->
                val image = try {
                    loader.loadImage(attachment)
                } catch (error: Exception) {
                    val reason = userFacingMessage(error, localizedText("文件不可用", "File unavailable"))
                    throw IllegalArgumentException(
                        localizedText(
                            "无法读取图片“${attachment.name}”：$reason",
                            "Could not read image “${attachment.name}”: $reason",
                        ),
                        error,
                    )
                }
                loadedBytes += image.bytes.size
                require(loadedBytes <= MAX_INLINE_IMAGE_BYTES) {
                    localizedText("当前对话中的图片总量过大，请新建对话或减少附件后重试", "Images in this conversation are too large. Start a new conversation or remove attachments and try again.")
                }
                image
            })
        }
    }
    private fun notice(id: String, message: String) { mutableNotices.update { it + (id to message) } }
}

/**
 * 完整结果已经落到 ToolCallRecord。当前模型轮次只带有限片段，后续可用 history_read
 * 携带 tool_call_id、offset 继续读取，避免一次网页或设备识别结果挤满上下文。
 */
private fun String.forModel(
    toolCallId: String,
    summary: String,
    inlineLimit: Int = MAX_INLINE_TOOL_RESULT_BYTES,
    excerptLimit: Int = TOOL_RESULT_EXCERPT_BYTES,
): String {
    if (toByteArray(Charsets.UTF_8).size <= inlineLimit) return this
    val excerpt = takeUtf8Bytes(excerptLimit)
    return buildString {
        append(localizedText("工具结果过长，完整内容已保存。\n", "The tool result is too long. The full content was saved.\n"))
        append("tool_call_id: ").append(toolCallId).append('\n')
        append("summary: ").append(summary).append('\n')
        append("excerpt_offset: 0\nnext_offset: ").append(excerpt.length).append('\n')
        append(localizedText("需要后续内容时调用 history_read，并传入 tool_call_id 与 offset。\n", "Call history_read with tool_call_id and offset to read more.\n"))
        append("excerpt:\n").append(excerpt)
    }
}

private fun String.takeUtf8Bytes(limit: Int): String {
    var end = 0
    var bytes = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        val width = Character.charCount(codePoint)
        val nextBytes = substring(end, end + width).toByteArray(Charsets.UTF_8).size
        if (bytes + nextBytes > limit) break
        bytes += nextBytes
        end += width
    }
    return substring(0, end)
}

/** 只在单个 Run 内存在；每次持久化都转成不可变快照，避免 UI 丢失轮次边界。 */
private class StreamingAssistantStep {
    val reasoning = StringBuilder()
    val text = StringBuilder()
    val toolCallIds = mutableListOf<String>()
    private var reasoningStartedAt: Long? = null
    private var reasoningFinishedAt: Long? = null

    fun appendReasoning(delta: String) {
        if (delta.isEmpty()) return
        if (reasoningStartedAt == null) reasoningStartedAt = System.currentTimeMillis()
        reasoningFinishedAt = null
        reasoning.append(delta)
    }

    fun finishReasoning() {
        if (reasoningStartedAt != null && reasoningFinishedAt == null) {
            reasoningFinishedAt = System.currentTimeMillis()
        }
    }

    fun snapshot(now: Long): AssistantStep = AssistantStep(
        reasoning = reasoning.toString(),
        toolCallIds = toolCallIds.toList(),
        text = text.toString(),
        reasoningDurationMillis = reasoningStartedAt?.let { (reasoningFinishedAt ?: now) - it },
    )
}
