package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.Recipe
import xyz.chouxuewei.mobile_agent.core.RecipeAction
import xyz.chouxuewei.mobile_agent.core.RecipeController
import xyz.chouxuewei.mobile_agent.core.RecipeStep
import xyz.chouxuewei.mobile_agent.core.RecipeStepException
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolAvailability
import xyz.chouxuewei.mobile_agent.core.ToolAvailabilityState
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect

/**
 * 高频操作模板：把常用 App 流程固化为确定性步骤，模型只填参数。
 * 执行在主屏前台进行；某步失败时错误里带步骤序号，
 * 模型应从该步起用 device_observe/device_action 手动兜底。
 */
class RecipeToolProvider(private val controller: RecipeController) : ToolProvider {
    override val id = "recipe"
    override val title get() = localizedText("操作模板", "Action recipes")
    override val description get() = localizedText("把常用 App 流程（如微信发消息）固化为确定性步骤序列，执行时不走模型逐步推理；也支持把验证过的流程保存成模板复用。", "Turn frequent app flows (like sending a WeChat message) into deterministic step sequences that run without per-step model reasoning; proven flows can be saved as reusable recipes.")
    override val definitions get() = listOf(
        ToolDefinition(
            "recipe_list",
            localizedText("列出操作模板", "List action recipes"),
            localizedText("列出全部可用模板（内置 + 自定义）：ID、名称、所需参数、说明。执行前先确认模板存在。", "List all recipes (built-in + custom): ID, name, required params, description. Check before running one."),
            """{"type":"object","properties":{},"additionalProperties":false}""",
            ToolSideEffect.READ,
            id,
            approvalDescription = localizedText("读取操作模板列表。", "List action recipes."),
        ),
        ToolDefinition(
            "recipe_run",
            localizedText("执行操作模板", "Run an action recipe"),
            localizedText("在主屏前台按确定性步骤执行模板，params 填入模板声明的参数值（如 {\"contact\":\"文件传输助手\",\"message\":\"你好\"}）。步骤执行不经过模型推理；失败时返回失败步骤序号，应从该步起用 device_observe + device_action 手动继续。需要无障碍服务在线。", "Run a recipe on the foreground screen through deterministic steps; fill declared params (e.g. {\"contact\":\"File Transfer\",\"message\":\"hi\"}). Steps run without model reasoning; on failure the result carries the failed step index — continue manually from that step with device_observe + device_action. Requires the accessibility service."),
            localizedJsonSchema("""{"type":"object","properties":{"recipe_id":{"type":"string","maxLength":64},"params":{"type":"object","description":localizedText("模板参数键值对", "Recipe parameter key-values"),"additionalProperties":{"type":"string","maxLength":2000}}},"required":["recipe_id"],"additionalProperties":false}"""),
            ToolSideEffect.EXTERNAL_WRITE,
            id,
            approvalDescription = localizedText("在主屏执行一条操作模板。", "Run an action recipe on the main screen."),
        ),
        ToolDefinition(
            "recipe_save",
            localizedText("保存操作模板", "Save an action recipe"),
            localizedText("把验证过的操作流程保存为可复用模板。steps 为动作数组：launch(package_name)、wait_text/tap_text(texts 数组)、input_text(text)、back、sleep(timeout_ms)。texts/text 可用 {参数名} 占位。", "Save a proven flow as a reusable recipe. steps is an action array: launch(package_name), wait_text/tap_text(texts array), input_text(text), back, sleep(timeout_ms). texts and text accept {param} placeholders."),
            localizedJsonSchema("""{"type":"object","properties":{"id":{"type":"string","maxLength":64,"description":localizedText("模板 ID，小写字母数字连字符", "Recipe ID, lowercase letters/digits/hyphens")},"name":{"type":"string","maxLength":40},"description":{"type":"string","maxLength":200},"package_name":{"type":"string","maxLength":255},"params":{"type":"array","items":{"type":"string","maxLength":30},"maxItems":8},"steps":{"type":"array","minItems":1,"maxItems":30,"items":{"type":"object","properties":{"action":{"type":"string","enum":["launch","wait_text","tap_text","input_text","back","sleep"]},"texts":{"type":"array","items":{"type":"string","maxLength":60},"maxItems":8},"text":{"type":"string","maxLength":2000},"package_name":{"type":"string","maxLength":255},"timeout_ms":{"type":"integer","minimum":0,"maximum":60000},"optional":{"type":"boolean"}},"required":["action"],"additionalProperties":false}}},"required":["id","name","steps"],"additionalProperties":false}"""),
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("保存一条操作模板。", "Save an action recipe."),
        ),
        ToolDefinition(
            "recipe_remove",
            localizedText("删除自定义模板", "Remove a custom recipe"),
            localizedText("按 recipe_id 删除自定义模板；内置模板不可删除。", "Remove a custom recipe by recipe_id; built-in recipes cannot be removed."),
            """{"type":"object","properties":{"recipe_id":{"type":"string","maxLength":64}},"required":["recipe_id"],"additionalProperties":false}""",
            ToolSideEffect.LOCAL_WRITE,
            id,
            approvalDescription = localizedText("删除一条自定义操作模板。", "Remove a custom recipe."),
        ),
    )

