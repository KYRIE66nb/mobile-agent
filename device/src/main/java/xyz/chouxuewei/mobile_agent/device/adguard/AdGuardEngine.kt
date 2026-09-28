package xyz.chouxuewei.mobile_agent.device.adguard

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.chouxuewei.mobile_agent.core.AdGuardAction
import xyz.chouxuewei.mobile_agent.core.AdGuardController
import xyz.chouxuewei.mobile_agent.core.AdGuardEvent
import xyz.chouxuewei.mobile_agent.core.AdGuardRule
import xyz.chouxuewei.mobile_agent.core.AdGuardSnapshot
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService
import xyz.chouxuewei.mobile_agent.device.accessibility.clickableSelfOrAncestor
import xyz.chouxuewei.mobile_agent.device.accessibility.collectVisibleNodes
import xyz.chouxuewei.mobile_agent.device.accessibility.nodeMatchesAny

/**
 * 广告守卫引擎：在无障碍事件流上做毫秒级确定性匹配，代替模型处理转瞬即逝的广告。
 * 全部状态保存在内存快照里，规则与开关由持久化层经 applyPersisted 灌入；
 * 引擎内部的修改经 persister 回写，applyPersisted 不回写以避免循环。
 */
object AdGuardEngine : AdGuardController {
    private val lock = Any()
    private val _state = MutableStateFlow(AdGuardSnapshot(false, true, emptyList(), emptyList(), 0))
    override val state: StateFlow<AdGuardSnapshot> = _state.asStateFlow()

    /** 由应用层注入，把规则与开关变化写回持久化存储；事件与计数不持久化。 */
    @Volatile var persister: (suspend (AdGuardSnapshot) -> Unit)? = null

    private var lastEvalAt = 0L
    private var lastForegroundPackage: String? = null
    private var foregroundSince = 0L
    private var launcherPackages = emptySet<String>()
    private val ruleLastFireAt = mutableMapOf<String, Long>()
    private val nodeSignatures = mutableMapOf<String, Long>()
    private val recentFireTimes = ArrayDeque<Long>()
    private var pendingBack: Runnable? = null
    private var mainHandler: Handler? = null

    override fun accessibilityConnected(): Boolean = AgentAccessibilityService.connected != null

    /** 持久化层单向灌入配置；不回写，内容相同直接跳过。 */
    fun applyPersisted(enabled: Boolean, showToast: Boolean, rules: List<AdGuardRule>) = synchronized(lock) {
        val current = _state.value
        if (current.enabled == enabled && current.showToast == showToast && current.rules == rules) return
        _state.value = current.copy(enabled = enabled, showToast = showToast, rules = rules)
    }

    override suspend fun setEnabled(enabled: Boolean) = mutate { it.copy(enabled = enabled) }
    override suspend fun setShowToast(show: Boolean) = mutate { it.copy(showToast = show) }

    override suspend fun addRule(rule: AdGuardRule) = mutate { current ->
        require(current.rules.none { it.id == rule.id }) { localizedText("规则 ID 已存在", "A rule with this ID already exists.") }
        require(current.rules.size < AdGuardRule.MAX_RULES) {
            localizedText("规则数量不能超过 ${AdGuardRule.MAX_RULES} 条", "At most ${AdGuardRule.MAX_RULES} rules are allowed.")
        }
        current.copy(rules = current.rules + rule)
    }

    override suspend fun removeRule(ruleId: String) = mutate { current ->
        require(current.rules.any { it.id == ruleId }) { localizedText("找不到这条规则", "No such rule.") }
        current.copy(rules = current.rules.filterNot { it.id == ruleId })
    }

