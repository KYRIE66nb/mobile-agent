package xyz.chouxuewei.mobile_agent.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.R
import xyz.chouxuewei.mobile_agent.core.AttachmentRef
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.core.ToolSummary
import xyz.chouxuewei.mobile_agent.ui.theme.LocalChatColors

@Composable
fun ChatIcon(
    @DrawableRes icon: Int,
    label: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon), label, modifier.size(21.dp), tint = tint)
}

/** 字母图标避免依赖图标字体，在不同 Android 版本上仍保持相同外观。 */
@Composable
fun AppGlyph(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = LocalChatColors.current.accentSoft,
    content: Color = LocalChatColors.current.accent,
) {
    Surface(modifier = modifier.size(40.dp), shape = RoundedCornerShape(13.dp), color = container) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = content, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = LocalChatColors.current.tertiary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun AttachmentCard(attachment: AttachmentRef, onRemove: (() -> Unit)? = null) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceRaised,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.isImage) {
                AttachmentThumbnail(attachment, Modifier.size(58.dp))
            } else {
                Surface(shape = RoundedCornerShape(10.dp), color = colors.accentSoft) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        ChatIcon(R.drawable.lucide_file_text, null)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(attachment.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                val type = if (attachment.isImage) "图片 · 发送时上传" else "文件 · 需要时读取"
                val detail = attachment.sizeBytes?.let { "$type · ${formatBytes(it)}" } ?: type
                Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = colors.secondary)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    ChatIcon(R.drawable.lucide_x, "移除 ${attachment.name}")
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: AttachmentRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(attachment.uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = LocalChatColors.current.accentSoft) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChatIcon(R.drawable.lucide_file_text, null)
            }
        }
    }
}

@Composable
fun ArtifactCard(
    artifact: Artifact,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = colors.accentSoft) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        ChatIcon(R.drawable.lucide_file_text, null, tint = colors.accent)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(artifact.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(
                        "${artifact.mimeType} · ${formatBytes(artifact.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondary,
                        maxLines = 1,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onOpen) { Text("预览") }
                TextButton(onClick = onShare) { Text("分享") }
                TextButton(onClick = onReuse) { Text("继续处理") }
                TextButton(onClick = onDelete) { Text("删除", color = colors.error) }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${(bytes / 1_024.0).let { "%.1f".format(it) }} KB"
    else -> "${(bytes / 1_048_576.0).let { "%.1f".format(it) }} MB"
}

@Composable
fun ToolSummaryRow(summary: ToolSummary) {
    val colors = LocalChatColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceRaised,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Surface(Modifier.size(8.dp).padding(top = 4.dp), shape = CircleShape, color = colors.accent) {}
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${summary.state} · ${summary.title}", style = MaterialTheme.typography.labelLarge)
                Text(summary.detail, style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            }
        }
    }
}

/** 轻量原生 Markdown：代码与宽表格横向滚动，链接仅开放 http(s)。 */
@Composable
fun ReplyBody(text: String) {
    val uriHandler = LocalUriHandler.current
    val colors = LocalChatColors.current
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            text.split("```").forEachIndexed { index, block ->
                if (index % 2 == 1) {
                    Text(
                        block.substringAfter('\n', block).trimEnd(),
                        Modifier.fillMaxWidth()
                            .background(colors.muted, RoundedCornerShape(14.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(14.dp),
                        color = colors.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    block.trim().split(Regex("\n\\s*\n")).filter(String::isNotBlank).forEach { paragraph ->
                        MarkdownParagraph(paragraph) { url -> runCatching { uriHandler.openUri(url) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(paragraph: String, onOpenUrl: (String) -> Unit) {
    val colors = LocalChatColors.current
    val heading = paragraph.takeWhile { it == '#' }.length
    val value = if (heading in 1..6) paragraph.drop(heading).trimStart() else paragraph
    val annotated = linkAndBoldText(value, colors.accent)
    val isWide = paragraph.lines().any { line -> line.count { it == '|' } >= 3 }
    ClickableText(
        text = annotated,
        modifier = if (isWide) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
        style = (if (heading in 1..6) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            .copy(color = colors.text),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let { onOpenUrl(it.item) }
        },
    )
}

private fun linkAndBoldText(value: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val regex = Regex("\\[([^\\]]+)]\\((https?://[^ )]+)\\)|\\*\\*([^*]+)\\*\\*")
    var start = 0
    regex.findAll(value).forEach { match ->
        append(value.substring(start, match.range.first))
        if (match.groupValues[2].isNotEmpty()) {
            pushStringAnnotation("url", match.groupValues[2])
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(match.groupValues[1]) }
            pop()
        } else {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[3]) }
        }
        start = match.range.last + 1
    }
    append(value.substring(start))
}