    override suspend fun availability(): ToolAvailability = ToolAvailability(
        if (controller.accessibilityConnected()) ToolAvailabilityState.AVAILABLE else ToolAvailabilityState.DEGRADED,
        if (controller.accessibilityConnected()) "" else localizedText("无障碍服务未连接，可管理模板但无法执行", "The accessibility service is disconnected; recipes can be managed but not run."),
    )

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        val args = call.arguments()
        when (call.toolId) {
            "recipe_list" -> list()
            "recipe_run" -> run(args)
            "recipe_save" -> save(args)
            "recipe_remove" -> remove(args)
            else -> error(localizedText("操作模板不支持 ${call.toolId}", "Recipes do not support ${call.toolId}"))
        }
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val args = call.arguments()
        when (call.toolId) {
            "recipe_list" -> localizedText("读取操作模板列表", "List action recipes")
            "recipe_run" -> localizedText("执行模板：", "Run recipe: ") +
                (args["recipe_id"]?.jsonPrimitive?.contentOrNull ?: "")
            "recipe_save" -> localizedText("保存模板：", "Save recipe: ") +
                (args["name"]?.jsonPrimitive?.contentOrNull ?: "")
            "recipe_remove" -> localizedText("删除自定义模板", "Remove a custom recipe")
            else -> null
        }
    }.getOrNull()

    private fun list(): ToolResult {
        val recipes = controller.state.value
        return ToolResult(buildJsonObject {
            put("count", recipes.size)
            put("accessibility_connected", controller.accessibilityConnected())
            putJsonArray("recipes") {
                recipes.forEach { recipe ->
                    add(buildJsonObject {
                        put("id", recipe.id)
                        put("name", recipe.name)
                        put("description", recipe.description)
                        recipe.packageName?.let { put("package_name", it) }
                        put("builtin", recipe.builtin)
                        putJsonArray("params") { recipe.params.forEach { add(it) } }
                        put("steps", recipe.steps.size)
                    })
                }
            }
        }.toString(), localizedText("已列出 ${recipes.size} 条操作模板", "Listed ${recipes.size} recipes"))
    }

    private suspend fun run(args: JsonObject): ToolResult {
        val recipeId = args["recipe_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 recipe_id", "Missing parameter: recipe_id"))
        val params = (args["params"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val log = try {
            controller.runRecipe(recipeId, params)
        } catch (error: Exception) {
            val step = (error as? RecipeStepException)?.stepIndex
            error(localizedText(
                "模板在第 ${(step ?: -1) + 1} 步失败：${error.message}；请用 device_observe 观察当前界面后改用 device_action 从该步继续",
                "The recipe failed at step ${(step ?: -1) + 1}: ${error.message}; inspect with device_observe and continue manually with device_action from that step",
            ))
        }
        return ToolResult(buildJsonObject {
            put("recipe_id", recipeId)
            put("completed", true)
            putJsonArray("steps") { log.forEach { add(it) } }
        }.toString(), localizedText("模板执行完成", "Recipe completed"))
    }

    private suspend fun save(args: JsonObject): ToolResult {
        val stepsJson = args["steps"] as? JsonArray
            ?: error(localizedText("缺少参数 steps", "Missing parameter: steps"))
        val steps = stepsJson.map { item ->
            val obj = item.jsonObject
            RecipeStep(
                action = when (obj["action"]?.jsonPrimitive?.contentOrNull) {
                    "launch" -> RecipeAction.LAUNCH
                    "wait_text" -> RecipeAction.WAIT_TEXT
                    "tap_text" -> RecipeAction.TAP_TEXT
                    "input_text" -> RecipeAction.INPUT_TEXT
                    "back" -> RecipeAction.BACK
                    "sleep" -> RecipeAction.SLEEP
                    else -> error(localizedText("未知步骤动作", "Unknown step action"))
                },
                texts = (obj["texts"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                text = obj["text"]?.jsonPrimitive?.contentOrNull,
                packageName = obj["package_name"]?.jsonPrimitive?.contentOrNull,
                timeoutMs = obj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 4_000,
                optional = obj["optional"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        val recipe = Recipe(
            id = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error(localizedText("缺少参数 id", "Missing parameter: id")),
            name = args["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: error(localizedText("缺少参数 name", "Missing parameter: name")),
            description = args["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            params = (args["params"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            steps = steps,
        )
        controller.add(recipe)
        return ToolResult(buildJsonObject {
            put("recipe_id", recipe.id)
            put("recipes_total", controller.state.value.size)
        }.toString(), localizedText("已保存模板「${recipe.name}」", "Recipe \"${recipe.name}\" saved"))
    }

    private suspend fun remove(args: JsonObject): ToolResult {
        val id = args["recipe_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 recipe_id", "Missing parameter: recipe_id"))
        controller.remove(id)
        return ToolResult("""{"removed":true,"recipe_id":"$id"}""", localizedText("已删除模板", "Recipe removed"))
    }
}
