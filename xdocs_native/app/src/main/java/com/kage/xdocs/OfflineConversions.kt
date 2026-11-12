package com.kage.xdocs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.Typeface
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Offline conversion helpers. PDF/Word conversions are text-first and intentionally
 * do not claim pixel-perfect layout reconstruction.
 */
internal object OfflineConversions {
    fun pdfToDocx(source: File, directory: File): File {
        val text = PDDocument.load(source).use { pdf ->
            require(pdf.numberOfPages > 0) { "The PDF has no pages." }
            PDFTextStripper().apply { setSortByPosition(true); setAddMoreFormatting(true) }.getText(pdf)
        }
        require(text.isNotBlank()) {
            "No selectable text was found. This may be a scanned PDF; offline OCR is not available yet."
        }
        return writeDocx(text, unique(directory, "${source.nameWithoutExtension}.docx"))
    }

    fun docxToPdf(source: File, directory: File): File {
        require(source.isFile && source.length() > 0L) { "Select a readable Word document." }
        require(source.extension.equals("docx", true)) { "Only modern .docx Word documents are supported." }
        val text = readDocxText(source)
        require(text.isNotBlank()) { "No readable paragraphs were found in this Word document." }
        return writeTextPdf(text, unique(directory, "${source.nameWithoutExtension}.pdf"))
    }
    fun pdfToText(source: File, directory: File): File {
        val text = PDDocument.load(source).use { pdf ->
            PDFTextStripper().apply { setSortByPosition(true) }.getText(pdf)
        }
        require(text.isNotBlank()) { "No selectable text found in this PDF." }
        return unique(directory, "${source.nameWithoutExtension}.txt").also { it.writeText(text, Charsets.UTF_8) }
    }

    fun docxToText(source: File, directory: File): File {
        val text = readDocxText(source)
        return unique(directory, "${source.nameWithoutExtension}.txt").also { it.writeText(text, Charsets.UTF_8) }
    }

    fun textToDocx(source: File, directory: File): File =
        writeDocx(source.readText(Charsets.UTF_8), unique(directory, "${source.nameWithoutExtension}.docx"))

    fun textToPdf(source: File, directory: File): File {
        val text = source.readText(Charsets.UTF_8)
        require(text.isNotBlank()) { "The text file is empty." }
        return writeTextPdf(text, unique(directory, "${source.nameWithoutExtension}.pdf"))
    }

    /**
     * Use Android's native PDF writer for text documents. This avoids PDFBox's
     * limited Standard-14 font encoding, which can fail on Unicode text and
     * on some Android/PDFBox combinations.
     */
    private fun writeTextPdf(text: String, output: File): File {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val maxWidth = pageWidth - margin * 2f
        val leading = 16f
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin + paint.textSize

        fun nextPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = margin + paint.textSize
        }

        try {
            text.replace("\\r\\n", "\\n").replace('\\r', '\\n').split('\\n').forEach { paragraph ->
                if (paragraph.isBlank()) {
                    y += leading
                    if (y > pageHeight - margin) nextPage()
                } else {
                    val words = paragraph.replace('\\t', ' ').trim().split(Regex("\\s+"))
                    var line = StringBuilder()
                    for (word in words) {
                        val candidate = if (line.isEmpty()) word else "${line} ${word}"
                        if (paint.measureText(candidate) <= maxWidth) {
                            line = StringBuilder(candidate)
                        } else {
                            if (line.isNotEmpty()) {
                                if (y > pageHeight - margin) nextPage()
                                canvas.drawText(line.toString(), margin, y, paint)
                                y += leading
                                line = StringBuilder()
                            }
                            var remainder = word
                            while (paint.measureText(remainder) > maxWidth && remainder.length > 1) {
                                var cut = 1
                                while (cut < remainder.length && paint.measureText(remainder.substring(0, cut + 1)) <= maxWidth) cut++
                                if (y > pageHeight - margin) nextPage()
                                canvas.drawText(remainder.substring(0, cut), margin, y, paint)
                                y += leading
                                remainder = remainder.substring(cut)
                            }
                            line.append(remainder)
                        }
                    }
                    if (line.isNotEmpty()) {
                        if (y > pageHeight - margin) nextPage()
                        canvas.drawText(line.toString(), margin, y, paint)
                        y += leading
                    }
                    y += 5f
                }
            }
            document.finishPage(page)
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        require(output.isFile && output.length() > 100L) { "The PDF could not be generated." }
        return output
    }

    fun convertImage(source: File, directory: File, format: String): File {
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("This image could not be opened.")
        val ext = format.lowercase()
        require(ext in listOf("png", "jpg", "webp")) { "Unsupported image format." }
        val output = unique(directory, "${source.nameWithoutExtension}.$ext")
        try {
            FileOutputStream(output).use { stream ->
                val type = when (ext) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                val quality = if (ext == "png") 100 else 92
                check(bitmap.compress(type, quality, stream)) { "Image conversion failed." }
            }
        } finally { bitmap.recycle() }
        return output
    }

    private fun readDocxText(source: File): String {
        require(source.isFile && source.length() > 0L) { "The Word document is empty or missing." }
        ZipFile(source).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("This is not a valid DOCX document.")
            val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))
            val paragraphs = mutableListOf<String>()
            var paragraph = StringBuilder()
            var inText = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                        "p" -> paragraph = StringBuilder()
                        "t" -> inText = true
                        "tab" -> paragraph.append('\\t')
                        "br", "cr" -> paragraph.append('\\n')
                    }
                    XmlPullParser.TEXT -> if (inText) paragraph.append(parser.text ?: "")
                    XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                        "t" -> inText = false
                        "p" -> paragraphs.add(paragraph.toString())
                    }
                }
                event = parser.next()
            }
            return paragraphs.joinToString("\\n")
        }
    }

    private fun writeDocx(text: String, output: File): File {
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val body = buildString {
            paragraphs.forEach { line ->
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(xmlEscape(line))
                append("</w:t></w:r></w:p>")
            }
            append("<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>")
        }
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>""")
        }
        return output
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun wrap(value: String, max: Int): List<String> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var remaining = value
        while (remaining.length > max) {
            var cut = remaining.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            result += remaining.substring(0, cut)
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result
    }

    private fun String.toPdfWinAnsi(): String = map { if (it.code in 32..255) it else '?' }.joinToString("")

    private fun unique(directory: File, name: String): File {
        directory.mkdirs()
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isBlank() || it == name) "" else ".$it" }
        var output = File(directory, name)
        var index = 2
        while (output.exists()) output = File(directory, "${base}_${index++}$extension")
        return output
    }
}
