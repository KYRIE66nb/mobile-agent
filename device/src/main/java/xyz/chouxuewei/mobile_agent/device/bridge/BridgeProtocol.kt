package xyz.chouxuewei.mobile_agent.device.bridge

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import xyz.chouxuewei.mobile_agent.core.Action
import xyz.chouxuewei.mobile_agent.core.ActionResult
import xyz.chouxuewei.mobile_agent.core.AppTarget
import xyz.chouxuewei.mobile_agent.core.DeviceKey
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.GesturePoint
import xyz.chouxuewei.mobile_agent.core.GestureStroke
import xyz.chouxuewei.mobile_agent.core.LaunchableApp
import xyz.chouxuewei.mobile_agent.core.NodeActionKind
import xyz.chouxuewei.mobile_agent.core.NodeRef
import xyz.chouxuewei.mobile_agent.core.NodeSnapshot
import xyz.chouxuewei.mobile_agent.core.Observation
import xyz.chouxuewei.mobile_agent.core.TextInputMode
import xyz.chouxuewei.mobile_agent.core.localizedText

/**
 * 桌面桥线协议：Observation/Action 与桌面端 adb_controller.py 的字段约定。
 * 节点 JSON 采用紧凑格式——布尔字段只在非默认时出现，tap 预计算可点中心坐标。
 */
object BridgeProtocol {

    fun shortType(className: String?): String? = className
        ?.substringAfterLast('.')
        ?.takeIf { it.isNotBlank() }

    fun nodeToJson(node: NodeSnapshot): JSONObject {
        val json = JSONObject()
        json.put("ref", node.ref.value)
        val bounds = node.bounds
        json.put("rect", JSONArray().apply {
            put(bounds.left); put(bounds.top)
            put(bounds.right - bounds.left); put(bounds.bottom - bounds.top)
        })
        node.text?.let { json.put("text", it) }
        node.contentDescription?.let { json.put("desc", it) }
        node.viewId?.let { json.put("id", it) }
        shortType(node.className)?.let { json.put("type", it) }
        node.packageName?.let { json.put("pkg", it) }
        node.hintText?.let { json.put("hint", it) }
        if (node.editable) json.put("editable", true)
        if (node.focused) json.put("focused", true)
        if (!node.enabled) json.put("enabled", false)
        if (!node.visible) json.put("visible", false)
        if (node.clickable) json.put("clickable", true)
        if (node.longClickable) json.put("long_clickable", true)
        if (node.scrollable) json.put("scrollable", true)
        if (node.selected) json.put("selected", true)
        if (node.checked) json.put("checked", true)
        if (node.supportedActions.isNotEmpty()) {
            json.put("actions", JSONArray().apply {
                node.supportedActions.sortedBy { it.name }.forEach { put(it.name.lowercase()) }
            })
        }
        json.put("tap", JSONArray().apply {
            put((bounds.left + bounds.right) / 2); put((bounds.top + bounds.bottom) / 2)
        })
        return json
    }

    fun observationToJson(
        observation: Observation,
        includeNodes: Boolean,
        maxNodes: Int,
    ): JSONObject {
        val json = JSONObject()
        json.put("observation_id", observation.id)
        json.put("session_id", observation.sessionId)
        json.put("captured_at", observation.capturedAtEpochMillis)
        json.put("viewport", JSONObject()
            .put("width", observation.viewport.width)
            .put("height", observation.viewport.height))
        json.put("rotation", observation.rotationDegrees)
        observation.foregroundPackage?.let { json.put("foreground_package", it) }
        if (includeNodes) {
            val truncated = observation.nodes.size > maxNodes
            val shown = if (truncated) observation.nodes.take(maxNodes) else observation.nodes
            json.put("nodes", JSONArray().apply { shown.forEach { put(nodeToJson(it)) } })
            json.put("truncated", truncated)
            json.put("node_count", observation.nodes.size)
        }
        observation.screenshot?.let { shot ->
            json.put("image", JSONObject()
                .put("base64", Base64.encodeToString(shot.bytes, Base64.NO_WRAP))
                .put("mime", shot.mimeType)
                .put("width", shot.width)
                .put("height", shot.height)
                .put("pixel_to_point", JSONArray().apply { put(1); put(1) }))
        }
        return json
    }

