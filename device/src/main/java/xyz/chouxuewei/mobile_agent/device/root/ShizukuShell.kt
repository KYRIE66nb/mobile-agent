package xyz.chouxuewei.mobile_agent.device.root

import android.content.pm.PackageManager
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.DeviceResult
import xyz.chouxuewei.mobile_agent.core.ShellResult
import xyz.chouxuewei.mobile_agent.core.localizedText
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku 降级通道：非 Root 设备经用户授权后以 shell(uid 2000) 或 root 身份
 * 执行白名单命令。不走 userService，直接用 Shizuku.newProcess。
 */
object ShizukuShell {

    private const val REQUEST_CODE = 72
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val MAX_OUTPUT_CHARS = 32_000

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** 弹 Shizuku 授权框并挂起等待结果；设备未装 Shizuku 时立即返回 false。 */
    suspend fun ensurePermission(): Boolean {
        if (!binderAlive()) return false
        if (hasPermission()) return true
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return false
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            runCatching {
                Shizuku.addRequestPermissionResultListener(listener)
                cont.invokeOnCancellation {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                }
                Shizuku.requestPermission(REQUEST_CODE)
            }.onFailure {
                runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): DeviceResult<ShellResult> =
        withContext(Dispatchers.IO) {
            if (!binderAlive()) {
                return@withContext DeviceResult.Unsupported(
                    localizedText("未检测到 Shizuku 服务，请先安装并启动 Shizuku", "Shizuku service not found. Install and start Shizuku first."),
                )
            }
            if (!ensurePermission()) {
                return@withContext DeviceResult.Unsupported(
                    localizedText("Shizuku 授权被拒绝", "Shizuku permission was denied."),
                )
            }
            try {
                // Shizuku.newProcess 在 api 13 里被标为 private，但仍是稳定的官方入口——
                // 官方文档推荐的 userService 方案要额外打包 AIDL 服务端进程，反射调用在这里更轻。
                val process = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
                    .invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                // stdout/stderr 必须并发读取，否则缓冲区写满会互相阻塞。
                val stdout = async { process.inputStream.readBytes().toString(Charsets.UTF_8).take(MAX_OUTPUT_CHARS) }
                val stderr = async { process.errorStream.readBytes().toString(Charsets.UTF_8).take(MAX_OUTPUT_CHARS) }
                val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                if (!finished) {
                    runCatching { process.destroy() }
                    return@withContext DeviceResult.Failure(
                        localizedText("命令超时（${timeoutMs}ms），已终止", "Command timed out (${timeoutMs}ms) and was killed."),
                    )
                }
                DeviceResult.Success(ShellResult(process.exitValue(), stdout.await(), stderr.await()))
            } catch (error: Exception) {
                AgentLog.e("Shizuku", error) { "shell_exec_failed" }
                DeviceResult.Failure(error.message ?: localizedText("Shizuku 命令执行失败", "Shizuku command failed."))
            }
        }
}
