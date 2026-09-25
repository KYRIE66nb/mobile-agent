package xyz.chouxuewei.mobile_agent.tools.notifications

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val appName: String,
    val postTime: Long,
    val category: String?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
    val sensitive: Boolean,
    val actions: List<String>,
)

class AgentNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile
        var connected: AgentNotificationListenerService? = null
            private set

        private val SENSITIVE_PATTERN = Regex(
            "验证码|校验码|动态码|安全码|一次性密码|otp|verification\\s*code|security\\s*code|one[- ]time\\s*(password|code)",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    fun snapshots(): List<NotificationSnapshot> = activeNotifications.orEmpty()
        .sortedByDescending { it.postTime }
        .map(::snapshot)

    fun open(key: String) {
        val item = requireNotification(key)
        check(!item.isSensitive()) { localizedText("验证码或账户安全通知不允许自动打开", "Verification-code or account-security notifications cannot be opened automatically.") }
        val notification = item.notification
        checkNotNull(notification.contentIntent) { localizedText("该通知没有可打开的内容", "This notification has no content to open.") }.send()
    }

    fun invokeAction(key: String, index: Int) {
        val item = requireNotification(key)
        check(!item.isSensitive()) { localizedText("验证码或账户安全通知不允许自动执行操作", "Actions on verification-code or account-security notifications cannot be automated.") }
        val actions = item.notification.actions.orEmpty()
        require(index in actions.indices) { localizedText("通知操作序号无效", "Invalid notification action index.") }
        checkNotNull(actions[index].actionIntent) { localizedText("该通知操作当前不可用", "This notification action is currently unavailable.") }.send()
    }

    fun dismiss(key: String) {
        val item = requireNotification(key)
        check(item.isClearable) { localizedText("该通知不能被清除", "This notification cannot be dismissed.") }
        check(!item.isSensitive()) { localizedText("验证码或账户安全通知不允许自动清除", "Verification-code or account-security notifications cannot be dismissed automatically.") }
        cancelNotification(key)
    }

    private fun requireNotification(key: String): StatusBarNotification =
        activeNotifications.orEmpty().singleOrNull { it.key == key }
            ?: error(localizedText("通知已消失，请重新读取通知列表", "The notification is gone. Read the notification list again."))

    private fun snapshot(item: StatusBarNotification): NotificationSnapshot {
        val notification = item.notification
        val extras = notification.extras
        val sensitive = item.isSensitive()
        val hidden = localizedText("[敏感通知内容已隐藏]", "[Sensitive notification content hidden]")
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(item.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(item.packageName)
        return NotificationSnapshot(
            key = item.key,
            packageName = item.packageName,
            appName = appName,
            postTime = item.postTime,
            category = notification.category,
            title = if (sensitive) hidden else extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(500),
            text = if (sensitive) hidden else extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(1_000),
            subText = if (sensitive) null else extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.take(500),
            ongoing = item.isOngoing,
            clearable = item.isClearable,
            sensitive = sensitive,
            actions = if (sensitive) emptyList() else notification.actions.orEmpty().mapIndexed { index, action ->
                action.title?.toString()?.take(100).orEmpty().ifBlank { localizedText("操作 ${index + 1}", "Action ${index + 1}") }
            },
        )
    }

    private fun StatusBarNotification.isSensitive(): Boolean {
        if (notification.visibility == Notification.VISIBILITY_SECRET) return true
        val extras = notification.extras
        val content = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).joinToString(" ") { it?.toString().orEmpty() }
        return SENSITIVE_PATTERN.containsMatchIn(content)
    }
}
