package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

enum class TriggerKind { SCHEDULE, NOTIFICATION, INTERVAL }

enum class TriggerRepeat { NONE, DAILY, WEEKDAYS, WEEKLY }

/** 通知过滤条件：非空的字段全部命中（AND）才触发；字段为正则，留空视为通配。 */
data class NotificationMatch(
    val packagePattern: String? = null,
    val titlePattern: String? = null,
    val textPattern: String? = null,
) {
    init {
        require(listOf(packagePattern, titlePattern, textPattern).any { !it.isNullOrBlank() }) {
            localizedText("通知触发器至少需要一个匹配条件", "A notification trigger needs at least one match condition.")
        }
        for (pattern in listOfNotNull(packagePattern, titlePattern, textPattern)) {
            require(pattern.length <= 200) { localizedText("匹配表达式过长", "Match pattern is too long.") }
            require(runCatching { Regex(pattern) }.isSuccess) {
                localizedText("匹配表达式不是合法正则：$pattern", "Invalid regular expression: $pattern")
            }
        }
    }
}

/** 通知监听器交给引擎的事件快照；title/text 已做长度裁剪，属于低信任数据。 */
data class TriggerNotificationEvent(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val ongoing: Boolean,
)

/**
 * 触发器定义。toolScope 是无人值守执行的唯一防线：scope 之外的工具模型既看不到也调不动；
 * EXTERNAL_WRITE/DESTRUCTIVE 工具必须用户在创建时显式加入 scope。
 * weekdays 使用 Calendar 常量（SUNDAY=1 … SATURDAY=7）。
 */
