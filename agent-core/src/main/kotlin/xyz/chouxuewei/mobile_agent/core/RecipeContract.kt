package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.StateFlow

/** 操作模板中的单步动作；参数位用 {name} 占位，运行时由调用方填入。 */
enum class RecipeAction {
    /** 启动 packageName 对应的应用。 */
    LAUNCH,
    /** 等待界面上出现 texts 中的任意文本，超时失败（optional 时跳过）。 */
    WAIT_TEXT,
    /** 点击文字或描述命中 texts 的可点击节点。 */
    TAP_TEXT,
    /** 向界面上唯一可编辑节点输入 text（可含参数占位）。 */
    INPUT_TEXT,
    /** 按一次全局返回。 */
    BACK,
    /** 固定等待 timeoutMs 毫秒。 */
    SLEEP,
}

data class RecipeStep(
    val action: RecipeAction,
    val texts: List<String> = emptyList(),
    val text: String? = null,
    val packageName: String? = null,
    val timeoutMs: Long = 4_000,
    /** optional 步骤失败时跳过而不是终止整个配方。 */
    val optional: Boolean = false,
) {
    init {
        require(texts.size <= 8 && texts.all { it.isNotBlank() && it.length <= 60 }) {
            localizedText("匹配文本最多 8 个且每个不超过 60 字", "At most 8 match texts of at most 60 characters each.")
        }
        require(timeoutMs in 0..60_000) { localizedText("等待时间必须在 0 到 60000 毫秒之间", "Timeout must be between 0 and 60000 ms.") }
        when (action) {
            RecipeAction.LAUNCH -> require(packageName != null) {
                localizedText("启动步骤需要 packageName", "A launch step needs packageName.")
            }
            RecipeAction.WAIT_TEXT, RecipeAction.TAP_TEXT -> require(texts.isNotEmpty()) {
                localizedText("文本步骤需要至少一个匹配文本", "A text step needs at least one match text.")
            }
            RecipeAction.INPUT_TEXT -> require(!text.isNullOrBlank()) {
                localizedText("输入步骤需要 text", "An input step needs text.")
            }
            RecipeAction.BACK, RecipeAction.SLEEP -> Unit
        }
    }
}

/**
 * 高频 App 操作模板：把一条常用流程固化为确定性步骤，模型只填参数。
 * 模板在主屏前台执行，需要无障碍服务在线；某步超时/未命中即失败并把步骤号回传，
 * 供模型从该步起改用通用界面操作兜底。
 */
data class Recipe(
    val id: String,
    val name: String,
    val description: String,
    val packageName: String? = null,
    val params: List<String> = emptyList(),
    val steps: List<RecipeStep>,
    val builtin: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 64) { localizedText("模板 ID 无效", "Invalid recipe ID.") }
        require(name.isNotBlank() && name.length <= 40) { localizedText("模板名称不能为空且不超过 40 字", "The recipe name is required and must be at most 40 characters.") }
        require(description.length <= 200) { localizedText("模板说明不超过 200 字", "The recipe description is at most 200 characters.") }
        require(params.size <= 8 && params.all { it.matches(PARAM_PATTERN) }) {
            localizedText("参数名最多 8 个，仅限字母数字下划线", "At most 8 parameters with letters, digits and underscores only.")
        }
        require(steps.isNotEmpty() && steps.size <= 30) {
            localizedText("模板需要 1 到 30 个步骤", "A recipe needs 1 to 30 steps.")
        }
        packageName?.let {
            require(AdGuardRule.PACKAGE_PATTERN.matches(it)) { localizedText("应用包名格式无效", "Invalid app package name.") }
        }
    }

    companion object {
        const val MAX_CUSTOM = 50
        val PARAM_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/** 模板步骤执行失败；stepIndex 为 0 起的步骤序号，-1 表示流程外的通用失败。 */
class RecipeStepException(val stepIndex: Int, message: String) : IllegalStateException(message)

interface RecipeController {
    val state: StateFlow<List<Recipe>>
    fun accessibilityConnected(): Boolean
    suspend fun add(recipe: Recipe)
    suspend fun remove(recipeId: String)
    /** 按模板 ID 执行并返回逐步日志；失败抛出携带步骤序号的异常。 */
    suspend fun runRecipe(recipeId: String, params: Map<String, String>): List<String>
}

object RecipeDefaults {
    /** 微信发消息：搜索联系人到发送的确定性链路；界面随微信版本变化，失败由模型兜底。 */
    fun recipes(): List<Recipe> = listOf(
        Recipe(
            id = "wechat-send-message",
            name = localizedText("微信发消息", "WeChat send message"),
            description = localizedText("打开微信 → 搜索联系人 → 输入内容 → 发送。参数：contact、message", "Open WeChat → search contact → type message → send. Params: contact, message"),
            packageName = "com.tencent.mm",
            params = listOf("contact", "message"),
            builtin = true,
            steps = listOf(
                RecipeStep(RecipeAction.LAUNCH, packageName = "com.tencent.mm"),
                RecipeStep(RecipeAction.WAIT_TEXT, texts = listOf(localizedText("搜索", "Search")), timeoutMs = 10_000),
                RecipeStep(RecipeAction.TAP_TEXT, texts = listOf(localizedText("搜索", "Search"))),
                RecipeStep(RecipeAction.INPUT_TEXT, text = "{contact}"),
                RecipeStep(RecipeAction.SLEEP, timeoutMs = 800),
                RecipeStep(RecipeAction.TAP_TEXT, texts = listOf("{contact}"), timeoutMs = 5_000),
                RecipeStep(RecipeAction.INPUT_TEXT, text = "{message}"),
                RecipeStep(RecipeAction.TAP_TEXT, texts = listOf(localizedText("发送", "Send")), timeoutMs = 5_000),
            ),
        ),
    )
}
