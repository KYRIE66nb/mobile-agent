package xyz.chouxuewei.mobile_agent.tools

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OoxmlEditorTest {

    private fun ops(json: String): List<JsonObject> =
        Json.parseToJsonElement("""{"ops":$json}""").jsonObject["ops"]!!
            .let { it as kotlinx.serialization.json.JsonArray }
            .map { it.jsonObject }

    private fun docx(vararg paragraphs: String): ByteArray {
        val body = paragraphs.joinToString("") { p ->
            "<w:p><w:r><w:t xml:space=\"preserve\">$p</w:t></w:r></w:p>"
        }
        val out = ByteArrayOutputStream()
        OoxmlEditor.writeDocx(body.split("\n").joinToString("\n"), out) // 占位不用
        // 直接构造最小 docx，保证可控 run 结构
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
        val files = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to xml,
        ).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        val output = ByteArrayOutputStream()
        OoxmlEditor.zipBytes(output, files)
        return output.toByteArray()
    }

    private fun docxWithRuns(vararg runs: String): ByteArray {
        val body = "<w:p>" + runs.joinToString("") { "<w:r><w:t xml:space=\"preserve\">$it</w:t></w:r>" } + "</w:p>"
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
        val out = ByteArrayOutputStream()
        OoxmlEditor.zipBytes(out, mapOf("word/document.xml" to xml.toByteArray(Charsets.UTF_8)))
        return out.toByteArray()
    }

    @Test
    fun `docx write and read back`() {
        val out = ByteArrayOutputStream()
        OoxmlEditor.writeDocx("第一行\n第二行\n", out)
        val text = OoxmlEditor.extractDocxText(out.toByteArray())
        assertTrue(text.contains("第一行"))
        assertTrue(text.contains("第二行"))
    }

    @Test
    fun `docx set_paragraph replaces text`() {
        val edited = OoxmlEditor.editDocx(docx("原始内容", "保持不动"), ops("""[{"op":"set_paragraph","index":0,"text":"新内容"}]"""))
        assertEquals(1, edited.applied)
        val text = OoxmlEditor.extractDocxText(edited.bytes)
        assertTrue(text.contains("新内容"))
        assertFalse(text.contains("原始内容"))
        assertTrue(text.contains("保持不动"))
    }

    @Test
    fun `docx replace works across runs`() {
        // "换我" 被拆到两个 run 里，段落级 replace 仍要命中
        val edited = OoxmlEditor.editDocx(docxWithRuns("请把这段", "换我", "掉"), ops("""[{"op":"replace","find":"换我","replace":"改"}]"""))
        assertEquals(1, edited.applied)
        val text = OoxmlEditor.extractDocxText(edited.bytes)
        assertTrue(text.contains("请把这段改掉"))
    }

    @Test
    fun `docx replace missing text warns and does not apply`() {
        val outcome = OoxmlEditor.editDocx(docx("abc"), ops("""[{"op":"replace","find":"zzz","replace":"x"}]"""))
        assertEquals(0, outcome.applied)
        assertTrue(outcome.warnings.isNotEmpty())
    }

    @Test
    fun `docx insert and append`() {
        val edited = OoxmlEditor.editDocx(docx("A", "B"), ops("""
            [{"op":"insert_paragraph","after_index":0,"text":"插入"},
             {"op":"append","text":"尾部"}]
        """))
        assertEquals(2, edited.applied)
        val text = OoxmlEditor.extractDocxText(edited.bytes)
        val order = listOf("A", "插入", "B", "尾部").map { text.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `docx inspect lists non-empty paragraphs`() {
        val info = OoxmlEditor.inspectDocx(docx("第一段", "第二段"))
        assertEquals(2, info.totalParagraphs)
        assertEquals(2, info.paragraphs.size)
        assertTrue(info.paragraphs[0].preview.contains("第一段"))
    }

    private fun xlsxShared(): ByteArray {
        // 带 sharedStrings 的 xlsx：A1=Hello(shared), B1=5
        val out = ByteArrayOutputStream()
        OoxmlEditor.zipBytes(out, mapOf(
            "xl/sharedStrings.xml" to """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Hello</t></si></sst>""".toByteArray(),
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets></workbook>""".toByteArray(),
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""".toByteArray(),
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>5</v></c></row></sheetData></worksheet>""".toByteArray(),
        ))
        return out.toByteArray()
    }

    @Test
    fun `xlsx read shared strings`() {
        val text = OoxmlEditor.extractXlsxText(xlsxShared())
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("5"))
    }

    @Test
    fun `xlsx set_cell writes inline string and preserves sharedStrings`() {
        val before = OoxmlEditor.zipEntries(xlsxShared())["xl/sharedStrings.xml"]!!
        val edited = OoxmlEditor.editXlsx(xlsxShared(), ops("""[{"op":"set_cell","cell":"C2","value":"新值"}]"""))
        assertEquals(1, edited.applied)
        val entries = OoxmlEditor.zipEntries(edited.bytes)
        // sharedStrings 原样不动
        assertTrue(entries["xl/sharedStrings.xml"]!!.contentEquals(before))
        val text = OoxmlEditor.extractXlsxText(edited.bytes)
        assertTrue(text.contains("新值"))
        assertTrue(text.contains("Hello"))
    }

    @Test
    fun `xlsx set_cell overwrites existing`() {
        val edited = OoxmlEditor.editXlsx(xlsxShared(), ops("""[{"op":"set_cell","cell":"A1","value":"替换"}]"""))
        val text = OoxmlEditor.extractXlsxText(edited.bytes)
        assertTrue(text.contains("替换"))
        assertFalse(text.contains("Hello"))
    }

    @Test
    fun `xlsx append_row`() {
        val edited = OoxmlEditor.editXlsx(xlsxShared(), ops("""[{"op":"append_row","values":["r2c1","r2c2"]}]"""))
        val text = OoxmlEditor.extractXlsxText(edited.bytes)
        assertTrue(text.contains("r2c1"))
        assertTrue(text.indexOf("r2c1") > text.indexOf("Hello"))
    }

    @Test
    fun `xlsx insert_sheet creates new sheet`() {
        val edited = OoxmlEditor.editXlsx(xlsxShared(), ops("""[{"op":"insert_sheet","name":"新表"}]"""))
        val info = OoxmlEditor.inspectXlsx(edited.bytes)
        assertEquals(2, info.sheets.size)
        assertTrue(info.sheets.any { it.name == "新表" })
    }

    @Test
    fun `xlsx clear_range removes cells`() {
        val edited = OoxmlEditor.editXlsx(xlsxShared(), ops("""[{"op":"clear_range","range":"A1:B1"}]"""))
        val text = OoxmlEditor.extractXlsxText(edited.bytes)
        assertFalse(text.contains("Hello"))
    }

    @Test
    fun `xlsx inspect reports sheet stats`() {
        val info = OoxmlEditor.inspectXlsx(xlsxShared())
        assertEquals(1, info.sheets.size)
        assertEquals("数据", info.sheets[0].name)
        assertEquals(1, info.sheets[0].rowCount)
        assertTrue(info.sheets[0].previewRows.isNotEmpty())
    }

    @Test
    fun `xlsx write and read back multiple sheets`() {
        val out = ByteArrayOutputStream()
        OoxmlEditor.writeXlsx("Sheet: 甲\n1\t2\nSheet: 乙\nx\ty", out)
        val info = OoxmlEditor.inspectXlsx(out.toByteArray())
        assertEquals(2, info.sheets.size)
        assertTrue(info.sheets[0].name == "甲")
    }
}