data class TriggerSpec(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val kind: TriggerKind,
    /** SCHEDULE+NONE：首次触发时间戳；重复规则下由 hour/minute 决定触发时刻。 */
    val scheduleAtEpochMs: Long? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val repeat: TriggerRepeat = TriggerRepeat.NONE,
    val weekdays: Set<Int> = emptySet(),
    val notification: NotificationMatch? = null,
    val intervalMinutes: Int = MIN_INTERVAL_MINUTES,
    val instruction: String,
    val toolScope: Set<String> = DEFAULT_SCOPE,
    val cooldownMinutes: Int = 5,
    val maxRunsPerDay: Int = 12,
    val notifyUser: Boolean = true,
    /** 首次保存时创建的执行会话；触发产物与主聊天隔离。 */
    val conversationId: String? = null,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val consecutiveFailures: Int = 0,
    /** yyyyMMdd 持久化的当日触发计数：进程重启后日熔断不被绕过。 */
    val dailyFireDate: Int = 0,
    val dailyFireCount: Int = 0,
) {
    init {
        require(id.isNotBlank() && id.length <= 64) { localizedText("触发器 ID 无效", "Invalid trigger ID.") }
        require(name.isNotBlank() && name.length <= 40) { localizedText("触发器名称不能为空且不超过 40 字", "A trigger name is required and must be at most 40 characters.") }
        require(instruction.isNotBlank() && instruction.length <= 2000) { localizedText("触发指令不能为空且不超过 2000 字", "Trigger instruction is required and must be at most 2000 characters.") }
        require(toolScope.isNotEmpty() && toolScope.size <= 40 && toolScope.all { it.isNotBlank() && it.length <= 64 }) {
            localizedText("工具范围无效", "Invalid tool scope.")
        }
        require(cooldownMinutes in 0..1440) { localizedText("冷却时间需在 0 到 1440 分钟之间", "Cooldown must be between 0 and 1440 minutes.") }
        require(maxRunsPerDay in 1..200) { localizedText("每日次数需在 1 到 200 之间", "Daily run limit must be between 1 and 200.") }
        when (kind) {
            TriggerKind.SCHEDULE -> when (repeat) {
                TriggerRepeat.NONE -> require(scheduleAtEpochMs != null) {
                    localizedText("定时触发需要触发时间", "A scheduled trigger needs a fire time.")
                }
                TriggerRepeat.DAILY, TriggerRepeat.WEEKDAYS -> require(hour in 0..23 && minute in 0..59) {
                    localizedText("重复触发需要有效的时与分", "A repeating trigger needs a valid hour and minute.")
                }
                TriggerRepeat.WEEKLY -> {
                    require(hour in 0..23 && minute in 0..59) {
                        localizedText("重复触发需要有效的时与分", "A repeating trigger needs a valid hour and minute.")
                    }
                    require(weekdays.isNotEmpty() && weekdays.size <= 7 && weekdays.all { it in 1..7 }) {
                        localizedText("每周触发需要 1 到 7 个有效星期值", "A weekly trigger needs 1 to 7 valid weekdays.")
                    }
                }
            }
            TriggerKind.NOTIFICATION -> require(notification != null) {
                localizedText("通知触发器需要匹配条件", "A notification trigger needs match conditions.")
            }
            TriggerKind.INTERVAL -> require(intervalMinutes >= MIN_INTERVAL_MINUTES) {
                localizedText("周期间隔至少 $MIN_INTERVAL_MINUTES 分钟", "Interval must be at least $MIN_INTERVAL_MINUTES minutes.")
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_FAILURES_BEFORE_DISABLE = 3

        /**
         * 任何路径都不允许进入触发器 scope 的工具：触发器自我管理会造成权限自我扩张，
         * ask_user 会让无人值守执行永久挂起，授权弹窗类工具在后台无人响应。
         */
        val FORBIDDEN_SCOPE_TOOLS: Set<String> = setOf(
            "trigger_list", "trigger_save", "trigger_delete", "trigger_run_now",
            "ask_user", "personal_grant_permission",
        )

        /** 未显式指定时的默认范围：只读 + 本地产物，不接触设备与外部副作用。 */
        val DEFAULT_SCOPE: Set<String> = linkedSetOf(
            "history_search", "history_read",
            "file_list", "file_read", "file_write", "file_share",
            "document_write", "document_inspect", "document_edit",
            "notifications_list", "clipboard_read", "clipboard_write",
            "contacts_search", "calendar_events",
            "system_get_state", "system_storage_stats", "system_display",
            "network_fetch", "network_search",
        )
    }
}

/** 一次无人值守运行的策略：工具过滤 + scope 内免审批 + 独立安全闸。 */
data class RunPolicy(
    val allowedToolIds: Set<String>,
    val autoApproveWithinScope: Boolean = true,
    val gate: DecisionGate? = null,
)

/** 触发执行的第二道闸：即便工具有副作用标记，scope 内放行、scope 外直接拒绝，永不弹确认。 */
class ScopeGate(
    private val allowedToolIds: Set<String>,
    private val triggerName: String,
) : DecisionGate {
    override suspend fun gate(request: GateRequest): GateVerdict =
        if (request.toolId in allowedToolIds) GateVerdict.Allow
        else GateVerdict.Block(
            localizedText(
                "触发器「$triggerName」的授权范围不包含 ${request.toolId}",
                "Trigger \"$triggerName\" has not authorized tool ${request.toolId}.",
            )
        )
}

interface TriggerController {
    val specs: StateFlow<List<TriggerSpec>>
    suspend fun upsert(spec: TriggerSpec): TriggerSpec
    suspend fun remove(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    /** 立即以与定时触发相同的安全约束执行一次，返回模型产出的结尾摘要。 */
    suspend fun runNow(id: String): String
    suspend fun spec(id: String): TriggerSpec?
}

/**
 * 纯逻辑引擎：匹配、冷却、日熔断、失败熔断、下次触发时间计算；不碰 Android API，可单测。
 * 冷却/日计数保存在内存，进程重启后以 spec.lastRunAt 兜底粗粒度去重。
 */
class TriggerEngine(private val now: () -> Long = { System.currentTimeMillis() }) {

    data class FireRequest(val spec: TriggerSpec, val cause: String)

    /** 引擎会被闹钟线程、通知监听线程与工具执行并发调用，计数结构必须线程安全。 */
    private val lastFiredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** id → (yyyyMMdd of last reset, fired count) */
    private val dailyCount = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun canFire(spec: TriggerSpec): String? {
        if (!spec.enabled) return localizedText("触发器已停用", "Trigger is disabled")
        if (spec.consecutiveFailures >= TriggerSpec.MAX_FAILURES_BEFORE_DISABLE) {
            return localizedText("连续失败已达上限，触发器已熔断", "Disabled after consecutive failures")
        }
        val reference = maxOf(lastFiredAt[spec.id] ?: 0L, spec.lastRunAt ?: 0L)
        if (spec.cooldownMinutes > 0 && reference > 0 &&
            now() - reference < spec.cooldownMinutes * 60_000L) {
            return localizedText("冷却中", "Cooling down")
        }
        if (firedToday(spec) >= spec.maxRunsPerDay) {
            return localizedText("今日触发次数已达上限", "Daily run limit reached")
        }
        return null
    }

    fun matchesNotification(spec: TriggerSpec, event: TriggerNotificationEvent, ownPackage: String): Boolean {
        if (spec.kind != TriggerKind.NOTIFICATION) return false
        if (canFire(spec) != null) return false
        if (event.packageName == ownPackage) return false
        if (event.ongoing) return false
        val match = spec.notification ?: return false
        return match.packagePattern.orEmpty().let { it.isEmpty() || Regex(it).containsMatchIn(event.packageName) } &&
            match.titlePattern.orEmpty().let { it.isEmpty() || Regex(it).containsMatchIn(event.title) } &&
            match.textPattern.orEmpty().let { it.isEmpty() || Regex(it).containsMatchIn(event.text) }
    }

    @Synchronized
    fun onFired(spec: TriggerSpec) {
        val at = now()
        lastFiredAt[spec.id] = at
        val day = dayKey(at)
        val (key, count) = dailyCount[spec.id] ?: (day to 0)
        dailyCount[spec.id] = if (key == day) day to count + 1 else day to 1
    }

    /** 内存计数与 spec 持久化计数取大者：进程重启后 spec 是冷启动期的唯一来源。 */
    fun firedToday(spec: TriggerSpec): Int {
        val today = dayKey(now())
        val memory = dailyCount[spec.id]?.takeIf { it.first == today }?.second ?: 0
        val persisted = if (spec.dailyFireDate == today) spec.dailyFireCount else 0
        return maxOf(memory, persisted)
    }

    /** (yyyyMMdd, count) 内存态快照；执行层把它合并写回 spec。 */
    fun dailySnapshot(id: String): Pair<Int, Int>? = dailyCount[id]

    /** 计算下一次触发时间；null 表示不再需要排期（一次性已过、通知型、停用）。 */
    fun nextFireAt(spec: TriggerSpec, fromMs: Long = now()): Long? {
        if (!spec.enabled) return null
        return when (spec.kind) {
            TriggerKind.NOTIFICATION -> null
            TriggerKind.INTERVAL -> {
                val base = maxOf(spec.lastRunAt ?: 0L, lastFiredAt[spec.id] ?: 0L)
                val next = if (base <= 0L) fromMs else base + spec.intervalMinutes * 60_000L
                next.coerceAtLeast(fromMs)
            }
            TriggerKind.SCHEDULE -> when (spec.repeat) {
                TriggerRepeat.NONE -> spec.scheduleAtEpochMs?.takeIf { it > fromMs }
                else -> nextLocalOccurrence(spec, fromMs)
            }
        }
    }

    private fun nextLocalOccurrence(spec: TriggerSpec, fromMs: Long): Long? {
        val hour = spec.hour ?: return null
        val minute = spec.minute ?: 0
        val base = Calendar.getInstance().apply { timeInMillis = fromMs }
        for (offset in 0..370) {
            val day = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (day.timeInMillis <= fromMs) continue
            val matches = when (spec.repeat) {
                TriggerRepeat.DAILY -> true
                TriggerRepeat.WEEKDAYS -> day.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY
                TriggerRepeat.WEEKLY -> day.get(Calendar.DAY_OF_WEEK) in spec.weekdays
                TriggerRepeat.NONE -> false
            }
            if (matches) return day.timeInMillis
        }
        return null
    }

    private fun dayKey(ms: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.YEAR) * 10_000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }
}