    fun appsToJson(apps: List<LaunchableApp>): JSONObject = JSONObject()
        .put("ok", true)
        .put("apps", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject()
                    .put("label", app.label)
                    .put("package_name", app.packageName))
            }
        })

    class InvalidRequest(message: String) : IllegalArgumentException(message)

    private fun required(json: JSONObject, key: String): String =
        json.optString(key).takeIf { it.isNotEmpty() }
            ?: throw InvalidRequest("missing $key")

    fun actionFromJson(raw: JSONObject): Action = when (val kind = required(raw, "kind")) {
        "tap" -> Action.Tap(int(raw, "x"), int(raw, "y"))
        "long_press" -> Action.LongPress(int(raw, "x"), int(raw, "y"), int(raw, "duration_ms", 700))
        "swipe" -> Action.Swipe(
            int(raw, "x1"), int(raw, "y1"), int(raw, "x2"), int(raw, "y2"),
            int(raw, "duration_ms", 350),
        )
        "input_text" -> Action.InputText(
            text = required(raw, "text"),
            node = raw.optString("node_ref").takeIf { it.isNotEmpty() }?.let(::NodeRef),
            mode = if (raw.optString("mode") == "append") TextInputMode.APPEND else TextInputMode.REPLACE,
        )
        "node_action" -> Action.PerformNodeAction(
            node = NodeRef(required(raw, "node_ref")),
            action = nodeActionKind(required(raw, "action")),
        )
        "gesture" -> {
            val strokes = raw.optJSONArray("strokes")
                ?: throw InvalidRequest("gesture requires strokes")
            require(strokes.length() in 1..10) { "gesture requires 1-10 strokes" }
            Action.MultiStrokeGesture(List(strokes.length()) { index ->
                val stroke = strokes.getJSONObject(index)
                val points = stroke.getJSONArray("points")
                GestureStroke(
                    points = List(points.length()) { p ->
                        val point = points.getJSONArray(p)
                        GesturePoint(point.getInt(0), point.getInt(1))
                    },
                    startTimeMs = stroke.optLong("start_ms", 0),
                    durationMs = stroke.optLong("duration_ms", 500),
                )
            })
        }
        "press_key" -> Action.PressKey(deviceKey(required(raw, "key")))
        "open_app" -> Action.OpenApp(AppTarget(required(raw, "package_name")))
        "wait" -> Action.Wait(raw.optLong("duration_ms", 0))
        "enable_node_access" -> Action.EnableNodeAccess
        else -> throw InvalidRequest("unknown action kind: $kind")
    }

    private fun int(json: JSONObject, key: String, default: Int? = null): Int =
        if (json.has(key)) json.getInt(key)
        else default ?: throw InvalidRequest("missing $key")

    private fun nodeActionKind(name: String): NodeActionKind = when (name) {
        "click" -> NodeActionKind.CLICK
        "long_click" -> NodeActionKind.LONG_CLICK
        "scroll_forward" -> NodeActionKind.SCROLL_FORWARD
        "scroll_backward" -> NodeActionKind.SCROLL_BACKWARD
        "scroll_up" -> NodeActionKind.SCROLL_UP
        "scroll_down" -> NodeActionKind.SCROLL_DOWN
        "scroll_left" -> NodeActionKind.SCROLL_LEFT
        "scroll_right" -> NodeActionKind.SCROLL_RIGHT
        "set_text" -> NodeActionKind.SET_TEXT
        else -> throw InvalidRequest("unknown node action: $name")
    }

    private fun deviceKey(name: String): DeviceKey = when (name) {
        "home" -> DeviceKey.HOME
        "back" -> DeviceKey.BACK
        "recents" -> DeviceKey.RECENTS
        "enter" -> DeviceKey.ENTER
        "escape" -> DeviceKey.ESCAPE
        "delete" -> DeviceKey.DELETE
        "tab" -> DeviceKey.TAB
        "dpad_up" -> DeviceKey.DPAD_UP
        "dpad_down" -> DeviceKey.DPAD_DOWN
        "dpad_left" -> DeviceKey.DPAD_LEFT
        "dpad_right" -> DeviceKey.DPAD_RIGHT
        "volume_up" -> DeviceKey.VOLUME_UP
        "volume_down" -> DeviceKey.VOLUME_DOWN
        else -> throw InvalidRequest("unknown key: $name")
    }

    fun actionResultToJson(result: ActionResult): JSONObject = when (result) {
        is ActionResult.Performed -> JSONObject().put("ok", true).put("performed", true)
            .also { if (result.detail.isNotBlank()) it.put("detail", result.detail) }
        is ActionResult.Unsupported -> errorJson("unsupported", result.reason, 501)
        is ActionResult.SessionExpired -> errorJson("session_expired", result.reason, 410)
        is ActionResult.ObservationMismatch -> errorJson("observation_mismatch", result.reason, 409)
        is ActionResult.TargetMismatch -> errorJson("target_mismatch", result.reason, 409)
        is ActionResult.Failure -> errorJson("failure", result.reason, 500)
    }

    fun errorJson(code: String, message: String, status: Int = 400): JSONObject =
        JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))

    fun httpStatusFor(result: ActionResult): Int = when (result) {
        is ActionResult.Performed -> 200
        is ActionResult.Unsupported -> 501
        is ActionResult.SessionExpired -> 410
        is ActionResult.ObservationMismatch, is ActionResult.TargetMismatch -> 409
        is ActionResult.Failure -> 500
    }

    fun <T> deviceResultToJson(result: DeviceResult<T>, onSuccess: (T) -> JSONObject): Pair<Int, JSONObject> =
        when (result) {
            is DeviceResult.Success -> 200 to onSuccess(result.value).put("ok", true)
            is DeviceResult.Unsupported -> 501 to errorJson("unsupported", result.reason)
            is DeviceResult.SessionExpired -> 410 to errorJson("session_expired", result.reason)
            is DeviceResult.Failure -> 500 to errorJson("failure", result.reason)
        }
}
