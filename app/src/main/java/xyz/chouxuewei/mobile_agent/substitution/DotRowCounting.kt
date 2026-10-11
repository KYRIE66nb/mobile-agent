package xyz.chouxuewei.mobile_agent.substitution

import xyz.chouxuewei.mobile_agent.core.substitution.DotClassifier
import xyz.chouxuewei.mobile_agent.core.substitution.DotState
import xyz.chouxuewei.mobile_agent.core.substitution.TimerSide
import xyz.chouxuewei.mobile_agent.device.capture.FrameAccess

/**
 * 豆排逐格读取：只回答"每个采样格此刻亮/灭/未知"，不做行级裁决——
 * 规范豆形校验与格级记忆在 agent-core 的 BeadRowTracker。
 * 任一格落在有效帧外 → 整侧返回 null；被本应用悬浮窗遮挡的格子直接记
 * UNKNOWN 且不采样（投影帧会截到自家悬浮窗；无障碍通道截游戏窗口不含它）。
 */
internal object DotRowCounting {

    /**
     * @param cells 物理像素格列表（左→右），由 TimerLayout.dotCells 给出
     * @param masked 返回 true 的格子视为被悬浮窗遮挡：记 UNKNOWN 且不采样
     * @param onCell 诊断回调：每格分类结果（含 lit/dim 占比）
     * @return 逐格状态；任一格无效（越界）→ null
     */
    fun readCells(
        access: FrameAccess,
        cells: List<IntArray>,
        classifier: DotClassifier,
        side: TimerSide,
        grid: Int,
        cellInnerFraction: Float,
        masked: (IntArray) -> Boolean = { false },
        onCell: ((DotClassifier.Result) -> Unit)? = null,
    ): List<DotState>? {
        val states = ArrayList<DotState>(cells.size)
        val inset = (1f - cellInnerFraction) / 2f
        for (cell in cells) {
            if (!access.contains(cell[0], cell[1], cell[2], cell[3])) return null
            if (masked(cell)) { states += DotState.UNKNOWN; continue }
            val w = cell[2] - cell[0]; val h = cell[3] - cell[1]
            val l = (cell[0] + w * inset).toInt()
            val t = (cell[1] + h * inset).toInt()
            val r = (cell[2] - w * inset).toInt()
            val b = (cell[3] - h * inset).toInt()
            val result = classifier.classify(access.sample(l, t, r, b, grid), side)
            onCell?.invoke(result)
            states += result.state
        }
        return states
    }
}
