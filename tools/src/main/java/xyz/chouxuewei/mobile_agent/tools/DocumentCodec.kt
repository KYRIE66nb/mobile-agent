package xyz.chouxuewei.mobile_agent.tools

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 纯本地文档编解码：docx/xlsx 走 OOXML zip+XML 的最小读写（无外部依赖），
 * PDF 读取走 pdfbox-android、生成走系统 PdfDocument。
 * 全部输出为纯文本，供 file_read / document_write 复用。
 */
object DocumentCodec {

    enum class Format { DOCX, XLSX, PDF }

    fun detectFormat(name: String?, mimeType: String?): Format? {
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return when {
            ext == "docx" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Format.DOCX
            ext == "xlsx" || mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Format.XLSX
            ext == "pdf" || mimeType == "application/pdf" -> Format.PDF
            else -> null
        }
    }

    fun extractText(format: Format, bytes: ByteArray, appContext: android.content.Context): String = when (format) {
        Format.DOCX -> extractDocx(bytes)
        Format.XLSX -> extractXlsx(bytes)
        Format.PDF -> extractPdf(bytes, appContext)
    }

    fun write(format: Format, content: String, output: OutputStream) = when (format) {
        Format.DOCX -> writeDocx(content, output)
        Format.XLSX -> writeXlsx(content, output)
        Format.PDF -> writePdf(content, output)
    }

    // ---------- 读取 ----------

