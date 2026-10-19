package xyz.chouxuewei.mobile_agent.core.substitution

/**
 * 单侧豆行逐格追踪：把"每帧格子状态列表"翻译成"行计数读数"。
 * - 格级记忆：本帧 UNKNOWN 格沿用上一帧已确认状态（≤cellHoldMs），超期则整行不可读；
 * - 规范豆形：亮豆连续且锚定左端或右端，空洞/居中块/含 UNKNOWN → 整行不可读；
 * - 全灭（全 EMPTY）是合法读数 0。
 * 该层不做计时推断，只回答"这排豆子现在读数是几、可不可信"。
 */
internal class BeadRowTracker(private val cellHoldMs: Long) {
    private data class Held(var state: DotState, var atMs: Long)

    private var held: MutableList<Held>? = null

    /** 返回本帧行计数；null = 该行不可读。cells=null 不推进也不破坏记忆。 */
    fun update(cells: List<DotState>?, atMs: Long): Int? {
        if (cells == null) return null
        val h = held
        if (h == null || h.size != cells.size) {
            held = MutableList(cells.size) { Held(DotState.UNKNOWN, Long.MIN_VALUE) }
        }
        val heldNow = held!!
        val states = ArrayList<DotState>(cells.size)
        for (i in cells.indices) {
            val s = cells[i]
            if (s != DotState.UNKNOWN) {
                heldNow[i].state = s; heldNow[i].atMs = atMs
                states += s
            } else {
                val mem = heldNow[i]
                // 整排暂不可读时仍保存后续格的有效采样，避免交错遮挡把掉豆证据丢掉。
                states += if (mem.state == DotState.UNKNOWN || atMs - mem.atMs > cellHoldMs) {
                    DotState.UNKNOWN
                } else mem.state
            }
        }
        if (!canonicalShape(states)) return null
        return states.count { it == DotState.LIT }
    }

    fun reset() { held = null }

    companion object {
        /** 亮豆连续且锚定一端；全灭返回 true（0 是合法读数）。 */
        fun canonicalShape(states: List<DotState>): Boolean {
            if (states.any { it == DotState.UNKNOWN }) return false
            val firstLit = states.indexOfFirst { it == DotState.LIT }
            if (firstLit < 0) return true
            val lastLit = states.indexOfLast { it == DotState.LIT }
            if (lastLit - firstLit + 1 != states.count { it == DotState.LIT }) return false
            return firstLit == 0 || lastLit == states.size - 1
        }
    }
}
