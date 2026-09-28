package xyz.chouxuewei.mobile_agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar

class TriggerEngineTest {

    private var nowMs = BASE
    private val engine = TriggerEngine { nowMs }

    private fun scheduleSpec(
        repeat: TriggerRepeat = TriggerRepeat.NONE,
        at: Long? = BASE + 60_000,
        hour: Int? = 8, minute: Int? = 0,
        weekdays: Set<Int> = emptySet(),
    ) = TriggerSpec(
        id = "t1", name = "日报", kind = TriggerKind.SCHEDULE,
        scheduleAtEpochMs = at, repeat = repeat, hour = hour, minute = minute,
        weekdays = weekdays, instruction = "读出今天日程",
    )

    private fun notificationSpec(
        pkg: String? = "com.tencent.mm", title: String? = null, text: String? = null,
    ) = TriggerSpec(
        id = "t2", name = "微信监听", kind = TriggerKind.NOTIFICATION,
        notification = NotificationMatch(pkg, title, text), instruction = "汇总新消息",
    )

    private fun intervalSpec(minutes: Int = 15, lastRunAt: Long? = null) = TriggerSpec(
        id = "t3", name = "巡检", kind = TriggerKind.INTERVAL,
        intervalMinutes = minutes, lastRunAt = lastRunAt, instruction = "检查存储",
    )

    private fun event(
        pkg: String = "com.tencent.mm", title: String = "老板", text: String = "在吗",
        ongoing: Boolean = false,
    ) = TriggerNotificationEvent(
        packageName = pkg, appName = "微信", title = title, text = text,
        postTime = nowMs, ongoing = ongoing,
    )

    // ---- 校验 ----

