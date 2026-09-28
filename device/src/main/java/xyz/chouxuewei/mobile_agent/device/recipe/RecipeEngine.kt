package xyz.chouxuewei.mobile_agent.device.recipe

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.Recipe
import xyz.chouxuewei.mobile_agent.core.RecipeAction
import xyz.chouxuewei.mobile_agent.core.RecipeController
import xyz.chouxuewei.mobile_agent.core.RecipeDefaults
import xyz.chouxuewei.mobile_agent.core.RecipeStep
import xyz.chouxuewei.mobile_agent.core.RecipeStepException
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService
import xyz.chouxuewei.mobile_agent.device.accessibility.clickableSelfOrAncestor
import xyz.chouxuewei.mobile_agent.device.accessibility.collectVisibleNodes
import xyz.chouxuewei.mobile_agent.device.accessibility.nodeMatchesAny
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 操作模板引擎：把常用 App 流程固化为确定性步骤在主屏前台执行，
 * 模型只填参数；某步失败即返回步骤号，供模型从该步起用通用界面操作兜底。
 * 内置模板与自定义模板合流，自定义规则经 persister 回写持久化。
 */
object RecipeEngine : RecipeController {
    private val lock = Any()
    private val _state = MutableStateFlow<List<Recipe>>(RecipeDefaults.recipes())
    override val state: StateFlow<List<Recipe>> = _state.asStateFlow()

    /** 由应用层注入，把自定义模板写回持久化存储。 */
    @Volatile var persister: (suspend (List<Recipe>) -> Unit)? = null

    override fun accessibilityConnected(): Boolean = AgentAccessibilityService.connected != null

    /** 持久化层单向灌入自定义模板；与内置模板合流，不回写。 */
    fun applyPersisted(custom: List<Recipe>) = synchronized(lock) {
        val current = _state.value.filter(Recipe::builtin)
        _state.value = current + custom.filter { item -> item.id.isNotBlank() }
    }

    override suspend fun add(recipe: Recipe) = mutate { current ->
        require(recipe.id.isNotBlank() && recipe.id !in current.map { it.id }) {
            localizedText("模板 ID 已存在或无效", "The recipe ID already exists or is invalid.")
        }
        require(current.count { !it.builtin } < Recipe.MAX_CUSTOM) {
            localizedText("自定义模板不能超过 ${Recipe.MAX_CUSTOM} 条", "At most ${Recipe.MAX_CUSTOM} custom recipes are allowed.")
        }
        current + recipe.copy(builtin = false)
    }

    override suspend fun remove(recipeId: String) = mutate { current ->
        val target = current.firstOrNull { it.id == recipeId }
        require(target != null) { localizedText("找不到这个模板", "No such recipe.") }
        require(!target.builtin) { localizedText("内置模板只能停用不能删除", "Built-in recipes cannot be removed.") }
        current.filterNot { it.id == recipeId }
    }

    override suspend fun runRecipe(recipeId: String, params: Map<String, String>): List<String> {
        val recipe = _state.value.firstOrNull { it.id == recipeId }
            ?: throw IllegalArgumentException(localizedText("找不到这个模板", "No such recipe."))
        return run(recipe, params)
    }

