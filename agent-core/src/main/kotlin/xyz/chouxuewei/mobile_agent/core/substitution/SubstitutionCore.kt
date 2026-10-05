package xyz.chouxuewei.mobile_agent.core.substitution

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 单个豆槽的视觉状态。UNKNOWN 用于爆闪/遮挡/无 HUD 等不可信帧，不参与计数。 */
enum class DotState { LIT, EMPTY, UNKNOWN }

enum class TimerSide { SELF, ENEMY }

/** 一次网格采样的颜色统计；由平台采样层产出一组 ARGB 后统计得到。 */
data class DotSample(
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    val meanHue: Float,
    val meanSaturation: Float,
    val meanValue: Float,
    val colorVariance: Float,
) {
    companion object {
        val EMPTY = DotSample(0f, 0f, 0f, 0f, 0f, 0f, Float.MAX_VALUE)

        /** 从一组 ARGB 像素统计（调用方保证非空且已按 ROI 网格取样）。 */
        fun of(argb: IntArray): DotSample {
            if (argb.isEmpty()) return EMPTY
            var r = 0f; var g = 0f; var b = 0f
            var hue = 0f; var sat = 0f; var value = 0f
            var sq = 0.0
            for (c in argb) {
                val cr = ((c ushr 16) and 0xFF) / 255f
                val cg = ((c ushr 8) and 0xFF) / 255f
                val cb = (c and 0xFF) / 255f
                r += cr; g += cg; b += cb
                val hsv = rgbToHsv(cr, cg, cb)
                hue += hsv[0]; sat += hsv[1]; value += hsv[2]
                sq += cr * cr + cg * cg + cb * cb
            }
            val n = argb.size.toFloat()
            r /= n; g /= n; b /= n; hue /= n; sat /= n; value /= n
            val variance = (sq / argb.size - (r * r + g * g + b * b)).toFloat().coerceAtLeast(0f)
            return DotSample(r, g, b, hue, sat, value, variance)
        }

        /** r/g/b ∈ 0..1 → [h 0..360, s 0..1, v 0..1] */
        fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val d = mx - mn
            val h = when {
                d <= 0f -> 0f
                mx == r -> 60f * (((g - b) / d) % 6f).let { if (it < 0) it + 6f else it }
                mx == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            return floatArrayOf(h, if (mx <= 0f) 0f else d / mx, mx)
        }
    }
}

/** RGB 颜色盒：通道落进区间即命中。参考成熟开源实现的思路——逐像素盒判定而非均值统计。 */
data class RgbBox(
    val minR: Int, val minG: Int, val minB: Int,
    val maxR: Int, val maxG: Int, val maxB: Int,
) {
    fun contains(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return r in minR..maxR && g in minG..maxG && b in minB..maxB
    }
}

/** 分类判定参数。点亮豆逐像素颜色盒 + 格内占比表决。点亮色不分侧而分状态：非满=青蓝、满四颗=金色。 */
data class DetectionTuning(
    /** 点亮豆·青蓝盒（未满状态；开源参考的"亮蓝"盒；R 上限排除纯白闪光）。 */
    val litCyanBox: RgbBox = RgbBox(0, 120, 170, 225, 255, 255),
    /** 点亮豆·金色盒（四颗全亮时整排变金——开源参考的"赤金"盒）。 */
    val litGoldBox: RgbBox = RgbBox(179, 19, 1, 255, 255, 206),
    /** 熄灭豆槽颜色盒（深藏青，放宽以覆盖暗色背景混入）。 */
    val dimBox: RgbBox = RgbBox(0, 0, 0, 90, 100, 150),
    /** 格内命中点亮盒的像素占比 ≥ 该值 → LIT。亮豆中心纯色约占格 20-40%。 */
    val litPixelMinRatio: Float = 0.12f,
    /** 格内命中熄灭盒的像素占比 ≥ 该值 → EMPTY。 */
    val dimPixelMinRatio: Float = 0.40f,
    /** 每格采样网格（grid×grid 像素）。 */
    val sampleGrid: Int = 7,
    /** 格子内缩比例：只采样中心区域，避开豆间分隔与 HUD 边缘。 */
    val cellInnerFraction: Float = 0.6f,
    /** 连续一致帧数才确认计数变化。 */
    val confirmFrames: Int = 3,
    /** 稳定计数窗口内允许的 UNKNOWN 帧比例；超过则整个估计为 null。 */
    val maxUnknownRatio: Float = 0.5f,
    /** 无效 HUD/丢帧超过该时长重建基线。 */
    val invalidBaselineTimeoutMs: Long = 2000,
    /** 同一候选去重窗：同一侧两次 n→n−1 间隔小于该值视为同一事件。 */
    val dedupeMs: Long = 800,
    /** 倒计时进行中又出现新候选 → 标记冲突而非静默覆盖。 */
    val conflictWindowFraction: Float = 0.5f,
    /**
     * 掉落前的"高位计数"须已稳定该时长才接受为替身候选；
     * 涨豆动画/闪光造成的高位驻留 + 落回原值在该窗内被判为抖动而非替身。
     * 代价：涨豆后立刻（<窗长）真替身会被吞——窗长是 FP/FN 的权衡点。
     */
    val minStableBeforeDropMs: Long = 1200,
    /** 悬置候选验证窗：窗口内快回弹撤单、慢回弹按真实涨豆确认。 */
    val pendingVerifyMs: Long = 1500,
    /**
     * 悬置期回弹判定分界：候选后 <该值 弹回原值 = 遮挡/闪烁假掉落 → 撤单；
     * ≥该值 才弹回 = 大概率真实涨豆 → 确认计时（真替身的豆不可能这么快回复）。
     */
    val fastReboundCancelMs: Long = 600,
)

