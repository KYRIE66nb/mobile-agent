package xyz.chouxuewei.mobile_agent.chat

import xyz.chouxuewei.mobile_agent.core.localizedText
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.ui.theme.LocalChatColors

internal sealed interface ArtifactPreviewContent {
    data class Image(val uri: String) : ArtifactPreviewContent
    data class Html(val source: String) : ArtifactPreviewContent
    data class Text(val source: String) : ArtifactPreviewContent
}

internal enum class ArtifactPreviewKind { IMAGE, HTML, TEXT }

internal fun artifactPreviewKind(mimeType: String, name: String): ArtifactPreviewKind {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        normalizedMime in PREVIEW_IMAGE_MIME_TYPES || extension in PREVIEW_IMAGE_EXTENSIONS ->
            ArtifactPreviewKind.IMAGE
        normalizedMime in PREVIEW_HTML_MIME_TYPES || extension in PREVIEW_HTML_EXTENSIONS ->
            ArtifactPreviewKind.HTML
        else -> ArtifactPreviewKind.TEXT
    }
}

internal suspend fun loadArtifactPreview(
    context: Context,
    artifact: Artifact,
): ArtifactPreviewContent = withContext(Dispatchers.IO) {
    val kind = artifactPreviewKind(artifact.mimeType, artifact.name)
    if (kind == ArtifactPreviewKind.IMAGE) {
        require(artifact.sizeBytes <= MAX_IMAGE_PREVIEW_BYTES) {
            localizedText("图片超过 20 MB，请使用分享功能在其它应用中查看", "This image exceeds 20 MB. Share it to view it in another app.")
        }
        return@withContext ArtifactPreviewContent.Image(artifact.contentUri)
    }
    context.contentResolver.openInputStream(android.net.Uri.parse(artifact.contentUri))?.use { input ->
        when (kind) {
            ArtifactPreviewKind.IMAGE -> error(localizedText("图片预览应直接读取产物 URI", "Image previews must read the artifact URI directly."))
            ArtifactPreviewKind.HTML -> ArtifactPreviewContent.Html(
                input.readUtf8Limited(MAX_HTML_PREVIEW_BYTES, localizedText("HTML 超过 1 MB，无法安全预览", "This HTML file exceeds 1 MB and cannot be previewed safely.")),
            )
            ArtifactPreviewKind.TEXT -> ArtifactPreviewContent.Text(
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    buildString {
                        val buffer = CharArray(8_192)
                        while (length < MAX_TEXT_PREVIEW_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_TEXT_PREVIEW_CHARS - length))
                            if (count < 0) break
                            append(buffer, 0, count)
                        }
                    }
                },
            )
        }
    } ?: error(localizedText("文件不可用", "File unavailable"))
}

@Composable
internal fun ArtifactPreviewDialog(
    artifact: Artifact,
    content: ArtifactPreviewContent?,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Column {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp)) {
                    Text(
                        artifact.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artifact.mimeType,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondary,
                    )
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, colors.divider),
                ) {
                    when (content) {
                        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        is ArtifactPreviewContent.Image -> ArtifactImagePreview(
                            uri = content.uri,
                            name = artifact.name,
                        )
                        is ArtifactPreviewContent.Html -> SandboxedWebPreview(
                            document = sandboxHtmlDocument(
                                source = content.source,
                                foreground = colors.text.toCssColor(),
                                background = colors.surfaceRaised.toCssColor(),
                            ),
                            background = colors.surfaceRaised,
                        )
                        is ArtifactPreviewContent.Text -> SelectionContainer {
                            Text(
                                content.source,
                                Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onShare) { Text(localizedText("分享", "Share")) }
                    TextButton(onClick = onDismiss) { Text(localizedText("关闭", "Close")) }
                }
            }
        }
    }
}

/**
 * WebView 只负责 HTML 排版，不获得脚本、文件或 ContentProvider 访问能力。
 * HTML 会注入 CSP，并由请求拦截器只放行内存资源，避免预览文件偷偷请求外部地址。
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
private fun SandboxedWebPreview(document: String, background: Color) {
    val holder = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            holder[0] = null
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().testTag("artifact-preview-web"),
        factory = { context ->
            WebView(context).apply {
                holder[0] = this
                setBackgroundColor(background.toArgb())
                settings.apply {
                    javaScriptEnabled = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    defaultTextEncodingName = "UTF-8"
                }
                webViewClient = PreviewWebViewClient()
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
            }
        },
    )
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class PreviewWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? = blockedResource(request?.url?.scheme)

    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? =
        blockedResource(url?.substringBefore(':'))

    private fun blockedResource(scheme: String?): WebResourceResponse? =
        if (isAllowedPreviewResourceScheme(scheme)) {
            null
        } else {
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
}

internal fun isAllowedPreviewResourceScheme(scheme: String?): Boolean =
    scheme.equals("data", ignoreCase = true) || scheme.equals("about", ignoreCase = true)

@Composable
private fun ArtifactImagePreview(uri: String, name: String) {
    val context = LocalContext.current
    var failed by remember(uri) { mutableStateOf(false) }
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = android.net.Uri.parse(uri),
            imageLoader = imageLoader,
            contentDescription = name,
            modifier = Modifier.fillMaxSize().testTag("artifact-preview-image"),
            contentScale = ContentScale.Fit,
            onLoading = { failed = false },
            onSuccess = { failed = false },
            onError = { failed = true },
        )
        if (failed) {
            Text(
                localizedText("图片无法解码，可尝试分享后用其它应用打开", "This image could not be decoded. Try sharing it to another app."),
                modifier = Modifier.padding(24.dp),
                color = LocalChatColors.current.secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun sandboxHtmlDocument(
    source: String,
    foreground: String,
    background: String,
): String {
    val guard = """
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; media-src data:; font-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
        <style>html,body{max-width:100%;overflow-wrap:anywhere;color:$foreground;background:$background}img,svg,video,canvas{max-width:100%;height:auto}</style>
    """.trimIndent()
    val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    val html = Regex("<html(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    val headMatch = head.find(source)
    val htmlMatch = html.find(source)
    return when {
        headMatch != null -> source.replaceRange(
            headMatch.range.last + 1,
            headMatch.range.last + 1,
            guard,
        )
        htmlMatch != null -> source.replaceRange(
            htmlMatch.range.last + 1,
            htmlMatch.range.last + 1,
            "<head>$guard</head>",
        )
        else -> "<!doctype html><html><head>$guard</head><body>$source</body></html>"
    }
}

private fun InputStream.readBytesLimited(maxBytes: Int, errorMessage: String): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1_024))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { errorMessage }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun InputStream.readUtf8Limited(maxBytes: Int, errorMessage: String): String {
    val bytes = readBytesLimited(maxBytes, errorMessage)
    return bytes.toString(Charsets.UTF_8)
}

private fun Color.toCssColor(): String = "#%06X".format(toArgb() and 0x00FFFFFF)

private const val MAX_IMAGE_PREVIEW_BYTES = 20 * 1_024 * 1_024
private const val MAX_HTML_PREVIEW_BYTES = 1 * 1_024 * 1_024
private const val MAX_TEXT_PREVIEW_CHARS = 100_000
private val PREVIEW_IMAGE_MIME_TYPES = setOf(
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/gif",
    "image/svg+xml",
)
private val PREVIEW_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "svg")
private val PREVIEW_HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
private val PREVIEW_HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
