package xyz.chouxuewei.mobile_agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.chouxuewei.mobile_agent.core.AgentLog
import xyz.chouxuewei.mobile_agent.core.localizedText

/**
 * 应用内更新：对照 GitHub Releases 的最新版本，下载 APK 后交给系统安装器完成覆盖安装。
 * 更新要求包名一致且签名一致；签名不一致时系统安装器会拒绝，应用数据仅在签名一致时保留。
 */
class AppUpdater(private val context: Context) {

    data class UpdateInfo(
        val version: String,
        val releaseNotes: String,
        val apkUrl: String,
        val apkBytes: Long,
    )

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    sealed interface DownloadResult {
        data class Ready(val file: File) : DownloadResult
        data class Failed(val message: String) : DownloadResult
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "mobile-agent-updater")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching CheckResult.Failed(
                        localizedText("检查更新失败（HTTP ${response.code}）", "Update check failed (HTTP ${response.code})")
                    )
                }
                val body = response.body?.string()
                    ?: return@runCatching CheckResult.Failed(localizedText("检查更新失败：响应为空", "Update check failed: empty response"))
                parseRelease(body, currentVersion)
            }
        }.getOrElse {
            AgentLog.w(TAG) { "Update check failed: ${it.message}" }
            CheckResult.Failed(localizedText("检查更新失败：${it.message ?: "网络错误"}", "Update check failed: ${it.message ?: "network error"}"))
        }
    }

    private fun parseRelease(body: String, currentVersion: String): CheckResult {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content ?: return CheckResult.Failed(
            localizedText("检查更新失败：响应缺少版本号", "Update check failed: missing tag")
        )
        val remoteVersion = tag.removePrefix("v")
        if (!isNewer(remoteVersion, currentVersion)) return CheckResult.UpToDate
        val apk = root["assets"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
            ?: return CheckResult.Failed(localizedText("新版本没有可下载的安装包", "Latest release has no APK asset"))
        val url = apk["browser_download_url"]?.jsonPrimitive?.content
            ?: return CheckResult.Failed(localizedText("新版本没有可下载的安装包", "Latest release has no APK asset"))
        return CheckResult.Available(
            UpdateInfo(
                version = remoteVersion,
                releaseNotes = root["body"]?.jsonPrimitive?.content.orEmpty(),
                apkUrl = url,
                apkBytes = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        )
    }

    /** 三段语义化版本比较，忽略预发布后缀。 */
    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val diff = (r.getOrElse(i) { 0 }) - (l.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }

    suspend fun download(url: String, onProgress: (Float) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "app-update.apk")
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@runCatching DownloadResult.Failed(
                            localizedText("下载失败（HTTP ${response.code}）", "Download failed (HTTP ${response.code})")
                        )
                    }
                    val body = response.body
                        ?: return@runCatching DownloadResult.Failed(localizedText("下载失败：响应为空", "Download failed: empty response"))
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) onProgress(downloaded.toFloat() / total)
                            }
                        }
                    }
                }
                DownloadResult.Ready(target)
            }.getOrElse {
                target.delete()
                AgentLog.w(TAG) { "APK download failed: ${it.message}" }
                DownloadResult.Failed(localizedText("下载失败：${it.message ?: "网络错误"}", "Download failed: ${it.message ?: "network error"}"))
            }
        }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val REPO = "KYRIE66nb/mobile-agent"
    }
}