    @Test fun `interval below minimum is rejected`() {
        try {
            intervalSpec(minutes = 10); fail("应拒绝小于 15 分钟的间隔")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `notification trigger requires at least one condition`() {
        try {
            NotificationMatch(); fail("应拒绝全空匹配条件")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `notification match rejects invalid regex`() {
        try {
            NotificationMatch(titlePattern = "[unclosed"); fail("应拒绝非法正则")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `weekly repeat requires weekdays`() {
        try {
            scheduleSpec(repeat = TriggerRepeat.WEEKLY); fail("应拒绝空的 weekdays")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `one-shot schedule requires fire time`() {
        try {
            scheduleSpec(at = null); fail("应拒绝缺失触发时间")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun `blank instruction is rejected`() {
        try {
            scheduleSpec().copy(instruction = " "); fail("copy 也应执行校验")
        } catch (_: IllegalArgumentException) {}
    }

    // ---- 熔断与冷却 ----

    @Test fun `disabled trigger cannot fire`() {
        assertNotNull(engine.canFire(scheduleSpec().copy(enabled = false)))
    }

    @Test fun `cooldown blocks refire`() {
        val spec = scheduleSpec().copy(cooldownMinutes = 5, lastRunAt = nowMs - 60_000)
        assertNotNull(engine.canFire(spec))
        assertNull(engine.canFire(spec.copy(lastRunAt = nowMs - 6 * 60_000)))
    }

    @Test fun `daily limit blocks`() {
        val spec = scheduleSpec().copy(maxRunsPerDay = 2, cooldownMinutes = 0)
        assertNull(engine.canFire(spec))
        engine.onFired(spec); engine.onFired(spec)
        assertNotNull(engine.canFire(spec))
    }

    @Test fun `consecutive failures trip the breaker`() {
        val spec = scheduleSpec().copy(consecutiveFailures = TriggerSpec.MAX_FAILURES_BEFORE_DISABLE)
        assertNotNull(engine.canFire(spec))
    }

    @Test fun `persisted daily count survives restart`() {
        // 冷启动时内存计数为空：spec 里持久化的当日计数必须继续参与日熔断。
        val freshEngine = TriggerEngine(now = { nowMs })
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(nowMs)).toInt()
        val spec = scheduleSpec().copy(maxRunsPerDay = 2, cooldownMinutes = 0, dailyFireDate = today, dailyFireCount = 2)
        assertNotNull(freshEngine.canFire(spec))
        assertEquals(2, freshEngine.firedToday(spec))
        // 持久化日期不是今天（跨天）时归零放行。
        val yesterday = spec.copy(dailyFireDate = today - 1)
        assertNull(freshEngine.canFire(yesterday))
    }

    @Test fun `memory count wins over stale persisted count`() {
        val spec = scheduleSpec().copy(maxRunsPerDay = 3, cooldownMinutes = 0)
        engine.onFired(spec); engine.onFired(spec)
        // 内存计数领先于尚未合并写回的 spec 计数。
        assertEquals(2, engine.firedToday(spec.copy(dailyFireCount = 1, dailyFireDate = 0)))
    }

    // ---- 通知匹配 ----

    @Test fun `notification match uses AND semantics`() {
        val spec = notificationSpec(pkg = "tencent", title = "老板", text = null)
        assertTrue(engine.matchesNotification(spec, event(title = "老板在吗"), "me.app"))
        assertFalse(engine.matchesNotification(spec, event(title = "同事"), "me.app"))
        assertFalse(engine.matchesNotification(spec, event(pkg = "com.other", title = "老板"), "me.app"))
    }

    @Test fun `own package never triggers`() {
        val spec = notificationSpec(pkg = ".*")
        assertFalse(engine.matchesNotification(spec, event(pkg = "me.app"), "me.app"))
    }

    @Test fun `ongoing notifications are skipped`() {
        val spec = notificationSpec(pkg = ".*")
        assertFalse(engine.matchesNotification(spec, event(ongoing = true), "me.app"))
    }

    @Test fun `notification fire respects cooldown`() {
        val spec = notificationSpec().copy(cooldownMinutes = 10, lastRunAt = nowMs - 60_000)
        assertFalse(engine.matchesNotification(spec, event(), "me.app"))
    }

    // ---- nextFireAt ----

    @Test fun `one-shot schedule fires only when future`() {
        assertEquals(BASE + 60_000, engine.nextFireAt(scheduleSpec(), BASE))
        assertNull(engine.nextFireAt(scheduleSpec(at = BASE - 1), BASE))
    }

    @Test fun `daily repeat returns next local occurrence`() {
        // BASE 是某周一 12:00 UTC（本地时区下以 Calendar 计算为准）
        val spec = scheduleSpec(repeat = TriggerRepeat.DAILY, at = null, hour = 8, minute = 30)
        val next = engine.nextFireAt(spec, BASE)
        assertNotNull(next)
        val cal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertTrue(next!! > BASE)
    }

    @Test fun `weekly repeat lands on a requested weekday`() {
        val spec = scheduleSpec(
            repeat = TriggerRepeat.WEEKLY, at = null, hour = 9, minute = 0,
            weekdays = setOf(Calendar.WEDNESDAY, Calendar.FRIDAY),
        )
        val next = engine.nextFireAt(spec, BASE)
        assertNotNull(next)
        val cal = Calendar.getInstance().apply { timeInMillis = next!! }
        assertTrue(cal.get(Calendar.DAY_OF_WEEK) in setOf(Calendar.WEDNESDAY, Calendar.FRIDAY))
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertTrue(next!! > BASE)
    }

    @Test fun `interval fires after interval from last run`() {
        val spec = intervalSpec(minutes = 15, lastRunAt = BASE - 5 * 60_000)
        assertEquals(BASE + 10 * 60_000, engine.nextFireAt(spec, BASE))
        val fresh = intervalSpec(minutes = 15)
        assertEquals(BASE, engine.nextFireAt(fresh, BASE))
    }

    @Test fun `notification kind never schedules`() {
        assertNull(engine.nextFireAt(notificationSpec(), BASE))
    }

    // ---- ScopeGate ----

    @Test fun `self-management and human-interaction tools are forbidden in scope`() {
        for (id in listOf("trigger_save", "trigger_delete", "trigger_run_now", "trigger_list", "ask_user")) {
            assertTrue("$id 必须被禁止进入触发器 scope", id in TriggerSpec.FORBIDDEN_SCOPE_TOOLS)
        }
    }

    @Test fun `scope gate allows listed tools and blocks others`() = kotlinx.coroutines.runBlocking {
        val gate = ScopeGate(setOf("file_read"), "测试")
        val allow = gate.gate(GateRequest("u", "file_read", "读文件", "args"))
        assertTrue(allow is GateVerdict.Allow)
        val block = gate.gate(GateRequest("u", "system_shell", "Shell", "args"))
        assertTrue(block is GateVerdict.Block)
        assertTrue((block as GateVerdict.Block).reason.contains("system_shell"))
    }

    companion object {
        /** 固定基准时间，避免测试依赖真实时钟。 */
        private val BASE: Long = Calendar.getInstance().apply {
            set(2025, Calendar.JANUARY, 6, 12, 0, 0) // 周一中午
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