    override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) = mutate { current ->
        require(current.rules.any { it.id == ruleId }) { localizedText("找不到这条规则", "No such rule.") }
        current.copy(rules = current.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
    }

    private suspend fun mutate(transform: (AdGuardSnapshot) -> AdGuardSnapshot) {
        val updated = synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            next
        }
        runCatching { persister?.invoke(updated) }
            .onFailure { AgentLog.e("AdGuard", it) { "广告守卫配置保存失败" } }
    }

    /** 无障碍事件入口；任何内部异常都不能拖垮整个服务。 */
    fun onAccessibilityEvent(service: AgentAccessibilityService, event: AccessibilityEvent) {
        val snapshot = _state.value
        if (!snapshot.enabled || snapshot.rules.none { it.enabled }) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    handleWindowChange(service, snapshot, event.packageName?.toString(), event.className?.toString())
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                    evaluateClickRules(service, snapshot)
            }
        } catch (error: Throwable) {
            AgentLog.e("AdGuard", error) { "广告守卫处理事件失败" }
        }
    }

    private fun handleWindowChange(service: AgentAccessibilityService, snapshot: AdGuardSnapshot, pkg: String?, className: String?) {
        val now = SystemClock.uptimeMillis()
        if (pkg != null && pkg != lastForegroundPackage) {
            val previous = lastForegroundPackage
            val dwell = now - foregroundSince
            lastForegroundPackage = pkg
            foregroundSince = now
            // 前台离开被守护的应用时才考虑按返回；短暂停留说明跳转更像误触发而不是用户主动导航。
            if (previous != null && pkg != service.packageName && dwell >= LEAVE_MIN_DWELL_MS && pkg !in ignoredPackages(service)) {
                val rules = snapshot.rules.filter {
                    it.enabled && it.action == AdGuardAction.AUTO_BACK && it.packageScope == previous
                }
                for (rule in rules) {
                    if (!cooldownReady(rule, now) || breakerOpen(now)) continue
                    val pattern = rule.classPattern?.let { runCatching { Regex(it) }.getOrNull() }
                    if (pattern == null || (className != null && pattern.containsMatchIn(className))) {
                        scheduleBack(service, rule, pkg)
                        break
                    }
                }
            }
            // 前台落到桌面/系统界面或回到某个被守护的应用时，撤销尚未执行的返回，避免打断用户主动导航。
            val returnedToGuarded = snapshot.rules.any { it.action == AdGuardAction.AUTO_BACK && it.packageScope == pkg }
            if (pkg in ignoredPackages(service) || returnedToGuarded) {
                pendingBack?.let { mainHandler?.removeCallbacks(it) }
                pendingBack = null
            }
        }
        evaluateClickRules(service, snapshot)
    }

    private fun scheduleBack(service: AgentAccessibilityService, rule: AdGuardRule, targetPackage: String) {
        val handler = mainHandler ?: Handler(Looper.getMainLooper()).also { mainHandler = it }
        pendingBack?.let(handler::removeCallbacks)
        val task = Runnable {
            pendingBack = null
            val now = SystemClock.uptimeMillis()
            if (!cooldownReady(rule, now) || breakerOpen(now)) return@Runnable
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            markFired(rule)
            recordEvent(rule, targetPackage, localizedText("摇一摇跳转已按返回拦截", "Shake-triggered jump cancelled with back"))
            if (_state.value.showToast) {
                Toast.makeText(service.applicationContext,
                    localizedText("已拦截：${rule.name}", "Blocked: ${rule.name}"), Toast.LENGTH_SHORT).show()
            }
        }
        pendingBack = task
        handler.postDelayed(task, AUTO_BACK_DELAY_MS)
    }

    private fun evaluateClickRules(service: AgentAccessibilityService, snapshot: AdGuardSnapshot) {
        val rules = snapshot.rules.filter { it.enabled && it.action == AdGuardAction.CLICK_TEXT }
        if (rules.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastEvalAt < CLICK_EVAL_INTERVAL_MS) return
        lastEvalAt = now
        val windows = service.windows ?: return
        for (window in windows) {
            val root = window.root
            window.recycle()
            if (root == null) continue
            try {
                val pkg = root.packageName?.toString()
                if (pkg == null || pkg == service.packageName || pkg in SYSTEM_PACKAGES) continue
                if (breakerOpen(now)) return
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                try {
                    collectVisibleNodes(root, nodes, MAX_NODES_PER_WINDOW)
                    if (nodes.isEmpty()) continue
                    val texts = nodes.mapNotNull { it.text?.toString() } +
                        nodes.mapNotNull { it.contentDescription?.toString() }
                    for (rule in rules) {
                        if (rule.packageScope != null && rule.packageScope != pkg) continue
                        if (!cooldownReady(rule, now)) continue
                        if (rule.contextTexts.isNotEmpty() &&
                            !rule.contextTexts.all { ctx -> texts.any { it.contains(ctx, ignoreCase = true) } }) continue
                        val hit = nodes.firstOrNull { node -> nodeMatchesAny(node, rule.matchTexts) }
                            ?.let { clickableSelfOrAncestor(it, nodes) } ?: continue
                        val signature = nodeSignature(hit)
                        if (signature != null && nodeSignatures[signature]?.let { now - it < NODE_DEDUP_MS } == true) continue
                        if (signature != null) nodeSignatures[signature] = now
                        if (hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            markFired(rule)
                            recordEvent(rule, pkg, localizedText("点击「${nodeLabel(hit)}」", "Tapped \"${nodeLabel(hit)}\""))
                            if (_state.value.showToast) {
                                Toast.makeText(service.applicationContext,
                                    localizedText("已拦截：${rule.name}", "Blocked: ${rule.name}"), Toast.LENGTH_SHORT).show()
                            }
                            return
                        }
                    }
                } finally {
                    nodes.forEach { it.recycle() }
                }
            } finally {
                root.recycle()
            }
        }
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): String? {
        val bounds = Rect().also(node::getBoundsInScreen)
        val label = nodeLabel(node)
        if (bounds.isEmpty && label.isEmpty()) return null
        return "${node.packageName}|${node.viewIdResourceName}|$bounds|$label"
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().take(30)

    private fun cooldownReady(rule: AdGuardRule, now: Long): Boolean =
        now - (ruleLastFireAt[rule.id] ?: 0L) >= rule.cooldownMs

    private fun markFired(rule: AdGuardRule) {
        val now = SystemClock.uptimeMillis()
        ruleLastFireAt[rule.id] = now
        recentFireTimes.addLast(now)
        while (recentFireTimes.size > GLOBAL_BREAKER_LIMIT) recentFireTimes.removeFirst()
        nodeSignatures.entries.removeAll { now - it.value > NODE_DEDUP_MS }
    }

    /** 一分钟内的总触发数超过上限时静默停手，避免误配规则造成连续误点。 */
    private fun breakerOpen(now: Long): Boolean =
        recentFireTimes.count { now - it < 60_000 } >= GLOBAL_BREAKER_LIMIT

    private fun recordEvent(rule: AdGuardRule, packageName: String, detail: String) = synchronized(lock) {
        val current = _state.value
        val event = AdGuardEvent(System.currentTimeMillis(), rule.name, packageName, detail)
        _state.value = current.copy(
            events = (listOf(event) + current.events).take(MAX_EVENTS),
            totalBlocked = current.totalBlocked + 1,
        )
    }

    /** 桌面、系统界面与本应用不算广告跳转目标，避免把用户主动回桌面误判成广告跳转。 */
    private fun ignoredPackages(service: AgentAccessibilityService): Set<String> {
        if (launcherPackages.isEmpty()) {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            launcherPackages = runCatching {
                service.packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }.toSet()
            }.getOrDefault(emptySet())
        }
        return launcherPackages + SYSTEM_PACKAGES + service.packageName
    }

    private const val CLICK_EVAL_INTERVAL_MS = 300L
    private const val AUTO_BACK_DELAY_MS = 350L
    private const val LEAVE_MIN_DWELL_MS = 600L
    private const val NODE_DEDUP_MS = 4_000L
    private const val GLOBAL_BREAKER_LIMIT = 12
    private const val MAX_NODES_PER_WINDOW = 400
    private const val MAX_EVENTS = 30
    private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
}
