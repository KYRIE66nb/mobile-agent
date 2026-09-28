package xyz.chouxuewei.mobile_agent.tools

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * OOXML(docx/xlsx) 的纯 Kotlin 读写与手术式编辑——不碰 Android API，可 JVM 单测。
 *
 * 保真策略：只重写目标 XML 片段（docx 按 <w:p> 段落，xlsx 按 <c> 单元格），
 * 其余 zip 条目（图片、样式、主题）原样透传，最大限度保留原文件格式。
 * 段落修改保留 pPr 与首 run 的 rPr；xlsx 新值一律写 inlineStr，不动 sharedStrings。
 */
object OoxmlEditor {

    // ---------- zip ----------

    fun zipEntries(bytes: ByteArray, maxEntryBytes: Long = 64L * 1024 * 1024): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.size <= maxEntryBytes) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }

    fun zipBytes(output: OutputStream, files: Map<String, ByteArray>) {
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun unescapeXml(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("&#(x?[0-9A-Fa-f]+);")) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x", true)) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: ""
        }

    // ==================== DOCX ====================

    private val docxParagraph = Regex("<w:p\\b[^>]*/>|<w:p\\b[^>]*>.*?</w:p>", RegexOption.DOT_MATCHES_ALL)

    fun docxParagraphs(xml: String): List<MatchResult> = docxParagraph.findAll(xml).toList()

    fun docxParagraphText(paragraphXml: String): String = unescapeXml(
        paragraphXml
            .replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:br\\s*/>|<w:cr\\s*/>"), "\n")
            .replace(Regex("<[^>]+>"), ""),
    )

    fun extractDocxText(bytes: ByteArray): String {
        val doc = zipEntries(bytes)["word/document.xml"] ?: error("DOCX 缺少 word/document.xml")
        var xml = String(doc, Charsets.UTF_8)
        xml = xml.replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:br\\s*/>|<w:cr\\s*/>"), "\n")
            .replace(Regex("</w:p>"), "\n")
        return unescapeXml(xml.replace(Regex("<[^>]+>"), ""))
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    data class DocxInspection(val totalParagraphs: Int, val paragraphs: List<ParagraphInfo>)
    data class ParagraphInfo(val index: Int, val preview: String)

    fun inspectDocx(bytes: ByteArray, maxListed: Int = 200): DocxInspection {
        val doc = zipEntries(bytes)["word/document.xml"] ?: error("DOCX 缺少 word/document.xml")
        val xml = String(doc, Charsets.UTF_8)
        val all = docxParagraphs(xml)
        val listed = all.mapIndexedNotNull { index, m ->
            val text = docxParagraphText(m.value).trim()
            if (text.isEmpty()) null else ParagraphInfo(index, text.take(80))
        }.take(maxListed)
        return DocxInspection(all.size, listed)
    }

    /** 保留 pPr 和首 run 的 rPr，重写段落正文为单 run 纯文本。 */
    private fun rewriteParagraph(paragraphXml: String, text: String): String {
        val openEnd = paragraphXml.indexOf('>')
        if (openEnd < 0) return paragraphXml
        val openTag = paragraphXml.substring(0, openEnd + 1)
        val runBody = "<w:r>${extractOrEmpty(paragraphXml, "<w:rPr>")}<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r>"
        if (openTag.endsWith("/>")) {
            return openTag.removeSuffix("/") + ">" + runBody + "</w:p>"
        }
        val inner = paragraphXml.substring(openEnd + 1, paragraphXml.lastIndexOf("</w:p>").takeIf { it >= 0 } ?: paragraphXml.length)
        val pPr = extractOrEmpty(inner, "<w:pPr>")
        return "$openTag$pPr$runBody</w:p>"
    }

    private fun extractOrEmpty(xml: String, tag: String): String =
        Regex("${Regex.escape(tag)}.*?</${tag.drop(1).dropLast(1)}>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value.orEmpty()

    private fun newParagraph(text: String, templateXml: String?): String {
        val pPr = templateXml?.let { extractOrEmpty(it, "<w:pPr>") }.orEmpty()
        val rPr = templateXml?.let { extractOrEmpty(it, "<w:rPr>") }.orEmpty()
        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r></w:p>"
    }

    /** 遍历 docx 段落并逐个变换；transform 返回 null 保留原样，"" 删除，否则替换。 */
    private fun transformDocxParagraphs(xml: String, transform: (index: Int, paragraphXml: String, text: String) -> String?): String {
        val sb = StringBuilder(xml.length + 256)
        var cursor = 0
        docxParagraph.findAll(xml).forEachIndexed { index, m ->
            val replacement = transform(index, m.value, docxParagraphText(m.value))
            if (replacement != null) {
                sb.append(xml, cursor, m.range.first)
                sb.append(replacement)
                cursor = m.range.last + 1
            }
        }
        sb.append(xml, cursor, xml.length)
        return sb.toString()
    }

    data class EditOutcome(val bytes: ByteArray, val applied: Int, val warnings: List<String>)

    fun editDocx(bytes: ByteArray, ops: List<JsonObject>): EditOutcome {
        val entries = zipEntries(bytes).toMutableMap()
        var xml = String(entries["word/document.xml"] ?: error("DOCX 缺少 word/document.xml"), Charsets.UTF_8)
        val warnings = mutableListOf<String>()
        var applied = 0
        ops.forEachIndexed { opIndex, op ->
            val kind = op["op"]?.jsonPrimitive?.contentOrNull
                ?: error("第 ${opIndex + 1} 个操作缺少 op")
            val paragraphCount = { docxParagraphs(xml).size }
            when (kind) {
                "set_paragraph" -> {
                    val index = requiredInt(op, "index")
                    require(index >= 0 && index < paragraphCount()) { "段落索引 $index 越界（共 ${paragraphCount()} 段）" }
                    val text = requiredString(op, "text")
                    xml = transformDocxParagraphs(xml) { i, px, _ -> if (i == index) rewriteParagraph(px, text) else null }
                    applied++
                }
                "delete_paragraph" -> {
                    val index = requiredInt(op, "index")
                    require(index >= 0 && index < paragraphCount()) { "段落索引 $index 越界（共 ${paragraphCount()} 段）" }
                    xml = transformDocxParagraphs(xml) { i, _, _ -> if (i == index) "" else null }
                    applied++
                }
                "insert_paragraph" -> {
                    val after = requiredInt(op, "after_index")
                    require(after >= -1 && after < paragraphCount()) { "after_index $after 越界（共 ${paragraphCount()} 段，-1 表示插到最前）" }
                    val text = requiredString(op, "text")
                    xml = transformDocxParagraphs(xml) { i, px, _ -> if (i == after) px + newParagraph(text, px) else null }
                    if (after == -1) {
                        Regex("<w:body\\b[^>]*>").find(xml)?.let { m ->
                            xml = xml.substring(0, m.range.last + 1) + newParagraph(text, null) + xml.substring(m.range.last + 1)
                        }
                    }
                    applied++
                }
                "append" -> {
                    val text = requiredString(op, "text")
                    val lastPara = docxParagraphs(xml).lastOrNull()?.value
                    val para = newParagraph(text, lastPara)
                    xml = if (xml.contains("<w:sectPr")) {
                        xml.replaceFirst(Regex("<w:sectPr\\b"), para + "<w:sectPr")
                    } else {
                        xml.replace("</w:body>", para + "</w:body>")
                    }
                    applied++
                }
                "replace" -> {
                    val find = requiredString(op, "find")
                    val repl = op["replace"]?.jsonPrimitive?.contentOrNull ?: ""
                    val all = op["all"]?.jsonPrimitive?.booleanOrNull ?: true
                    var hits = 0
                    xml = transformDocxParagraphs(xml) { _, px, text ->
                        if (text.contains(find) && (all || hits == 0)) {
                            hits++
                            applied++
                            rewriteParagraph(px, text.replace(find, repl, ignoreCase = false))
                        } else null
                    }
                    if (hits == 0) warnings += "未找到文本「${find.take(40)}」，该替换被跳过"
                }
                else -> error("不支持的 docx 操作 $kind")
            }
        }
        entries["word/document.xml"] = xml.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        zipBytes(out, entries)
        return EditOutcome(out.toByteArray(), applied, warnings)
    }

    // ==================== XLSX ====================

    private fun String.extractXmlTag(tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(this)?.groupValues?.get(1)

    private fun String.columnIndex(): Int {
        var value = 0
        for (ch in this) value = value * 26 + (ch - 'A' + 1)
        return value - 1
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        var name = ""
        while (value > 0) { value--; name = ('A' + value % 26) + name; value /= 26 }
        return name
    }

    private fun cellRefParts(ref: String): Pair<Int, Int> {
        val m = Regex("([A-Za-z]+)(\\d+)").matchEntire(ref.uppercase())
            ?: error("无效的单元格引用 $ref")
        return m.groupValues[1].columnIndex() to m.groupValues[2].toInt() - 1
    }

    private fun sharedStrings(entries: Map<String, ByteArray>): List<String> =
        entries["xl/sharedStrings.xml"]?.let { raw ->
            val xml = String(raw, Charsets.UTF_8)
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                unescapeXml(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] })
            }.toList()
        } ?: emptyList()

    /** sheet 名 → 文件路径：workbook.xml 的 r:id 经 workbook.xml.rels 解析。 */
    data class SheetRef(val name: String, val file: String, val rId: String)

    fun sheetMap(entries: Map<String, ByteArray>): List<SheetRef> {
        val workbook = entries["xl/workbook.xml"]?.let { String(it, Charsets.UTF_8) } ?: return emptyList()
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val relMap = Regex("<Relationship[^>]*Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"")
            .findAll(rels).associate { it.groupValues[1] to it.groupValues[2] }
        val declared = Regex("<sheet[^>]*name=\"([^\"]+)\"[^>]*r:id=\"([^\"]+)\"")
            .findAll(workbook).map { m ->
                val target = relMap[m.groupValues[2]] ?: "worksheets/sheet${m.groupValues[2].removePrefix("rId")}.xml"
                SheetRef(unescapeXml(m.groupValues[1]), "xl/" + target.removePrefix("/"), m.groupValues[2])
            }.toList()
        if (declared.isNotEmpty()) return declared
        // 兜底：没有 workbook 声明时按文件名排序猜
        return entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.removeSurrounding("xl/worksheets/sheet", ".xml").toIntOrNull() ?: 0 }
            .mapIndexed { i, f -> SheetRef("Sheet${i + 1}", f, "") }
    }

    /** 把一个 sheet xml 解析成行列表（每行 col 数对齐）。 */
    fun parseSheetRows(sheetXml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(sheetXml).forEach { row ->
            val cells = mutableListOf<Pair<Int, String>>()
            var sequentialCol = 0
            Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
                .findAll(row.groupValues[1]).forEach { c ->
                    val attrs = c.groupValues[1]
                    val body = c.groupValues[2]
                    val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                    val col = ref?.columnIndex() ?: sequentialCol
                    sequentialCol = col + 1
                    if (body.isEmpty()) return@forEach
                    val type = Regex("t=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                    val value = when (type) {
                        "s" -> body.extractXmlTag("v")?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> body.extractXmlTag("t") ?: body.extractXmlTag("is")?.extractXmlTag("t") ?: ""
                        else -> body.extractXmlTag("v") ?: ""
                    }
                    cells += col to unescapeXml(value)
                }
            if (cells.isNotEmpty()) {
                val maxCol = cells.maxOf { it.first }
                val rowValues = Array(maxCol + 1) { "" }
                cells.forEach { (col, v) -> if (col <= maxCol) rowValues[col] = v }
                rows += rowValues.toList()
            }
        }
        return rows
    }

    fun extractXlsxText(bytes: ByteArray): String {
        val entries = zipEntries(bytes)
        val shared = sharedStrings(entries)
        val sheets = sheetMap(entries).filter { entries.containsKey(it.file) }
        require(sheets.isNotEmpty()) { "XLSX 中没有工作表" }
        return buildString {
            sheets.forEachIndexed { index, sheet ->
                append(if (index == 0) "Sheet: ${sheet.name}\n" else "\nSheet: ${sheet.name}\n")
                parseSheetRows(String(entries.getValue(sheet.file), Charsets.UTF_8), shared)
                    .forEach { row -> append(row.joinToString("\t").trimEnd()).append('\n') }
            }
        }.trimEnd()
    }

    data class XlsxInspection(val sheets: List<SheetInfo>)
    data class SheetInfo(val name: String, val file: String, val rowCount: Int, val colCount: Int, val previewRows: List<String>)

    fun inspectXlsx(bytes: ByteArray, previewCount: Int = 15): XlsxInspection {
        val entries = zipEntries(bytes)
        val shared = sharedStrings(entries)
        val sheets = sheetMap(entries).filter { entries.containsKey(it.file) }
        require(sheets.isNotEmpty()) { "XLSX 中没有工作表" }
        return XlsxInspection(sheets.map { sheet ->
            val rows = parseSheetRows(String(entries.getValue(sheet.file), Charsets.UTF_8), shared)
            SheetInfo(
                name = sheet.name,
                file = sheet.file,
                rowCount = rows.size,
                colCount = rows.maxOfOrNull { it.size } ?: 0,
                previewRows = rows.take(previewCount).map { it.joinToString("\t").trimEnd() },
            )
        })
    }

    private fun resolveSheet(op: JsonObject, entries: Map<String, ByteArray>): SheetRef {
        val sheets = sheetMap(entries)
        val name = op["sheet"]?.jsonPrimitive?.contentOrNull
        return when {
            name == null -> sheets.firstOrNull() ?: error("工作簿没有工作表")
            name.toIntOrNull() != null -> sheets.getOrNull(name.toInt() - 1)
                ?: error("工作表序号 $name 越界（共 ${sheets.size} 张）")
            else -> sheets.firstOrNull { it.name.equals(name, true) }
                ?: error("找不到工作表「$name」，现有：${sheets.joinToString { it.name }}")
        }
    }

    /** 写单元格：命中就替换（含公式时告警），未命中按列序插入；行不存在则按行序插入新行。 */
    private fun setCell(sheetXml: String, col: Int, row: Int, value: String): Pair<String, String?> {
        val rowNum = row + 1
        val ref = "${columnName(col)}$rowNum"
        val newCell = """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>"""
        var warning: String? = null
        var xml = sheetXml
        val rowRegex = Regex("(<row\\b[^>]*r=\"$rowNum\"[^>]*>)(.*?)(</row>)", RegexOption.DOT_MATCHES_ALL)
        val rowMatch = rowRegex.find(xml)
        if (rowMatch != null) {
            var rowInner = rowMatch.groupValues[2]
            val cellRegex = Regex("<c\\b[^>]*r=\"$ref\"[^>]*?(/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
            val cellMatch = cellRegex.find(rowInner)
            if (cellMatch != null) {
                if (cellMatch.value.contains("<f>") || cellMatch.value.contains("<f ")) {
                    warning = "单元格 $ref 原有公式已被覆盖为静态值"
                }
                rowInner = rowInner.replaceRange(cellMatch.range, newCell)
            } else {
                // 按列序插入
                val insertAt = Regex("<c\\b[^>]*r=\"([A-Z]+)\\d+\"").findAll(rowInner)
                    .firstOrNull { it.groupValues[1].columnIndex() > col }?.range?.first ?: rowInner.length
                rowInner = rowInner.substring(0, insertAt) + newCell + rowInner.substring(insertAt)
            }
            xml = xml.replaceRange(rowMatch.range, rowMatch.groupValues[1] + rowInner + rowMatch.groupValues[3])
        } else {
            // 插入新行（按行号排序进 sheetData）
            val newRow = "<row r=\"$rowNum\">$newCell</row>"
            val sheetData = Regex("(</?sheetData[^>]*>)", RegexOption.DOT_MATCHES_ALL)
            if (xml.contains("<sheetData/>")) {
                xml = xml.replace("<sheetData/>", "<sheetData>$newRow</sheetData>")
            } else {
                val insertPos = Regex("<row\\b[^>]*r=\"(\\d+)\"").findAll(xml)
                    .firstOrNull { it.groupValues[1].toInt() > rowNum }?.range?.first
                    ?: Regex("</sheetData>").find(xml)?.range?.first ?: error("sheet 缺少 sheetData")
                xml = xml.substring(0, insertPos) + newRow + xml.substring(insertPos)
            }
        }
        return xml to warning
    }

    fun editXlsx(bytes: ByteArray, ops: List<JsonObject>): EditOutcome {
        val entries = zipEntries(bytes).toMutableMap()
        val warnings = mutableListOf<String>()
        var applied = 0
        ops.forEachIndexed { opIndex, op ->
            val kind = op["op"]?.jsonPrimitive?.contentOrNull ?: error("第 ${opIndex + 1} 个操作缺少 op")
            when (kind) {
                "set_cell", "append_row", "clear_range" -> {
                    val sheet = resolveSheet(op, entries)
                    var xml = String(entries[sheet.file] ?: error("缺少 ${sheet.file}"), Charsets.UTF_8)
                    when (kind) {
                        "set_cell" -> {
                            val (col, row) = cellRefParts(requiredString(op, "cell"))
                            val (newXml, w) = setCell(xml, col, row, requiredString(op, "value"))
                            xml = newXml
                            w?.let { warnings += "[${sheet.name}] $it" }
                        }
                        "append_row" -> {
                            val values = op["values"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" }
                                ?: error("append_row 缺少 values 数组")
                            val rows = Regex("<row\\b[^>]*r=\"(\\d+)\"").findAll(xml)
                                .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                            val next = (rows.maxOrNull() ?: 0) + 1
                            val cellsXml = values.mapIndexed { ci, v ->
                                """<c r="${columnName(ci)}$next" t="inlineStr"><is><t xml:space="preserve">${escapeXml(v)}</t></is></c>"""
                            }.joinToString("")
                            xml = if (xml.contains("<sheetData/>")) {
                                xml.replace("<sheetData/>", "<sheetData><row r=\"$next\">$cellsXml</row></sheetData>")
                            } else {
                                xml.replace("</sheetData>", "<row r=\"$next\">$cellsXml</row></sheetData>")
                            }
                        }
                        "clear_range" -> {
                            val parts = requiredString(op, "range").split(":")
                            require(parts.size == 2) { "range 需要形如 A1:C5" }
                            val (c1, r1) = cellRefParts(parts[0]); val (c2, r2) = cellRefParts(parts[1])
                            val (loC, hiC) = minOf(c1, c2) to maxOf(c1, c2)
                            val (loR, hiR) = minOf(r1, r2) to maxOf(r1, r2)
                            var cleared = 0
                            xml = Regex("<c\\b[^>]*r=\"([A-Z]+)(\\d+)\"[^>]*?(/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
                                .replace(xml) { m ->
                                    val col = m.groupValues[1].columnIndex(); val row = m.groupValues[2].toInt() - 1
                                    if (col in loC..hiC && row in loR..hiR) { cleared++; "" } else m.value
                                }
                            warnings += "[${sheet.name}] 已清空 ${parts[0]}:${parts[1]} 共 $cleared 个单元格"
                        }
                    }
                    entries[sheet.file] = xml.toByteArray(Charsets.UTF_8)
                    applied++
                }
                "insert_sheet" -> {
                    val name = requiredString(op, "name").take(31)
                    val sheets = sheetMap(entries)
                    require(sheets.none { it.name.equals(name, true) }) { "工作表「$name」已存在" }
                    val nextFileNum = entries.keys.mapNotNull {
                        Regex("xl/worksheets/sheet(\\d+)\\.xml").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }.let { (it.maxOrNull() ?: 0) + 1 }
                    val nextRId = entries["xl/_rels/workbook.xml.rels"]?.let { String(it, Charsets.UTF_8) }?.let { rels ->
                        (Regex("rId(\\d+)").findAll(rels).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0) + 1
                    } ?: 1
                    entries["xl/worksheets/sheet$nextFileNum.xml"] =
                        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>"""
                            .toByteArray(Charsets.UTF_8)
                    entries["xl/workbook.xml"] = String(entries.getValue("xl/workbook.xml"), Charsets.UTF_8)
                        .replace("</sheets>", """<sheet name="${escapeXml(name)}" sheetId="${sheets.size + 1}" r:id="rId$nextRId"/></sheets>""")
                        .toByteArray(Charsets.UTF_8)
                    entries["xl/_rels/workbook.xml.rels"]?.let {
                        entries["xl/_rels/workbook.xml.rels"] = String(it, Charsets.UTF_8)
                            .replace("</Relationships>", """<Relationship Id="rId$nextRId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$nextFileNum.xml"/></Relationships>""")
                            .toByteArray(Charsets.UTF_8)
                    }
                    entries["[Content_Types].xml"]?.let {
                        entries["[Content_Types].xml"] = String(it, Charsets.UTF_8)
                            .replace("</Types>", """<Override PartName="/xl/worksheets/sheet$nextFileNum.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                            .toByteArray(Charsets.UTF_8)
                    }
                    applied++
                }
                else -> error("不支持的 xlsx 操作 $kind")
            }
        }
        val out = java.io.ByteArrayOutputStream()
        zipBytes(out, entries)
        return EditOutcome(out.toByteArray(), applied, warnings)
    }

    // ---------- 生成（供 document_write 复用） ----------

    fun writeDocx(content: String, output: OutputStream) {
        val paragraphs = content.split(Regex("\\r\\n|\\n")).joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
        }
        zipBytes(output, mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs</w:body></w:document>""",
        ).mapValues { it.value.toByteArray(Charsets.UTF_8) })
    }

    fun writeXlsx(content: String, output: OutputStream) {
        val sheets = mutableListOf<Pair<String, List<List<String>>>>()
        var currentName = "Sheet1"
        var currentRows = mutableListOf<List<String>>()
        var headerSeen = false
        content.split(Regex("\\r\\n|\\n")).forEach { line ->
            val header = Regex("^Sheet:\\s*(.+)$").find(line)
            if (header != null) {
                // 首个 Sheet 头之前若无内容，默认 Sheet1 不落盘，避免幽灵空表
                if (currentRows.isNotEmpty() || headerSeen) sheets += currentName to currentRows
                headerSeen = true
                currentName = header.groupValues[1].trim().take(31).ifBlank { "Sheet${sheets.size + 1}" }
                currentRows = mutableListOf()
            } else currentRows += line.split('\t').flatMap { it.split('|') }
        }
        sheets += currentName to currentRows
        val files = mutableMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" }}</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" }}</Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed { i, (name, _) -> """<sheet name="${escapeXml(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("")}</sheets></workbook>""",
        )
        sheets.forEachIndexed { si, (_, rows) ->
            val rowsXml = rows.mapIndexed { ri, cells ->
                val cellsXml = cells.mapIndexed { ci, value ->
                    val ref = "${columnName(ci)}${ri + 1}"
                    """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>"""
                }.joinToString("")
                """<row r="${ri + 1}">$cellsXml</row>"""
            }.joinToString("")
            files["xl/worksheets/sheet${si + 1}.xml"] =
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rowsXml</sheetData></worksheet>"""
        }
        zipBytes(output, files.mapValues { it.value.toByteArray(Charsets.UTF_8) })
    }

    // ---------- 参数工具 ----------

    private fun requiredString(op: JsonObject, name: String): String =
        op[name]?.jsonPrimitive?.contentOrNull ?: error("缺少参数 $name")

    private fun requiredInt(op: JsonObject, name: String): Int =
        op[name]?.jsonPrimitive?.intOrNull ?: error("缺少整数参数 $name")
}
