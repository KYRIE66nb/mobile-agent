package xyz.chouxuewei.mobile_agent.core.substitution

/**
 * 诊断观察记录：一帧观察的可回放描述。本地保存、限额、不上传。
 * clock 字段显式标记时间戳所属时钟域——不能混用截图时间、墙上时间与 elapsedRealtime。
 */
data class ObservationRecord(
    /** 观察序号（会话内单调递增）。 */
    val seq: Int,
    /** 帧时刻，时钟域见 [clock]。 */
    val atMs: Long,
    /** 时钟域标识："elapsedRealtime" | "test" | ... */
    val clock: String,
    /** 采集通道："projection" | "a11y" | "replay"。 */
    val channel: String,
    /** 几何版本：帧像素尺寸，通道切换/旋转/内容尺寸变化都会改变它。 */
    val geoW: Int,
    val geoH: Int,
    /** 场景判定（BATTLE/NOT_BATTLE/UNVERIFIED）。 */
    val scene: String,
    /** 引擎实际收到的 battle 输入。 */
    val battle: Boolean,
    /** 送入引擎的两侧计数（null=不可信）。 */
    val selfCount: Int? = null,
    val enemyCount: Int? = null,
    /** 每格分类结果（L=亮 E=灭 U=未知），诊断开启时才记录。 */
    val selfCells: List<String>? = null,
    val enemyCells: List<String>? = null,
    /** 本帧产生的事件（toString 摘要，含候选/接受/撤销原因）。 */
    val events: List<String> = emptyList(),
    /** 用户手动标记："missed"=刚才漏了 / "wrong"=刚才错了。 */
    val mark: String? = null,
)

/**
 * 诊断记录编解码：JSONL 逐行存储，向后兼容未知字段。
 * 手写极简编解码（字段平铺、无嵌套对象），不引入 serialization 插件。
 */
object DiagJson {
    fun encode(r: ObservationRecord): String = buildString {
        append("{\"seq\":${r.seq},\"atMs\":${r.atMs}")
        append(",\"clock\":${str(r.clock)},\"channel\":${str(r.channel)}")
        append(",\"geoW\":${r.geoW},\"geoH\":${r.geoH}")
        append(",\"scene\":${str(r.scene)},\"battle\":${r.battle}")
        append(",\"selfCount\":${r.selfCount ?: "null"},\"enemyCount\":${r.enemyCount ?: "null"}")
        append(",\"selfCells\":${list(r.selfCells)},\"enemyCells\":${list(r.enemyCells)}")
        append(",\"events\":${list(r.events) ?: "[]"}")
        append(",\"mark\":${r.mark?.let { str(it) } ?: "null"}")
        append('}')
    }

    fun decode(line: String): ObservationRecord {
        val m = parseObject(line.trim())
        return ObservationRecord(
            seq = m["seq"]?.toLong()?.toInt() ?: 0,
            atMs = m["atMs"]?.toLong() ?: 0L,
            clock = m["clock"] ?: "",
            channel = m["channel"] ?: "",
            geoW = m["geoW"]?.toLong()?.toInt() ?: 0,
            geoH = m["geoH"]?.toLong()?.toInt() ?: 0,
            scene = m["scene"] ?: "",
            battle = m["battle"] == "true",
            selfCount = m["selfCount"]?.toLongOrNull()?.toInt(),
            enemyCount = m["enemyCount"]?.toLongOrNull()?.toInt(),
            selfCells = m["selfCells"]?.let { parseStringArray(it) },
            enemyCells = m["enemyCells"]?.let { parseStringArray(it) },
            events = m["events"]?.let { parseStringArray(it) } ?: emptyList(),
            mark = m["mark"],
        )
    }

    // ---- 极简 JSON：仅支持本格式的平铺对象、字符串、数字、布尔、null、字符串数组 ----

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    private fun list(l: List<String>?): String? = l?.joinToString(",", "[", "]") { str(it) }

    /** 平铺对象 → key→raw 值（字符串保留原文、数组保留原样、标量保留字面量）。 */
    private fun parseObject(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun readString(): String {
            val sb = StringBuilder(); i++ // 跳过开引号
            while (i < s.length) {
                val c = s[i++]
                if (c == '\\' && i < s.length) when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    else -> sb.append(e)
                } else if (c == '"') break
                else sb.append(c)
            }
            return sb.toString()
        }
        skipWs(); if (i < s.length && s[i] == '{') i++
        while (i < s.length && s[i] != '}') {
            skipWs(); if (i >= s.length || s[i] != '"') break
            val key = readString()
            skipWs(); if (i < s.length && s[i] == ':') i++
            skipWs()
            val raw = when {
                i < s.length && s[i] == '"' -> readString()
                i < s.length && s[i] == '[' -> { // 数组保留原始片段
                    var depth = 0; var inStr = false; var esc = false
                    val start = i
                    while (i < s.length) {
                        val c = s[i]; i++
                        if (esc) { esc = false; continue }
                        if (c == '\\' && inStr) { esc = true; continue }
                        if (c == '"') inStr = !inStr
                        if (!inStr) { if (c == '[') depth++; if (c == ']') { depth--; if (depth == 0) break } }
                    }
                    s.substring(start, i)
                }
                else -> { // 标量字面量
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != '}') i++
                    val lit = s.substring(start, i).trim()
                    if (lit == "null") null else lit
                }
            }
            if (raw != null) out[key] = raw
            skipWs(); if (i < s.length && s[i] == ',') i++
        }
        return out
    }

    private fun parseStringArray(raw: String): List<String> {
        if (raw.length < 2 || raw.first() != '[') return emptyList()
        val out = ArrayList<String>()
        var i = 1
        while (i < raw.length - 1) {
            if (raw[i] == '"') {
                val sb = StringBuilder(); i++
                while (i < raw.length) {
                    val c = raw[i++]
                    if (c == '\\' && i < raw.length) sb.append(raw[i++])
                    else if (c == '"') break
                    else sb.append(c)
                }
                out.add(sb.toString())
            } else i++
        }
        return out
    }
}

/**
 * 离线回放：把同一批观察重新送入引擎，返回逐帧事件——
 * 与记录/实时运行比较即可验证识别链路是否一致。
 */
fun SubstitutionEngine.replay(records: List<ObservationRecord>): List<List<TimerEvent>> =
    records.map { onFrame(it.selfCount, it.enemyCount, it.atMs, it.battle) }
