package xyz.chouxuewei.mobile_agent.device.bridge

import xyz.chouxuewei.mobile_agent.core.localizedText
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.chouxuewei.mobile_agent.core.AgentLog

/**
 * 只绑 127.0.0.1 的最小 HTTP/1.1 服务：协程 accept、连接 keep-alive、
 * Bearer 鉴权（本机其它 App 即使扫到端口也只会拿到 401）、GET /screen 走 MJPEG 流。
 */
class BridgeServer(
    private val controller: BridgeController,
    private val tokenProvider: () -> String,
    private val port: Int = BridgeSettings.DEVICE_PORT,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connections = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    @Volatile
    var listening: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (acceptJob != null) return
        val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        listening = true
        acceptJob = scope.launch(Dispatchers.IO) {
            AgentLog.i("Bridge") { "listening port=$port" }
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (error: SocketException) {
                    if (isActive) AgentLog.w("Bridge", error) { "accept_failed" }
                    break
                }
                connections += client
                launch(Dispatchers.IO) {
                    try {
                        client.soTimeout = IDLE_TIMEOUT_MS
                        serve(client)
                    } catch (error: Exception) {
                        if (error !is SocketException && error !is SocketTimeoutException) {
                            AgentLog.d("Bridge") { "connection_error ${error.message}" }
                        }
                    } finally {
                        connections -= client
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        listening = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }
        AgentLog.i("Bridge") { "stopped" }
    }

    private suspend fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        while (true) {
            val requestLine = readLine(input) ?: return
            if (requestLine.isBlank()) continue
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
                writeResponse(output, 400, BridgeProtocol.errorJson("bad_request", "malformed request line"))
                return
            }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY_BYTES) {
                writeResponse(output, 413, BridgeProtocol.errorJson("payload_too_large", "body exceeds 1MB"))
                return
            }
            val body = if (length > 0) {
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(bytes, offset, length - offset)
                    if (read < 0) return
                    offset += read
                }
                runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    ?: run {
                        writeResponse(output, 400, BridgeProtocol.errorJson("invalid_json", "body is not a JSON object"))
                        return
                    }
            } else null

            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")
            // 预览页 <img> 无法加 Authorization 头：仅 GET /screen 接受 ?token= 等价凭据。
            val queryToken = query.split('&').firstOrNull { it.startsWith("token=") }
                ?.removePrefix("token=")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            val authorized = headers["authorization"] == "Bearer ${tokenProvider()}"
                || (method == "GET" && path == "/screen" && queryToken == tokenProvider())
            if (!authorized) {
                writeResponse(output, 401, BridgeProtocol.errorJson("unauthorized", "missing or invalid bearer token"))
                if (headers["connection"]?.lowercase() == "keep-alive") continue else return
            }

            if (method == "OPTIONS") {
                writeResponse(output, 200, JSONObject().put("ok", true))
                continue
            }
            if (method == "GET" && path == "/screen") {
                socket.soTimeout = 0
                streamScreen(output)
                return
            }

            val response = controller.handle(BridgeController.Request(method, path, body))
            writeResponse(output, response.status, response.body)
            if (headers["connection"]?.lowercase() != "keep-alive" && parts[2] == "HTTP/1.0") return
        }
    }

    /** MJPEG 流：客户端断开（写失败）或桥停止时返回。预览暂停期间不产帧，连接保持。 */
    private suspend fun streamScreen(output: BufferedOutputStream) {
        try {
            val header = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            output.write(header)
            output.flush()
            while (true) {
                val frame = controller.capturePreviewFrame(PREVIEW_JPEG_QUALITY, PREVIEW_MAX_WIDTH)
                if (frame != null) {
                    output.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(frame)
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                // 暂停、限频或服务断开时本帧跳过；连接保持，不发明文注释避免污染严格解析器。
                delay(FRAME_INTERVAL_MS)
            }
        } catch (_: Exception) {
            // 客户端断开或桥停止：写失败即结束流。
        }
    }

    private fun writeResponse(output: BufferedOutputStream, status: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            409 -> "Conflict"; 410 -> "Gone"; 413 -> "Payload Too Large"
            500 -> "Internal Server Error"; 501 -> "Not Implemented"; else -> "OK"
        }
        output.write(("HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Content-Length: ${payload.size}\r\n\r\n").toByteArray(Charsets.UTF_8))
        output.write(payload)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (builder.length < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
        }
        return builder.toString()
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 30_000
        private const val MAX_BODY_BYTES = 1_048_576
        private const val MAX_LINE_BYTES = 8_192
        private const val BOUNDARY = "mobileagent"
        private const val FRAME_INTERVAL_MS = 400L
        private const val PREVIEW_JPEG_QUALITY = 70
        private const val PREVIEW_MAX_WIDTH = 480
    }
}
