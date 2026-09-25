package xyz.chouxuewei.mobile_agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.chouxuewei.mobile_agent.core.ArtifactStore
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect

class ImageRenderToolProvider(
    context: Context,
    artifacts: ArtifactStore,
) : ToolProvider {
    private val publisher = GeneratedArtifactPublisher(context, artifacts)
    override val id = "image_render"
    override val title = "图像渲染"
    override val description = "把模型生成的 SVG 保存为矢量图，或在本机转换为 PNG 和多帧 GIF。"

    override val definitions = listOf(
        ToolDefinition(
            id = "image_render",
            title = "生成 SVG、PNG 或 GIF",
            description = """
                使用完整、自包含且包含 viewBox 的 SVG 生成文件。format=svg 或 png 时必须提供 svg，format=gif 时必须提供 2 至 30 个 frames，每帧包含完整 svg 和 duration_ms；GIF 由多帧 SVG 顺序渲染，不解析 SVG 内的 SMIL/CSS 动画。禁止脚本、事件属性、foreignObject、内嵌位图、DOCTYPE、实体和网络/文件外部资源。PNG 未传 background_color 时保留透明背景；GIF 不支持透明背景，未传时使用白色。width 与 height 必须同时提供或同时省略，省略时从 SVG 的 width/height 或 viewBox 推导。同名文件自动使用新名称，不覆盖旧产物。
            """.trimIndent(),
            inputSchema = SCHEMA,
            sideEffect = ToolSideEffect.LOCAL_WRITE,
            providerId = id,
            approvalDescription = "在 Mobile Agent 中保存一张 SVG、PNG 或 GIF 图片。",
        ),
    )

    override suspend fun execute(
        call: RequestedToolCall,
        context: ToolExecutionContext,
    ): ToolResult = toolResult {
        require(call.toolId == "image_render") { "图像渲染工具不支持 ${call.toolId}" }
        render(parseImageRenderRequest(call.arguments()), context)
    }

    override fun approvalSummary(call: RequestedToolCall): String? = runCatching {
        val request = parseImageRenderRequest(call.arguments())
        if (request.format == ImageRenderFormat.GIF) {
            "生成 ${request.name} · ${request.frames.size} 帧 GIF"
        } else {
            "生成 ${request.name} · ${request.format.extension.uppercase()}"
        }
    }.getOrNull()

    private suspend fun render(
        request: ImageRenderRequest,
        context: ToolExecutionContext,
    ): ToolResult {
        val firstSource = request.svg ?: request.frames.first().svg
        val firstDocument = parseSvg(firstSource)
        val (width, height) = resolveOutputSize(request, firstDocument)
        validateOutputSize(request.format, width, height)
        val artifact = publisher.publish(
            context = context,
            requestedName = request.name,
            mimeType = request.format.mimeType,
        ) { output ->
            when (request.format) {
                ImageRenderFormat.SVG -> writeUtf8(output, requireNotNull(request.svg))
                ImageRenderFormat.PNG -> writePng(
                    output = output,
                    document = firstDocument,
                    width = width,
                    height = height,
                    backgroundColor = request.backgroundColor?.let(Color::parseColor),
                )
                ImageRenderFormat.GIF -> writeGif(output, request, width, height)
            }
        }
        val frameCount = if (request.format == ImageRenderFormat.GIF) request.frames.size else 1
        val duration = request.frames.sumOf(SvgAnimationFrame::durationMs)
        val result = buildJsonObject {
            put("artifact_id", artifact.id)
            put("name", artifact.name)
            put("mime_type", artifact.mimeType)
            put("size", artifact.sizeBytes)
            put("uri", artifact.contentUri)
            put("format", request.format.value)
            put("width", width)
            put("height", height)
            put("frame_count", frameCount)
            if (request.format == ImageRenderFormat.GIF) put("duration_ms", duration)
        }.toString()
        return ToolResult(
            content = result,
            summary = if (request.format == ImageRenderFormat.GIF) {
                "已生成 ${artifact.name}（$frameCount 帧）"
            } else {
                "已生成 ${artifact.name}"
            },
        )
    }

    private fun parseSvg(source: String): SVG = try {
        SVG.getFromString(source).also { document ->
            val viewBox = document.documentViewBox
            require(viewBox != null && viewBox.width() > 0f && viewBox.height() > 0f) {
                "SVG 根元素必须包含有效的 viewBox"
            }
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("SVG 内容无法解析：${error.message ?: "格式错误"}", error)
    }

    private fun resolveOutputSize(request: ImageRenderRequest, document: SVG): Pair<Int, Int> {
        if (request.width != null && request.height != null) return request.width to request.height
        val viewBox = requireNotNull(document.documentViewBox)
        val documentWidth = document.documentWidth.takeIf { it.isFinite() && it > 0f }
        val documentHeight = document.documentHeight.takeIf { it.isFinite() && it > 0f }
        val width = (documentWidth ?: viewBox.width()).toInt().coerceAtLeast(1)
        val height = (documentHeight ?: viewBox.height()).toInt().coerceAtLeast(1)
        return width to height
    }

    private fun validateOutputSize(format: ImageRenderFormat, width: Int, height: Int) {
        require(width in 1..MAX_OUTPUT_EDGE && height in 1..MAX_OUTPUT_EDGE) {
            "输出宽高必须在 1 到 $MAX_OUTPUT_EDGE 像素之间"
        }
        val pixels = width.toLong() * height
        val limit = if (format == ImageRenderFormat.GIF) MAX_GIF_PIXELS else MAX_STATIC_PIXELS
        require(pixels <= limit) {
            if (format == ImageRenderFormat.GIF) "GIF 像素过多，请缩小宽高" else "图片像素过多，请缩小宽高"
        }
    }

    private fun writeUtf8(output: OutputStream, content: String) {
        output.write(content.toByteArray(Charsets.UTF_8))
    }

    private fun writePng(
        output: OutputStream,
        document: SVG,
        width: Int,
        height: Int,
        backgroundColor: Int?,
    ) {
        val bitmap = renderBitmap(document, width, height, backgroundColor ?: Color.TRANSPARENT)
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun writeGif(
        output: OutputStream,
        request: ImageRenderRequest,
        width: Int,
        height: Int,
    ) {
        val background = Color.parseColor(request.backgroundColor ?: DEFAULT_GIF_BACKGROUND)
        val encoder = GifEncoder(output, width, height, request.loopCount)
        request.frames.forEach { frame ->
            currentCoroutineContext().ensureActive()
            val bitmap = renderBitmap(parseSvg(frame.svg), width, height, background)
            try {
                val rgb = IntArray(width * height)
                bitmap.getPixels(rgb, 0, width, 0, 0, width, height)
                for (index in rgb.indices) rgb[index] = rgb[index] and 0x00FFFFFF
                encoder.addImage(
                    rgb,
                    width,
                    ImageOptions().setDelay(frame.durationMs.toLong(), TimeUnit.MILLISECONDS),
                )
            } finally {
                bitmap.recycle()
            }
        }
        encoder.finishEncoding()
    }

    private fun renderBitmap(
        document: SVG,
        width: Int,
        height: Int,
        backgroundColor: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        document.renderToCanvas(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }

    private companion object {
        const val MAX_OUTPUT_EDGE = 2_048
        const val MAX_STATIC_PIXELS = 4_194_304L
        const val MAX_GIF_PIXELS = 786_432L
        const val DEFAULT_GIF_BACKGROUND = "#FFFFFF"
        val SCHEMA = """
            {
              "type":"object",
              "properties":{
                "name":{"type":"string","minLength":1,"maxLength":120,"description":"输出文件名；扩展名会按 format 规范为 .svg、.png 或 .gif"},
                "format":{"type":"string","enum":["svg","png","gif"]},
                "svg":{"type":"string","maxLength":250000,"description":"format=svg/png 时使用的完整 SVG；必须自包含并带 viewBox"},
                "frames":{"type":"array","minItems":2,"maxItems":30,"description":"format=gif 时使用的完整 SVG 帧，按数组顺序播放","items":{"type":"object","properties":{"svg":{"type":"string","maxLength":250000},"duration_ms":{"type":"integer","minimum":20,"maximum":10000}},"required":["svg","duration_ms"],"additionalProperties":false}},
                "width":{"type":"integer","minimum":1,"maximum":2048,"description":"可选输出宽度；必须和 height 一起提供"},
                "height":{"type":"integer","minimum":1,"maximum":2048,"description":"可选输出高度；必须和 width 一起提供"},
                "background_color":{"type":"string","pattern":"^#[0-9A-Fa-f]{6}$","description":"可选 #RRGGBB；PNG 省略为透明，GIF 省略为白色"},
                "loop_count":{"type":"integer","minimum":0,"maximum":100,"default":0,"description":"GIF 循环次数，0 表示无限循环"}
              },
              "required":["name","format"],
              "additionalProperties":false
            }
        """.trimIndent()
    }
}

internal enum class ImageRenderFormat(
    val value: String,
    val extension: String,
    val mimeType: String,
) {
    SVG("svg", "svg", "image/svg+xml"),
    PNG("png", "png", "image/png"),
    GIF("gif", "gif", "image/gif"),
}

internal data class SvgAnimationFrame(
    val svg: String,
    val durationMs: Int,
)

internal data class ImageRenderRequest(
    val name: String,
    val format: ImageRenderFormat,
    val svg: String?,
    val frames: List<SvgAnimationFrame>,
    val width: Int?,
    val height: Int?,
    val backgroundColor: String?,
    val loopCount: Int,
)

internal fun parseImageRenderRequest(args: JsonObject): ImageRenderRequest {
    val formatValue = args["format"]?.jsonPrimitive?.contentOrNull ?: error("缺少参数 format")
    val format = ImageRenderFormat.entries.firstOrNull { it.value == formatValue }
        ?: throw IllegalArgumentException("format 只支持 svg、png 或 gif")
    val requestedName = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val name = normalizedImageName(requestedName, format)
    val svg = args["svg"]?.jsonPrimitive?.contentOrNull
    val frameValues = args["frames"]?.jsonArray ?: JsonArray(emptyList())
    val frames = frameValues.mapIndexed { index, value ->
        val frame = value.jsonObject
        val source = frame["svg"]?.jsonPrimitive?.contentOrNull
            ?: error("第 ${index + 1} 帧缺少 svg")
        val duration = frame["duration_ms"]?.jsonPrimitive?.intOrNull
            ?: error("第 ${index + 1} 帧缺少 duration_ms")
        require(duration in 20..10_000) { "每帧时长必须在 20 到 10000 毫秒之间" }
        SvgAnimationFrame(source, duration)
    }
    when (format) {
        ImageRenderFormat.SVG, ImageRenderFormat.PNG -> {
            require(!svg.isNullOrBlank()) { "format=${format.value} 时必须提供 svg" }
            require(frames.isEmpty()) { "只有 format=gif 时才能提供 frames" }
        }
        ImageRenderFormat.GIF -> {
            require(svg == null) { "format=gif 时请使用 frames，不要同时提供 svg" }
            require(frames.size in 2..30) { "GIF 需要 2 到 30 帧 SVG" }
        }
    }
    val allSources = svg?.let(::listOf) ?: frames.map(SvgAnimationFrame::svg)
    require(allSources.sumOf(String::length) <= 1_000_000) { "SVG 帧内容总计不能超过 1000000 个字符" }
    allSources.forEach(::validateSvgSafety)
    val width = args["width"]?.jsonPrimitive?.intOrNull
    val height = args["height"]?.jsonPrimitive?.intOrNull
    require((width == null) == (height == null)) { "width 与 height 必须同时提供或同时省略" }
    width?.let { require(it in 1..2_048) { "width 必须在 1 到 2048 之间" } }
    height?.let { require(it in 1..2_048) { "height 必须在 1 到 2048 之间" } }
    val background = args["background_color"]?.jsonPrimitive?.contentOrNull
    require(background == null || COLOR_PATTERN.matches(background)) {
        "background_color 必须使用 #RRGGBB 格式"
    }
    val loopCount = args["loop_count"]?.jsonPrimitive?.intOrNull ?: 0
    require(loopCount in 0..100) { "loop_count 必须在 0 到 100 之间" }
    require(frames.sumOf(SvgAnimationFrame::durationMs) <= 60_000) { "GIF 总时长不能超过 60 秒" }
    return ImageRenderRequest(name, format, svg, frames, width, height, background, loopCount)
}

internal fun normalizedImageName(name: String, format: ImageRenderFormat): String {
    require(name.isNotBlank() && name !in setOf(".", "..") && name.none {
        it == '/' || it == '\\' || Character.isISOControl(it)
    }) { "文件名无效" }
    val dot = name.lastIndexOf('.').takeIf { it in 1 until name.lastIndex }
    val base = if (dot == null) name else name.substring(0, dot)
    val normalized = "$base.${format.extension}"
    require(normalized.length <= 120) { "文件名不能超过 120 个字符" }
    return normalized
}

internal fun validateSvgSafety(source: String) {
    require(source.isNotBlank()) { "SVG 内容不能为空" }
    require(source.length <= 250_000) { "单帧 SVG 不能超过 250000 个字符" }
    require(!FORBIDDEN_DECLARATION.containsMatchIn(source)) { "SVG 不能包含 DOCTYPE 或实体声明" }
    require(!FORBIDDEN_ELEMENT.containsMatchIn(source)) {
        "SVG 不能包含脚本、foreignObject、内嵌位图或嵌入式对象"
    }
    require(!EVENT_ATTRIBUTE.containsMatchIn(source)) { "SVG 不能包含事件属性" }
    require(!CSS_IMPORT.containsMatchIn(source)) { "SVG 不能使用 CSS @import" }
    require(REFERENCE_ATTRIBUTE.findAll(source).all { it.groupValues[1].trim().startsWith("#") }) {
        "SVG href 只能引用当前文档中的 #id"
    }
    require(CSS_URL.findAll(source).all { it.groupValues[1].trim().startsWith("#") }) {
        "SVG url() 只能引用当前文档中的 #id"
    }
}

private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")
private val FORBIDDEN_DECLARATION = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
private val FORBIDDEN_ELEMENT = Regex(
    "<\\s*(?:script|foreignObject|image|iframe|object|embed)\\b",
    RegexOption.IGNORE_CASE,
)
private val EVENT_ATTRIBUTE = Regex("\\s+on[a-z][a-z0-9:_-]*\\s*=", RegexOption.IGNORE_CASE)
private val CSS_IMPORT = Regex("@import\\b", RegexOption.IGNORE_CASE)
private val REFERENCE_ATTRIBUTE = Regex(
    "(?:href|xlink:href)\\s*=\\s*[\"']([^\"']*)[\"']",
    RegexOption.IGNORE_CASE,
)
private val CSS_URL = Regex(
    "url\\s*\\(\\s*[\"']?([^\"')]+)",
    RegexOption.IGNORE_CASE,
)
