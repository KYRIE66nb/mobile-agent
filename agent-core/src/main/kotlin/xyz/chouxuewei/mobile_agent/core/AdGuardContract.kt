package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.StateFlow

/** 广告守卫动作：点击匹配节点，或对离开指定应用的前台跳转按返回。 */
enum class AdGuardAction { CLICK_TEXT, AUTO_BACK }

/**
 * 广告守卫规则；由用户或 Agent 配置，运行时在无障碍事件流上确定性匹配。
 * CLICK_TEXT 点击界面中文字或描述命中 matchTexts 的可点击节点，contextTexts 全部出现时才触发；
 * AUTO_BACK 在前台从 packageScope 跳到其它应用时按返回；同应用内 Activity 跳变（包名不变的包内
 * WebView/广告落地页）在 classPattern 命中特征类名时同样按返回——不写 classPattern 的规则不干预同包导航。
 */
data class AdGuardRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val packageScope: String? = null,
    val action: AdGuardAction = AdGuardAction.CLICK_TEXT,
    val matchTexts: List<String> = emptyList(),
    val contextTexts: List<String> = emptyList(),
    val classPattern: String? = null,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    init {
        require(id.isNotBlank() && id.length <= 64) { localizedText("规则 ID 无效", "Invalid rule ID.") }
        require(name.isNotBlank() && name.length <= 40) { localizedText("规则名称不能为空且不超过 40 字", "The rule name is required and must be at most 40 characters.") }
        packageScope?.let {
            require(PACKAGE_PATTERN.matches(it)) { localizedText("应用包名格式无效", "Invalid app package name.") }
        }
        when (action) {
            AdGuardAction.CLICK_TEXT -> {
                require(matchTexts.isNotEmpty() && matchTexts.size <= 8) {
                    localizedText("点击规则需要 1 到 8 个匹配文本", "Click rules need 1 to 8 match texts.")
                }
                require(matchTexts.all { it.isNotBlank() && it.length <= 30 }) {
                    localizedText("匹配文本不能为空且不超过 30 字", "Match texts must be non-blank and at most 30 characters.")
                }
            }
            AdGuardAction.AUTO_BACK -> {
                require(packageScope != null) {
                    localizedText("返回规则必须指定要守护的应用包名", "Back rules must scope the app package to guard.")
                }
            }
        }
        require(contextTexts.size <= 6 && contextTexts.all { it.isNotBlank() && it.length <= 30 }) {
            localizedText("前置文本最多 6 个且每个不超过 30 字", "At most 6 context texts of at most 30 characters each.")
        }
        classPattern?.let {
            runCatching { Regex(it) }.getOrElse {
                throw IllegalArgumentException(localizedText("Activity 类名正则无效", "Invalid activity class regex."))
            }
        }
        require(cooldownMs in 0..60_000) { localizedText("冷却时间必须在 0 到 60000 毫秒之间", "Cooldown must be between 0 and 60000 ms.") }
    }

    companion object {
        const val MAX_RULES = 50
        const val DEFAULT_COOLDOWN_MS = 2_000L
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }

    /**
     * 同应用内页面跳变是否命中本规则：包名不变、Activity 类名匹配 classPattern。
     * 未写 classPattern 的 AUTO_BACK 规则返回 false——同包导航一律放行。
     */
    fun matchesInAppJump(packageName: String, className: String): Boolean {
        val pattern = classPattern?.takeIf(String::isNotBlank) ?: return false
        return enabled && action == AdGuardAction.AUTO_BACK && packageScope == packageName &&
            runCatching { Regex(pattern).containsMatchIn(className) }.getOrDefault(false)
    }
}

data class AdGuardEvent(
    val atEpochMillis: Long,
    val ruleName: String,
    val packageName: String?,
    val detail: String,
)

data class AdGuardSnapshot(
    val enabled: Boolean,
    val showToast: Boolean,
    val rules: List<AdGuardRule>,
    val events: List<AdGuardEvent>,
    val totalBlocked: Long,
)

/** 配置存取分离：状态由引擎持有并可选持久化，事件经无障碍服务喂入。 */
interface AdGuardController {
    val state: StateFlow<AdGuardSnapshot>
    /** 无障碍服务是否已连接；未连接时守卫不生效但配置仍可使用。 */
    fun accessibilityConnected(): Boolean
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setShowToast(show: Boolean)
    suspend fun addRule(rule: AdGuardRule)
    suspend fun removeRule(ruleId: String)
    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean)
}

object AdGuardDefaults {
    fun rules(): List<AdGuardRule> = listOf(
        AdGuardRule(
            id = "builtin-skip-splash",
            name = localizedText("跳过开屏广告", "Skip splash ads"),
            matchTexts = listOf(localizedText("跳过", "Skip")),
        ),
        AdGuardRule(
            id = "builtin-close-ad-popup",
            name = localizedText("关闭广告弹窗", "Close ad popups"),
            matchTexts = listOf("×", "✕", "✖", localizedText("关闭", "Close"), "close", "Close"),
            contextTexts = listOf(localizedText("广告", "Ads")),
        ),
    )
}