/**
 * 逐像素颜色盒分类器：格内每个采样像素独立判定（点亮盒/熄灭盒/其他），
 * 再按占比表决格子状态——不做均值池化，亮豆不会被背景稀释信号。
 */
class DotClassifier(private val tuning: DetectionTuning = DetectionTuning()) {
    data class Result(val state: DotState, val confidence: Float, val litRatio: Float, val dimRatio: Float)

    fun classify(argb: IntArray, side: TimerSide): Result {
        if (argb.isEmpty()) return Result(DotState.UNKNOWN, 0f, 0f, 0f)
        // 点亮豆双色：未满=青蓝、满四=金色——任一盒命中都算点亮
        var lit = 0
        var dim = 0
        for (px in argb) {
            if (tuning.litCyanBox.contains(px) || tuning.litGoldBox.contains(px)) lit++
            else if (tuning.dimBox.contains(px)) dim++
        }
        val n = argb.size.toFloat()
        val litRatio = lit / n
        val dimRatio = dim / n
        // 亮/暗比优势：谁先过线听谁会让边界豆反复横跳（实测 lit0.12/dim0.47
        // 被判 LIT → 豆数 3↔2 狂抖）。必须占比占优才定性，胶着归 UNKNOWN 丢帧。
        return when {
            litRatio >= tuning.litPixelMinRatio && litRatio > dimRatio ->
                Result(DotState.LIT, litRatio.coerceIn(0.05f, 1f), litRatio, dimRatio)
            dimRatio >= tuning.dimPixelMinRatio && dimRatio > litRatio ->
                Result(DotState.EMPTY, dimRatio.coerceIn(0.05f, 1f), litRatio, dimRatio)
            else -> Result(DotState.UNKNOWN, 0f, litRatio, dimRatio)
        }
    }
}

/** 归一化矩形（相对"游戏内容区域"而非物理屏）。 */
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "归一化坐标必须在 0..1：$this"
        }
        require(right > left && bottom > top) { "矩形必须非空：$this" }
    }

    fun toPixel(contentLeft: Int, contentTop: Int, contentWidth: Int, contentHeight: Int): IntArray =
        intArrayOf(
            (contentLeft + left * contentWidth).toInt(),
            (contentTop + top * contentHeight).toInt(),
            (contentLeft + right * contentWidth).toInt(),
            (contentTop + bottom * contentHeight).toInt(),
        )

    companion object {
        fun of(l: Float, t: Float, r: Float, b: Float) =
            NormalizedRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }
}

/**
 * 布局配置：contentRect 是帧中"游戏有效内容"区域（处理黑边/刘海/单应用共享偏移），
 * selfDots/enemyDots 是该区域内的归一化豆槽矩形，内部等分 dotsPerSide 个采样点。
 */
data class TimerLayout(
    val contentRect: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f),
    val selfDots: NormalizedRect = NormalizedRect(0.03f, 0.13f, 0.22f, 0.20f),
    val enemyDots: NormalizedRect = NormalizedRect(0.78f, 0.13f, 0.97f, 0.20f),
    val dotsPerSide: Int = 4,
) {
    init { require(dotsPerSide in 1..8) { "dotsPerSide 必须在 1..8" } }

    /** 每侧豆槽矩形均分为 dotsPerSide 个水平等宽采样格，返回像素坐标格列表。 */
    fun dotCells(side: TimerSide, frameW: Int, frameH: Int): List<IntArray> {
        val c = contentRect.toPixel(0, 0, frameW, frameH)
        val rect = (if (side == TimerSide.SELF) selfDots else enemyDots)
            .toPixel(c[0], c[1], c[2] - c[0], c[3] - c[1])
        val w = (rect[2] - rect[0]).coerceAtLeast(1)
        return List(dotsPerSide) { i ->
            intArrayOf(
                rect[0] + w * i / dotsPerSide,
                rect[1],
                rect[0] + w * (i + 1) / dotsPerSide,
                rect[3],
            )
        }
    }
}

data class TimerConfig(
    val enabled: Boolean = true,
    val autoStartOnAppOpen: Boolean = true,
    val gamePackage: String = "",
    val showSelfTimer: Boolean = true,
    /** 决斗场替身术冷却。开源实现常用经验值 13.5s；如有偏差可在设置页调整。 */
    val cooldownMs: Long = 15_000,
    val frameIntervalMs: Long = 80,
    val layout: TimerLayout = TimerLayout(),
    val tuning: DetectionTuning = DetectionTuning(),
    /** 校准完成后才允许进入识别；默认布局只是校准起点。 */
    val calibrated: Boolean = false,
    /** 实战我方可能分到右侧：true 时我方读右槽位（右满豆方向）、敌方读左槽。 */
    val swapSides: Boolean = false,
    val overlayX: Int? = null,
    val overlayY: Int? = null,
)

/** 单侧计时显示状态。 */
data class SideTimer(
    val side: TimerSide,
    val active: Boolean = false,
    val endAtMs: Long = 0,
    /** 由豆数间接推断 → 一律带"疑似"标记；conflict 表示覆盖过一个未走完的计时。 */
    val suspected: Boolean = true,
    val conflict: Boolean = false,
    val eventAtMs: Long = 0,
    /** 候选尚在回弹验证窗内——掉豆被推翻时计时会被撤销。 */
    val pending: Boolean = false,
) {
    fun remaining(nowMs: Long) = max(0L, endAtMs - nowMs)
}
