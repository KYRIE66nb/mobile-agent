package xyz.chouxuewei.mobile_agent.tools

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.OutputStream

/**
 * 文档编解码入口：docx/xlsx 全部委托给纯 Kotlin 的 [OoxmlEditor]（可单测），
 * PDF 读取走 pdfbox-android、生成走系统 PdfDocument。
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
        Format.DOCX -> OoxmlEditor.extractDocxText(bytes)
        Format.XLSX -> OoxmlEditor.extractXlsxText(bytes)
        Format.PDF -> extractPdf(bytes, appContext)
    }

    fun write(format: Format, content: String, output: OutputStream) = when (format) {
        Format.DOCX -> OoxmlEditor.writeDocx(content, output)
        Format.XLSX -> OoxmlEditor.writeXlsx(content, output)
        Format.PDF -> writePdf(content, output)
    }

    fun mimeType(format: Format): String = when (format) {
        Format.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        Format.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        Format.PDF -> "application/pdf"
    }

    private fun extractPdf(bytes: ByteArray, appContext: android.content.Context): String {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(appContext)
        PDDocument.load(bytes).use { doc ->
            val pages = doc.numberOfPages
            val text = PDFTextStripper().getText(doc).trim()
            return "Pages: $pages\n\n$text"
        }
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
}
