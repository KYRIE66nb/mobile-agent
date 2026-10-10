package xyz.chouxuewei.mobile_agent.substitution

import android.graphics.Bitmap
import xyz.chouxuewei.mobile_agent.core.substitution.DiagJson
import xyz.chouxuewei.mobile_agent.core.substitution.ObservationRecord
import java.io.File

/**
 * 本地诊断记录器（用户主动开启，默认关闭）：
 * - 内存环形缓存保留最近 [ringSize] 条观察——漏检发生前也有上下文；
 * - 事件/手动标记时把环形缓存快照追加为 JSONL 会话文件；
 * - 只保存 HUD/豆槽小块无损像素（调用方提供裁剪副本），默认不保存整屏；
 * - 数据留在应用私有目录、按 [maxSessionFiles] 限额滚动删除、绝不上传。
 *
 * 线程约定：record/onEvents/mark 在采集回调线程同步调用（轻量内存操作），
 * 文件 IO 由 [io] 回调投递（服务侧切 Dispatchers.IO）。
 */
class DiagnosticRecorder(
    private val dir: File,
    private val ringSize: Int = 400,
    private val maxSessionFiles: Int = 20,
    private val io: (() -> Unit) -> Unit,
) {
    private val ring = ArrayDeque<ObservationRecord>(ringSize)
    private var seq = 0
    private var sessionFile: File? = null
    /** 已落盘的最大 seq——事件落盘只增量追加，不重写环形缓存。 */
    private var lastWrittenSeq = -1

    /** 追加一条观察；mark!=null 的条目同时触发落盘。 */
    fun record(
        atMs: Long, clock: String, channel: String,
        geoW: Int, geoH: Int, scene: String, battle: Boolean,
        selfCount: Int?, enemyCount: Int?,
        selfCells: List<String>? = null, enemyCells: List<String>? = null,
        events: List<String> = emptyList(), mark: String? = null,
    ): ObservationRecord {
        val rec = ObservationRecord(
            seq = seq++, atMs = atMs, clock = clock, channel = channel,
            geoW = geoW, geoH = geoH, scene = scene, battle = battle,
            selfCount = selfCount, enemyCount = enemyCount,
            selfCells = selfCells, enemyCells = enemyCells,
            events = events, mark = mark,
        )
        append(rec)
        return rec
    }

    fun append(rec: ObservationRecord) {
        if (ring.size >= ringSize) ring.removeFirst()
        ring.addLast(rec)
        if (rec.events.isNotEmpty() || rec.mark != null) flush(rec)
    }

    /** 事件产生/手动标记 → 环形上下文中尚未落盘的观察增量追加到会话文件。 */
    private fun flush(trigger: ObservationRecord) {
        val fresh = ring.filter { it.seq > lastWrittenSeq }
        if (fresh.isEmpty()) return
        lastWrittenSeq = fresh.last().seq
        io {
            runCatching {
                dir.mkdirs()
                val f = sessionFile ?: File(dir, "diag_${trigger.atMs}.jsonl").also { sessionFile = it }
                f.appendText(fresh.joinToString("\n") { DiagJson.encode(it) } + "\n")
                enforceCap()
            }
        }
    }

    /** 手动标记触发时附带小块取证图（调用方保证已在回调线程内完成像素拷贝）。 */
    fun dumpCrops(tag: String, atMs: Long, crops: List<Pair<String, Bitmap>>) {
        io {
            crops.forEach { (name, bmp) ->
                runCatching {
                    dir.mkdirs()
                    File(dir, "crop_${atMs}_${tag}_$name.png")
                        .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    /** 限额滚动：只保留最近 [maxSessionFiles] 个会话文件，最旧的先删。 */
    private fun enforceCap() {
        val files = dir.listFiles { f -> f.name.startsWith("diag_") }?.sortedBy { it.name }
            ?: return
        val overflow = files.size - maxSessionFiles
        if (overflow > 0) files.take(overflow).forEach { it.delete() }
    }

    fun close() { sessionFile = null }
}
