package xyz.chouxuewei.mobile_agent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.AppTarget
import xyz.chouxuewei.mobile_agent.core.DeviceKey
import xyz.chouxuewei.mobile_agent.core.GesturePoint
import xyz.chouxuewei.mobile_agent.core.GestureStroke
import xyz.chouxuewei.mobile_agent.core.NodeActionKind
import xyz.chouxuewei.mobile_agent.core.NodeRef
import xyz.chouxuewei.mobile_agent.core.TextInputMode

/** 将核心动作转换为稳定 JSON，避免数据库实体反向进入 agent-core。 */
internal object RecordCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeAction(action: Action): String = buildJsonObject {
        when (action) {
            is Action.Tap -> {
                put("type", "tap"); put("x", action.x); put("y", action.y)
            }
            is Action.LongPress -> {
                put("type", "long_press"); put("x", action.x); put("y", action.y)
                put("duration_ms", action.durationMs)
            }
            is Action.Swipe -> {
                put("type", "swipe"); put("start_x", action.startX); put("start_y", action.startY)
                put("end_x", action.endX); put("end_y", action.endY); put("duration_ms", action.durationMs)
            }
            is Action.InputText -> {
                put("type", "input_text"); put("text", action.text)
                put("mode", action.mode.name)
                action.node?.let { put("node_ref", it.value) }
            }
            is Action.PerformNodeAction -> {
                put("type", "node_action")
                put("node_ref", action.node.value)
                put("action", action.action.name)
            }
            is Action.MultiStrokeGesture -> {
                put("type", "multi_stroke_gesture")
                putJsonArray("strokes") { action.strokes.forEach { stroke -> add(buildJsonObject {
                    put("start_time_ms", stroke.startTimeMs)
                    put("duration_ms", stroke.durationMs)
                    putJsonArray("points") { stroke.points.forEach { point -> add(buildJsonObject {
                        put("x", point.x); put("y", point.y)
                    }) } }
                }) } }
            }
            is Action.PressKey -> {
                put("type", "press_key"); put("key", action.key.name)
            }
            is Action.OpenApp -> {
                put("type", "open_app"); put("package_name", action.target.packageName)
            }
            is Action.Wait -> {
                put("type", "wait"); put("duration_ms", action.durationMs)
            }
            Action.EnableNodeAccess -> put("type", "enable_node_access")
        }
    }.toString()

    fun decodeAction(value: String): Action {
        val root = json.parseToJsonElement(value).jsonObject
        return when (root.string("type")) {
            "tap" -> Action.Tap(root.int("x"), root.int("y"))
            "long_press" -> Action.LongPress(root.int("x"), root.int("y"), root.int("duration_ms"))
            "swipe" -> Action.Swipe(
                root.int("start_x"), root.int("start_y"), root.int("end_x"), root.int("end_y"),
                root.int("duration_ms"),
            )
            "input_text" -> Action.InputText(
                root.string("text"),
                root.optionalString("node_ref")?.let(::NodeRef),
                root.optionalString("mode")?.let { TextInputMode.valueOf(it) } ?: TextInputMode.REPLACE,
            )
            "node_action" -> Action.PerformNodeAction(
                NodeRef(root.string("node_ref")),
                NodeActionKind.valueOf(root.string("action")),
            )
            "multi_stroke_gesture" -> Action.MultiStrokeGesture(
                root["strokes"]?.let { values ->
                    values as? kotlinx.serialization.json.JsonArray
                }?.map { strokeValue ->
                    val stroke = strokeValue.jsonObject
                    GestureStroke(
                        points = (stroke["points"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { pointValue ->
                            val point = pointValue.jsonObject
                            GesturePoint(point.int("x"), point.int("y"))
                        },
                        startTimeMs = stroke.long("start_time_ms"),
                        durationMs = stroke.long("duration_ms"),
                    )
                } ?: error("记录缺少字段 strokes"),
            )
            "press_key" -> Action.PressKey(DeviceKey.valueOf(root.string("key")))
            "open_app" -> Action.OpenApp(AppTarget(root.string("package_name")))
            "wait" -> Action.Wait(root.long("duration_ms"))
            "enable_node_access" -> Action.EnableNodeAccess
            else -> error("未知动作记录")
        }
    }

    fun encodeResult(result: ActionResult): String = buildJsonObject {
        when (result) {
            is ActionResult.Performed -> {
                put("type", "performed"); put("detail", result.detail)
            }
            is ActionResult.Unsupported -> {
                put("type", "unsupported"); put("reason", result.reason)
            }
            is ActionResult.SessionExpired -> {
                put("type", "session_expired"); put("reason", result.reason)
            }
            is ActionResult.ObservationMismatch -> {
                put("type", "observation_mismatch"); put("reason", result.reason)
            }
            is ActionResult.TargetMismatch -> {
                put("type", "target_mismatch"); put("reason", result.reason)
            }
            is ActionResult.Failure -> {
                put("type", "failure"); put("reason", result.reason); put("retryable", result.retryable)
            }
        }
    }.toString()

    fun decodeResult(value: String): ActionResult {
        val root = json.parseToJsonElement(value).jsonObject
        return when (root.string("type")) {
            "performed" -> ActionResult.Performed(root.optionalString("detail").orEmpty())
            "unsupported" -> ActionResult.Unsupported(root.string("reason"))
            "session_expired" -> ActionResult.SessionExpired(root.string("reason"))
            "observation_mismatch" -> ActionResult.ObservationMismatch(root.string("reason"))
            "target_mismatch" -> ActionResult.TargetMismatch(root.string("reason"))
            "failure" -> ActionResult.Failure(
                root.string("reason"),
                root["retryable"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            else -> error("未知动作结果记录")
        }
    }

    private fun JsonObject.string(name: String): String =
        optionalString(name) ?: error("记录缺少字段 $name")

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: error("记录字段 $name 不是整数")

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: error("记录字段 $name 不是整数")
}
