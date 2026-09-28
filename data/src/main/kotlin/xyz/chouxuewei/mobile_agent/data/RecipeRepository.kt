package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.Recipe
import xyz.chouxuewei.mobile_agent.core.RecipeAction
import xyz.chouxuewei.mobile_agent.core.RecipeStep

private val Context.recipeDataStore by preferencesDataStore("recipes")

/** 自定义操作模板持久化；内置模板不落盘，由代码下发。 */
class RecipeRepository(context: Context) {
    private val store = context.applicationContext.recipeDataStore
    private val recipesKey = stringPreferencesKey("custom_recipes_json")
    private val json = Json { ignoreUnknownKeys = true }

    val customRecipes: Flow<List<Recipe>> = store.data.map { preferences ->
        preferences[recipesKey]?.let(::decodeRecipes).orEmpty()
    }.distinctUntilChanged()

    suspend fun save(recipes: List<Recipe>) {
        store.edit { it[recipesKey] = encode(recipes.filterNot(Recipe::builtin)) }
    }

    private fun encode(recipes: List<Recipe>): String = buildJsonArray {
        recipes.forEach { recipe ->
            add(buildJsonObject {
                put("id", recipe.id)
                put("name", recipe.name)
                put("description", recipe.description)
                recipe.packageName?.let { put("package_name", it) }
                putJsonArray("params") { recipe.params.forEach { add(it) } }
                putJsonArray("steps") {
                    recipe.steps.forEach { step ->
                        add(buildJsonObject {
                            put("action", step.action.name)
                            putJsonArray("texts") { step.texts.forEach { add(it) } }
                            step.text?.let { put("text", it) }
                            step.packageName?.let { put("package_name", it) }
                            put("timeout_ms", step.timeoutMs)
                            put("optional", step.optional)
                        })
                    }
                }
            })
        }
    }.toString()

    private fun decodeRecipes(raw: String): List<Recipe> = runCatching {
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        array.mapNotNull { item ->
            runCatching {
                val obj = item.jsonObject
                Recipe(
                    id = obj["id"]!!.jsonPrimitive.content,
                    name = obj["name"]!!.jsonPrimitive.content,
                    description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
                    packageName = obj["package_name"]?.jsonPrimitive?.content,
                    params = obj.stringList("params"),
                    steps = (obj["steps"] as? JsonArray).orEmpty().mapNotNull { stepItem ->
                        runCatching {
                            val stepObj = stepItem.jsonObject
                            RecipeStep(
                                action = RecipeAction.valueOf(stepObj["action"]!!.jsonPrimitive.content),
                                texts = stepObj.stringList("texts"),
                                text = stepObj["text"]?.jsonPrimitive?.content,
                                packageName = stepObj["package_name"]?.jsonPrimitive?.content,
                                timeoutMs = stepObj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 4_000,
                                optional = stepObj["optional"]?.jsonPrimitive?.booleanOrNull ?: false,
                            )
                        }.getOrNull()
                    },
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }.orEmpty()
}