    /** OOXML 共用的 zip 条目读取。 */
    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.size <= 32L * 1024 * 1024) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("&#(x?[0-9A-Fa-f]+);")) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x", true)) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: ""
        }

    private fun extractDocx(bytes: ByteArray): String {
        val doc = zipEntries(bytes)["word/document.xml"]
            ?: error("DOCX 缺少 word/document.xml")
        var xml = String(doc, Charsets.UTF_8)
        // 段落/制表符/换行转成文本换行，再剥掉其余标签。
        xml = xml.replace(Regex("<w:tab\\s*/>"), "\t")
            .replace(Regex("<w:br\\s*/>|<w:cr\\s*/>"), "\n")
            .replace(Regex("</w:p>"), "\n")
        return unescapeXml(xml.replace(Regex("<[^>]+>"), ""))
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun extractXlsx(bytes: ByteArray): String {
        val entries = zipEntries(bytes)
        val shared = entries["xl/sharedStrings.xml"]?.let { raw ->
            val xml = String(raw, Charsets.UTF_8)
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                unescapeXml(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(si.groupValues[1]).joinToString("") { it.groupValues[1] })
            }.toList()
        } ?: emptyList()
        // sheet 名 → 文件：workbook.xml 的 r:id 顺序与 sheet*.xml 排序兜底
        val sheetNames = entries["xl/workbook.xml"]?.let { raw ->
            Regex("<sheet[^>]*name=\"([^\"]+)\"").findAll(String(raw, Charsets.UTF_8))
                .map { unescapeXml(it.groupValues[1]) }.toList()
        } ?: emptyList()
        val sheetFiles = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.removeSurrounding("xl/worksheets/sheet", ".xml").toIntOrNull() ?: 0 }
        if (sheetFiles.isEmpty()) error("XLSX 中没有工作表")
        return buildString {
            sheetFiles.forEachIndexed { index, file ->
                val name = sheetNames.getOrNull(index) ?: file
                append(if (index == 0) "Sheet: $name\n" else "\nSheet: $name\n")
                val xml = String(entries.getValue(file), Charsets.UTF_8)
                Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { row ->
                    val cells = mutableListOf<Pair<Int, String>>()
                    Regex("<c\\s+[^>]*?r=\"([A-Z]+)\\d+\"[^>]*?(?:t=\"([^\"]+)\")?[^>]*?>(.*?)</c>|<c\\s+[^>]*?(?:t=\"([^\"]+)\")?[^>]*?r=\"([A-Z]+)\\d+\"[^>]*?>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(row.groupValues[1]).forEach { c ->
                            val col = (c.groupValues[1].ifEmpty { c.groupValues[5] }).columnIndex()
                            val type = c.groupValues[2].ifEmpty { c.groupValues[4] }
                            val body = c.groupValues[3].ifEmpty { c.groupValues[6] }
                            val value = when (type) {
                                "s" -> body.extractXmlTag("v")?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "inlineStr" -> body.extractXmlTag("t") ?: ""
                                else -> body.extractXmlTag("v") ?: ""
                            }
                            cells += col to unescapeXml(value)
                        }
                    if (cells.isEmpty()) return@forEach
                    val maxCol = cells.maxOf { it.first }
                    val line = CharArray(maxCol + 1) { '\u0000' }
                    val rowValues = Array(maxCol + 1) { "" }
                    cells.forEach { (col, v) -> if (col <= maxCol) rowValues[col] = v }
                    append(rowValues.joinToString("\t").trimEnd()).append('\n')
                }
            }
        }.trimEnd()
    }

    private fun String.extractXmlTag(tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(this)?.groupValues?.get(1)

    private fun String.columnIndex(): Int {
        var value = 0
        for (ch in this) value = value * 26 + (ch - 'A' + 1)
        return value - 1
    }

    private fun extractPdf(bytes: ByteArray, appContext: android.content.Context): String {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(appContext)
        PDDocument.load(bytes).use { doc ->
            val pages = doc.numberOfPages
            val text = PDFTextStripper().getText(doc).trim()
            return "Pages: $pages\n\n$text"
        }
    }

    // ---------- 生成 ----------

    private fun zipOutput(output: OutputStream, files: Map<String, String>) {
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun writeDocx(content: String, output: OutputStream) {
        val paragraphs = content.split(Regex("\\r\\n|\\n")).joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
        }
        zipOutput(output, mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs</w:body></w:document>""",
        ))
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        var name = ""
        while (value > 0) { value--; name = ('A' + value % 26) + name; value /= 26 }
        return name
    }

    private fun writeXlsx(content: String, output: OutputStream) {
        // content：每个 "Sheet: 名" 行开启新表；表内每行一条记录，制表符或 | 分列。
        val sheets = mutableListOf<Pair<String, List<List<String>>>>()
        var currentName = "Sheet1"
        var currentRows = mutableListOf<List<String>>()
        content.split(Regex("\\r\\n|\\n")).forEach { line ->
            val header = Regex("^Sheet:\\s*(.+)$").find(line)
            if (header != null) {
                if (currentRows.isNotEmpty() || sheets.isEmpty()) sheets += currentName to currentRows
                currentName = header.groupValues[1].trim().take(31).ifBlank { "Sheet${sheets.size + 1}" }
                currentRows = mutableListOf()
            } else currentRows += line.split('\t').flatMap { it.split('|') }
        }
        sheets += currentName to currentRows
        val files = mutableMapOf<String, String>(
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
        zipOutput(output, files)
    }

    private fun writePdf(content: String, output: OutputStream) {
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 11f }
        val pageWidth = 595; val pageHeight = 842; val margin = 48f
        val lineHeight = 16f
        val maxChars = (pageWidth - margin * 2).let { w -> (w / (paint.textSize * 0.55f)).toInt().coerceAtLeast(20) }
        // 手工换行：每行过长时按字符数截断折行。
        val lines = content.split(Regex("\\r\\n|\\n")).flatMap { line ->
            if (line.length <= maxChars) listOf(line)
            else line.chunked(maxChars)
        }
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create())
            canvas = page!!.canvas
            y = margin + lineHeight
        }
        newPage()
        for (line in lines) {
            if (y > pageHeight - margin) newPage()
            canvas!!.drawText(line, margin, y, paint)
            y += lineHeight
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(output)
        doc.close()
    }

    fun mimeType(format: Format): String = when (format) {
        Format.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        Format.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        Format.PDF -> "application/pdf"
    }
}
