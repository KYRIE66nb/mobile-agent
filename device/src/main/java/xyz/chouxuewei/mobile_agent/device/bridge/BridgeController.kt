package xyz.chouxuewei.mobile_agent.device.bridge

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.DeviceGateway
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.Screenshot
import xyz.chouxuewei.mobile_agent.core.localizedText
import xyz.chouxuewei.mobile_agent.device.accessibility.AgentAccessibilityService

/**
 * HTTP 路由 → DeviceGateway 的薄适配层。selector 解析、batch、采集等编排都在
 * 桌面端 adb_controller.py；这里只暴露会话/观察/动作/应用/截屏原语。
 */
class BridgeController(
    private val gateway: DeviceGateway,
) {
    data class Request(
        val method: String,
        val path: String,
        val body: JSONObject?,
    )

    data class Response(
        val status: Int,
        val body: JSONObject,
    )

    /** 预览暂停（用户认证接管等）时 MJPEG 停止采帧，控制通道不受影响。 */
    val screenPaused = AtomicBoolean(false)

    suspend fun handle(request: Request): Response = try {
        when {
            request.method == "GET" && request.path == "/status" -> status()
            request.method == "POST" && request.path == "/session" -> openSession()
            request.method == "DELETE" && request.path == "/session" -> closeSession(request)
            request.method == "POST" && request.path == "/observe" -> observe(request, screenshot = true)
            request.method == "POST" && request.path == "/inspect" -> observe(request, screenshot = false)
            request.method == "POST" && request.path == "/action" -> action(request)
            request.method == "GET" && request.path == "/apps" -> apps()
            request.method == "POST" && request.path == "/screen/pause" -> {
                screenPaused.set(true)
                Response(200, JSONObject().put("ok", true).put("paused", true))
            }
            request.method == "POST" && request.path == "/screen/resume" -> {
                screenPaused.set(false)
                Response(200, JSONObject().put("ok", true).put("paused", false))
            }
            else -> Response(404, BridgeProtocol.errorJson("not_found", "unknown route"))
        }
    } catch (error: BridgeProtocol.InvalidRequest) {
        Response(400, BridgeProtocol.errorJson("invalid_argument", error.message ?: "invalid request"))
    } catch (error: Exception) {
        AgentLog.e("Bridge", error) { "request_failed ${request.method} ${request.path}" }
        Response(500, BridgeProtocol.errorJson("failure", error.message ?: error.javaClass.simpleName))
    }

    private suspend fun status(): Response {
        val sessionOpen = openSessionId != null
        val accessibility = AgentAccessibilityService.connected != null
        val body = JSONObject()
            .put("ok", true)
            .put("version", 1)
            .put("accessibility", accessibility)
            .put("session_open", sessionOpen)
            .put("screen_paused", screenPaused.get())
        if (sessionOpen) body.put("session_id", openSessionId)
        return Response(200, body)
    }

    @Volatile
    private var openSessionId: String? = null

    private suspend fun openSession(): Response {
        val (status, body) = BridgeProtocol.deviceResultToJson(
            gateway.openSession(ExecutionMode.MAIN_DISPLAY),
        ) { session ->
            openSessionId = session.id
            JSONObject()
                .put("session_id", session.id)
                .put("capabilities", org.json.JSONArray().apply {
                    session.capabilities.sortedBy { it.name }.forEach { put(it.name.lowercase()) }
                })
        }
        if (status == 200) return Response(status, body)
        if (status == 500 && body.optJSONObject("error")?.optString("message")?.contains("Another phone operation") == true) {
            return Response(409, body.apply { body.optJSONObject("error")?.put("code", "session_busy") })
        }
        return Response(status, body)
    }

    private suspend fun closeSession(request: Request): Response {
        val sessionId = request.body?.optString("session_id") ?: openSessionId
            ?: return Response(410, BridgeProtocol.errorJson("session_expired", "no open session"))
        val (status, body) = BridgeProtocol.deviceResultToJson(gateway.closeSession(sessionId)) {
            openSessionId = null
            JSONObject()
        }
        if (status == 200) openSessionId = null
        return Response(status, body)
    }

    private suspend fun observe(request: Request, screenshot: Boolean): Response {
        val body = request.body ?: JSONObject()
        val sessionId = body.optString("session_id").takeIf { it.isNotEmpty() }
            ?: return Response(400, BridgeProtocol.errorJson("invalid_argument", "missing session_id"))
        val mode = body.optString("mode", "both")
        val includeNodes = mode == "tree" || mode == "both" || !screenshot
        val includeScreenshot = screenshot && (mode == "screenshot" || mode == "both")
        val maxNodes = body.optInt("max_nodes", 200).coerceIn(1, 500)
        val result = if (includeScreenshot) gateway.observe(sessionId) else gateway.inspect(sessionId)
        val (status, json) = BridgeProtocol.deviceResultToJson(result) { observation ->
            BridgeProtocol.observationToJson(
                observation.copy(screenshot = if (includeScreenshot) observation.screenshot else null),
                includeNodes = includeNodes,
                maxNodes = maxNodes,
            )
        }
        return Response(status, json)
    }

    private suspend fun action(request: Request): Response {
        val body = request.body
            ?: return Response(400, BridgeProtocol.errorJson("invalid_argument", "missing body"))
        val sessionId = body.optString("session_id").takeIf { it.isNotEmpty() }
            ?: return Response(400, BridgeProtocol.errorJson("invalid_argument", "missing session_id"))
        val rawAction = body.optJSONObject("action")
            ?: return Response(400, BridgeProtocol.errorJson("invalid_argument", "missing action"))
        val action = BridgeProtocol.actionFromJson(rawAction)
        val observationId = body.optString("observation_id").takeIf { it.isNotEmpty() }
        val result = gateway.execute(sessionId, observationId, action)
        return Response(BridgeProtocol.httpStatusFor(result), BridgeProtocol.actionResultToJson(result))
    }

    private suspend fun apps(): Response {
        val (status, body) = BridgeProtocol.deviceResultToJson(gateway.listApps()) { apps ->
            BridgeProtocol.appsToJson(apps)
        }
        return Response(status, body)
    }

    /**
     * MJPEG 帧源：预览暂停期间不采帧（认证接管时屏幕内容不应继续外发）。
     * 返回 null 表示本帧应跳过；截图限频由 AccessibilityDeviceGateway 内部重试覆盖，
     * 这里捕获失败返回 null 让流继续等待下一周期。
     */
    suspend fun capturePreviewFrame(quality: Int, maxWidth: Int): ByteArray? {
        if (screenPaused.get()) return null
        val service = AgentAccessibilityService.connected ?: return null
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val bitmap = try {
            withContext(Dispatchers.Main.immediate) { service.captureDisplay(0) }
        } catch (error: Exception) {
            AgentLog.d("Bridge") { "preview_frame_failed ${error.message}" }
            return null
        } ?: return null
        return try {
            withContext(Dispatchers.Default) {
                val scaled = if (bitmap.width > maxWidth) {
                    val height = bitmap.height * maxWidth / bitmap.width
                    Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
                } else bitmap
                try {
                    ByteArrayOutputStream().use { output ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                        output.toByteArray()
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