    private suspend fun mutate(transform: (List<Recipe>) -> List<Recipe>) {
        val updated = synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            next
        }
        runCatching { persister?.invoke(updated.filterNot(Recipe::builtin)) }
            .onFailure { AgentLog.e("Recipe", it) { "操作模板保存失败" } }
    }

    /**
     * 顺序执行模板步骤；返回每步执行记录。
     * 失败抛出 RecipeStepException，携带步骤序号，供调用方定位后兜底。
     */
    suspend fun run(recipe: Recipe, params: Map<String, String>): List<String> {
        val service = AgentAccessibilityService.connected
            ?: throw RecipeStepException(-1, localizedText("无障碍服务未连接，无法执行操作模板", "The accessibility service is not connected; recipes cannot run."))
        val log = mutableListOf<String>()
        recipe.steps.forEachIndexed { index, step ->
            currentCoroutineContext().ensureActive()
            val resolved = resolve(step, params)
            try {
                executeStep(service, resolved)
            } catch (error: RecipeStepException) {
                if (step.optional) {
                    log += "步骤 ${index + 1} 跳过（可选）：${error.message}"
                } else throw RecipeStepException(index, error.message ?: localizedText("步骤失败", "Step failed"))
            } catch (error: Throwable) {
                if (step.optional) {
                    log += "步骤 ${index + 1} 跳过（可选）：${error.message}"
                } else throw RecipeStepException(index, error.message ?: localizedText("步骤失败", "Step failed"))
            }
            if (log.size <= index) log += "步骤 ${index + 1} 完成：${describe(resolved)}"
        }
        return log
    }

    private fun resolve(step: RecipeStep, params: Map<String, String>): RecipeStep {
        fun substitute(value: String) = params.entries.fold(value) { acc, (k, v) -> acc.replace("{$k}", v) }
        return step.copy(
            texts = step.texts.map(::substitute),
            text = step.text?.let(::substitute),
            packageName = step.packageName?.let(::substitute),
        )
    }

    private suspend fun executeStep(service: AgentAccessibilityService, step: RecipeStep) {
        when (step.action) {
            RecipeAction.LAUNCH -> {
                val intent = service.packageManager.getLaunchIntentForPackage(step.packageName!!)
                    ?: throw RecipeStepException(-1, localizedText("目标应用未安装", "The target app is not installed."))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
            }
            RecipeAction.WAIT_TEXT -> waitFor(service, step) { nodes ->
                nodes.any { nodeMatchesAny(it, step.texts) }
            }
            RecipeAction.TAP_TEXT -> waitFor(service, step) { nodes ->
                nodes.firstOrNull { nodeMatchesAny(it, step.texts) }
                    ?.let { clickableSelfOrAncestor(it, nodes) }
                    ?.let { hit ->
                        if (!hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            tapAt(service, hit)
                        }
                        true
                    } ?: false
            }
            RecipeAction.INPUT_TEXT -> {
                val target = editableNode(service)
                    ?: throw RecipeStepException(-1, localizedText("当前界面没有可编辑输入框", "No editable field on screen."))
                try {
                    check(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text!!.take(2000))
                    })) { localizedText("目标应用不支持节点文本输入", "The target app does not support node-based text input.") }
                } finally {
                    target.recycle()
                }
            }
            RecipeAction.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            RecipeAction.SLEEP -> delay(step.timeoutMs)
        }
    }

    /** 轮询界面直到条件满足或超时；每轮重新读取窗口节点。 */
    private suspend fun waitFor(
        service: AgentAccessibilityService,
        step: RecipeStep,
        predicate: suspend (MutableList<AccessibilityNodeInfo>) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + step.timeoutMs
        do {
            val windows = service.windows
            var matched = false
            for (window in windows) {
                val root = window.root
                window.recycle()
                if (root == null) continue
                try {
                    val nodes = mutableListOf<AccessibilityNodeInfo>()
                    try {
                        collectVisibleNodes(root, nodes, 400)
                        if (predicate(nodes)) { matched = true; break }
                    } finally {
                        nodes.forEach { it.recycle() }
                    }
                } finally {
                    root.recycle()
                }
            }
            if (matched) return
            currentCoroutineContext().ensureActive()
            delay(300)
        } while (SystemClock.uptimeMillis() < deadline)
        throw RecipeStepException(-1, localizedText("等待超时：${step.texts.firstOrNull() ?: step.action.name}", "Timed out waiting for: ${step.texts.firstOrNull() ?: step.action.name}"))
    }

    private fun editableNode(service: AgentAccessibilityService): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        for (window in windows) {
            val root = window.root
            window.recycle()
            if (root == null) continue
            try {
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                collectVisibleNodes(root, nodes, 400)
                val editable = nodes.firstOrNull { it.isEditable && it.isEnabled }
                if (editable != null) {
                    // 返回的节点交给调用方回收，其余统一释放。
                    nodes.filter { it !== editable }.forEach { it.recycle() }
                    return editable
                }
                nodes.forEach { it.recycle() }
            } finally {
                root.recycle()
            }
        }
        return null
    }

    /** 节点不可点击时按边界中心注入点击手势兜底。 */
    private suspend fun tapAt(service: AgentAccessibilityService, node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(android.view.Display.DEFAULT_DISPLAY)
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 60))
        suspendCancellableCoroutine { continuation ->
            val accepted = service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        RecipeStepException(-1, localizedText("系统取消了点击手势", "The system cancelled the tap gesture.")))
                }
            }, null)
            if (!accepted && continuation.isActive) {
                continuation.resumeWithException(RecipeStepException(-1, localizedText("系统未接受点击手势", "The system rejected the tap gesture.")))
            }
        }
    }

    private fun describe(step: RecipeStep): String = when (step.action) {
        RecipeAction.LAUNCH -> "启动 ${step.packageName}"
        RecipeAction.WAIT_TEXT -> "等待「${step.texts.firstOrNull()}」"
        RecipeAction.TAP_TEXT -> "点击「${step.texts.firstOrNull()}」"
        RecipeAction.INPUT_TEXT -> "输入文本"
        RecipeAction.BACK -> "按返回"
        RecipeAction.SLEEP -> "等待 ${step.timeoutMs}ms"
    }
}
