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

/**
 * 替身检测调参。颜色盒与比例阈值决定"每格亮/灭"；其余时间窗决定"何时起算/是否证伪"。
 * 标注"待真机复核"的参数须按 §7.3 与任务 11 验收结果回调。
 */
data class DetectionTuning(
    /** 点亮豆·青蓝盒（未满状态）。 */
    val litCyanBox: RgbBox = RgbBox(0, 120, 170, 225, 255, 255),
    /** 点亮豆·金色盒（四颗全亮时整排变金）。 */
    val litGoldBox: RgbBox = RgbBox(179, 19, 1, 255, 255, 206),
    /** 熄灭豆槽颜色盒（深藏青）。 */
    val dimBox: RgbBox = RgbBox(0, 0, 0, 90, 100, 150),
    /** 近黑下限：max(r,g,b) 不超过该值的像素既不算亮也不算灭（黑屏转场防"假全灭"）。 */
    val blackFloor: Int = 10,
    /** 格内命中点亮盒的像素占比 ≥ 该值且占优 → LIT。 */
    val litPixelMinRatio: Float = 0.12f,
    /** 格内命中熄灭盒的像素占比 ≥ 该值且占优 → EMPTY。 */
    val dimPixelMinRatio: Float = 0.40f,
    /** 每格采样网格（grid×grid 像素）。 */
    val sampleGrid: Int = 7,
    /** 格子内缩比例：只采样中心区域，避开豆间分隔与 HUD 边缘。 */
    val cellInnerFraction: Float = 0.6f,
    /** 新计数需被"可读帧"连续看到该次数才确认；不可读帧不推进也不重置。 */
    val confirmFrames: Int = 2,
    /** 单格 UNKNOWN 沿用记忆值的最长时长；超过则整行不可读。 */
    val cellHoldMs: Long = 400,
    /** 掉豆后在该窗口内弹回原值 → 闪光假掉落，回滚计时。 */
    val reboundRetractMs: Long = 500,
    /** 涨豆后在该窗口内掉豆 → 奥义清豆/涨豆残影，不计时。 */
    val gainFlickerMs: Long = 350,
    /** 该窗口内连续两次 −1 → 连扣（奥义逐帧扣豆），回滚链内计时。 */
    val cascadeWindowMs: Long = 400,
    /** 证据空白 > 该值 → 锚点取中点且标记 uncertain。 */
    val uncertainGapMs: Long = 700,
    /** 证据空白 > 该值 → 触发时刻不可知，报歧义不计时。 */
    val maxAnchorGapMs: Long = 2500,
    /** 双侧在该窗口内相继归零 → 转场整排消失，回滚窗内计时。 */
    val collapseWindowMs: Long = 300,
    /** 双侧不可读/非战斗持续 > 该值 → 基线重建（hud_lost 保留计时）/比赛结束（match_end 清计时）。 */
    val hudLostResetMs: Long = 8000,
    /** 物理先验：冷却剩余 > 该值的掉豆不可能是替身。 */
    val cooldownGraceMs: Long = 1000,
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
            else if (nearBlack(px, tuning.blackFloor)) continue      // 黑屏转场像素不投票
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

    private fun nearBlack(argb: Int, floor: Int): Boolean {
        val r = argb shr 16 and 0xFF; val g = argb shr 8 and 0xFF; val b = argb and 0xFF
        return maxOf(r, g, b) <= floor
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
 * HUD 锚点：与豆色独立的战斗画面证据（血条边框、头像框、对局计时区域等）。
 * rect 为帧归一化矩形；rect 内命中 box 的像素占比 ≥ minRatio 记一次命中。
 * 默认空——真实 HUD/非 HUD 样本标定后才可配置，未配置时场景判定降级为弱启发式。
 */
data class HudAnchor(
    val rect: NormalizedRect,
    val box: RgbBox,
    val minRatio: Float,
)

/** 战斗场景判定：BATTLE 参与推断；NOT_BATTLE 全部按无效观察；UNVERIFIED 表示证据未配置。 */
enum class SceneVerdict { BATTLE, NOT_BATTLE, UNVERIFIED }

/**
 * HUD 门控：只用豆色之外的锚点证据判定"当前是不是战斗画面"——
 * 不能用"豆数算得出来"反推 HUD 有效（加载页也可能在豆槽位置读出暗色块）。
 */
class HudGate(
    private val anchors: List<HudAnchor>,
    minHits: Int? = null,
) {
    private val needed = (minHits ?: anchors.size).coerceAtLeast(1)

    /** 逐锚点采样：返回各锚点命中占比。anchorSamples[i] 对应 anchors[i] 的 ROI 像素。 */
    fun hitRatios(anchorSamples: List<IntArray>): List<Float> =
        anchors.mapIndexed { i, a ->
            val px = anchorSamples.getOrNull(i) ?: intArrayOf()
            if (px.isEmpty()) 0f else px.count { a.box.contains(it) }.toFloat() / px.size
        }

    fun verdict(anchorSamples: List<IntArray>): SceneVerdict {
        if (anchors.isEmpty()) return SceneVerdict.UNVERIFIED
        val hits = hitRatios(anchorSamples).zip(anchors).count { (ratio, a) -> ratio >= a.minRatio }
        return if (hits >= needed) SceneVerdict.BATTLE else SceneVerdict.NOT_BATTLE
    }
}

/**
 * 战斗场景判定：只信豆色之外的 HUD 锚点证据。
 * 锚点未标定 → 恒 UNVERIFIED（不做任何否决；转场整排消失由引擎 collapse 规则兜底）；
 * 已标定 → HudGate 命中数裁决 BATTLE/NOT_BATTLE。
 */
fun evaluateScene(anchors: List<HudAnchor>, anchorSamples: List<IntArray>): SceneVerdict {
    if (anchors.isEmpty()) return SceneVerdict.UNVERIFIED
    return HudGate(anchors).verdict(anchorSamples)
}

/**
 * 从锚点区域采样像素推导颜色盒：各通道取 P10/P90 分位再外扩 tolerance，
 * 抑制零星噪点。供校准页"框一下常驻 HUD 元素"生成锚点用。
 */
fun deriveAnchorBox(pixels: IntArray, tolerance: Int = 30): RgbBox {
    require(pixels.isNotEmpty()) { "锚点区域无像素" }
    fun channel(shift: Int): IntArray = IntArray(pixels.size) { (pixels[it] shr shift) and 0xFF }
    fun pct(sorted: List<Int>, p: Double): Int = sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    val r = channel(16).sorted(); val g = channel(8).sorted(); val b = channel(0).sorted()
    return RgbBox(
        (pct(r, 0.10) - tolerance).coerceIn(0, 255),
        (pct(g, 0.10) - tolerance).coerceIn(0, 255),
        (pct(b, 0.10) - tolerance).coerceIn(0, 255),
        (pct(r, 0.90) + tolerance).coerceIn(0, 255),
        (pct(g, 0.90) + tolerance).coerceIn(0, 255),
        (pct(b, 0.90) + tolerance).coerceIn(0, 255),
    )
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
    /** 战斗场景锚点（独立于豆色）：空 = 未标定，场景判定走降级路径。 */
    val hudAnchors: List<HudAnchor> = emptyList(),
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
    val frameIntervalMs: Long = 100,
    val layout: TimerLayout = TimerLayout(),
    val tuning: DetectionTuning = DetectionTuning(),
    /** 校准完成后才允许进入识别；默认布局只是校准起点。 */
    val calibrated: Boolean = false,
    /** 本地诊断模式：用户主动开启后按限额保存事件上下文观察，默认不保存整屏、不上传。 */
    val diagnosticsEnabled: Boolean = false,
    /** 实战我方可能分到右侧：true 时我方读右槽位（右满豆方向）、敌方读左槽。 */
    val swapSides: Boolean = false,
    val overlayX: Int? = null,
    val overlayY: Int? = null,
)

/** 单侧计时状态（UI 消费快照；altEndAtMs/conflict 由引擎在冷却冲突时写入）。 */
data class SideTimer(
    val side: TimerSide,
    val active: Boolean = false,
    val endAtMs: Long = 0,
    /** 由豆数间接推断 → 一律带"疑似"标记。 */
    val suspected: Boolean = true,
    /** 冷却期内又出现 −1：altEndAtMs 记录了那次掉豆的备选截止。 */
    var conflict: Boolean = false,
    /** 起算锚点（= eventAtMs，保留后者兼容旧字段名）。 */
    val anchorAtMs: Long = 0,
    val eventAtMs: Long = 0,
    /** 证据空白过大 → 锚点为中点估计，UI 显示 "≈"。 */
    val uncertain: Boolean = false,
    /** 冷却冲突时的备选截止（第二落点）。 */
    var altEndAtMs: Long? = null,
    /** 被本计时替换的前任计时——回滚时恢复（链式记忆，用于连续证伪）。 */
    val prevTimer: SideTimer? = null,
) {
    fun remaining(nowMs: Long) = max(0L, endAtMs - nowMs)
}
