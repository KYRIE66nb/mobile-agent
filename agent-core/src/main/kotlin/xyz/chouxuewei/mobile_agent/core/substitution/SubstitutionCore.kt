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

/** 分类判定参数；HSV 区间用于识别"点亮"豆（多为高饱和亮色），暗/低饱和为空槽。 */
data class DetectionTuning(
    val litMinSaturation: Float = 0.35f,
    val litMinValue: Float = 0.45f,
    val litMaxVariance: Float = 0.08f,
    val emptyMaxValue: Float = 0.30f,
    val emptyMaxSaturation: Float = 0.45f,
    /** 方差超过该值视为爆闪/遮挡 → UNKNOWN。 */
    val unknownVariance: Float = 0.20f,
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
)

class DotClassifier(private val tuning: DetectionTuning = DetectionTuning()) {
    data class Result(val state: DotState, val confidence: Float)

    fun classify(sample: DotSample): Result {
        if (sample.colorVariance > tuning.unknownVariance) {
            return Result(DotState.UNKNOWN, 0f)
        }
        val litScore = saturationMargin(sample.meanSaturation, tuning.litMinSaturation) +
            valueMargin(sample.meanValue, tuning.litMinValue)
        if (sample.meanSaturation >= tuning.litMinSaturation &&
            sample.meanValue >= tuning.litMinValue &&
            sample.colorVariance <= tuning.litMaxVariance
        ) {
            return Result(DotState.LIT, litScore.coerceIn(0.05f, 1f))
        }
        if (sample.meanValue <= tuning.emptyMaxValue ||
            (sample.meanSaturation <= tuning.emptyMaxSaturation && sample.meanValue <= tuning.litMinValue * 0.8f)
        ) {
            val conf = ((tuning.litMinValue - sample.meanValue) / tuning.litMinValue).coerceIn(0.05f, 1f)
            return Result(DotState.EMPTY, conf)
        }
        return Result(DotState.UNKNOWN, 0f)
    }

    private fun saturationMargin(v: Float, min: Float) = ((v - min) / (1f - min)).coerceIn(0f, 1f)
    private fun valueMargin(v: Float, min: Float) = ((v - min) / (1f - min)).coerceIn(0f, 1f)
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
    /** 决斗场替身术经验冷却（秒）。首版为可配置经验值，非实测校准值。 */
    val cooldownMs: Long = 15_000,
    val frameIntervalMs: Long = 140,
    val layout: TimerLayout = TimerLayout(),
    val tuning: DetectionTuning = DetectionTuning(),
    /** 校准完成后才允许进入识别；默认布局只是校准起点。 */
    val calibrated: Boolean = false,
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
) {
    fun remaining(nowMs: Long) = max(0L, endAtMs - nowMs)
}
