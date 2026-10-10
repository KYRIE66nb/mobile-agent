package xyz.chouxuewei.mobile_agent.substitution

import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

/**
 * 单侧豆槽逐格计数：格内中心采样 → 逐像素分类 → 规范豆型校验。
 * 任一格不完整落在有效画面内 → 整侧不可信返回 null（越界 ROI 不得夹边当豆槽）。
 * 抽离成无服务依赖的纯逻辑，便于离线测试。
 */
internal object DotRowCounting {

    fun count(
        access: FrameAccess,
        cells: List<IntArray>,
        classifier: DotClassifier,
        side: TimerSide,
        grid: Int,
        cellInnerFraction: Float,
        onCell: ((DotClassifier.Result) -> Unit)? = null,
    ): Int? {
        val states = ArrayList<DotState>(cells.size)
        val inset = (1f - cellInnerFraction) / 2f
        for (cell in cells) {
            if (!access.contains(cell[0], cell[1], cell[2], cell[3])) return null
            // 只采样格子中心区：避开豆间分隔线与 HUD 边缘混入的背景
            val w = cell[2] - cell[0]; val h = cell[3] - cell[1]
            val l = (cell[0] + w * inset).toInt()
            val t = (cell[1] + h * inset).toInt()
            val r = (cell[2] - w * inset).toInt()
            val b = (cell[3] - h * inset).toInt()
            val result = classifier.classify(access.sample(l, t, r, b, grid), side)
            onCell?.invoke(result)
            states += result.state
        }
        // 规范豆型校验：点亮的豆必须连续且锚定某一端——左锚右锚都算合法。
        // 实测同一物理槽位的锚定方向随分边翻转（训练场自方在左读 L L L E，
        // 实战敌方分到左槽读 E L L L）——方向假设写死必然有一侧瞎。
        // 含 UNKNOWN、洞形（暗-亮-暗）、居中块的帧整帧丢弃，不产状态变化。
        val firstLit = states.indexOfFirst { it == DotState.LIT }
        val lastLit = states.indexOfLast { it == DotState.LIT }
        val anchored = firstLit == 0 || lastLit == states.size - 1
        val contiguous = firstLit >= 0 &&
            (firstLit..lastLit).all { states[it] == DotState.LIT }
        val canonical = states.none { it == DotState.UNKNOWN } &&
            (firstLit < 0 || (anchored && contiguous))
        // 非规范 → null：引擎侧整帧忽略，计数跨过噪声期继续持有
        return if (canonical) states.count { it == DotState.LIT } else null
    }
}
