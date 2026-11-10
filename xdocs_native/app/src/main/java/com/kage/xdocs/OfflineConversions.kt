package com.kage.xdocs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        val output = unique(directory, "${source.nameWithoutExtension}.pdf")
        PDDocument().use { pdf ->
            val pageSize = PDRectangle.A4
            val margin = 48f
            val font = PDType1Font.HELVETICA
            val fontSize = 10.5f
            val leading = 14f
            var page = PDPage(pageSize)
            pdf.addPage(page)
            var stream = PDPageContentStream(pdf, page)
            var y = pageSize.height - margin
            try {
                text.replace("\\r\\n", "\\n").replace('\\r', '\\n').split('\\n').forEach { paragraph ->
                    val safe = paragraph.replace('\\t', ' ').map { ch -> if (ch.code in 32..126) ch else '?' }.joinToString("")
                    if (safe.isEmpty()) {
                        y -= leading
                    } else {
                        var remaining = safe
                        while (remaining.isNotEmpty()) {
                            var count = 0
                            while (count < remaining.length) {
                                val candidate = remaining.substring(0, count + 1)
                                val width = font.getStringWidth(candidate) * fontSize / 1000f
                                if (width > pageSize.width - margin * 2f) break
                                count++
                            }
                            if (count == 0) count = 1
                            var cut = count
                            if (count < remaining.length) {
                                val space = remaining.lastIndexOf(' ', count - 1)
                                if (space > 0) cut = space
                            }
                            val line = remaining.substring(0, cut).trimEnd()
                            remaining = remaining.substring(cut).trimStart()
                            if (y < margin) {
                                stream.close()
                                page = PDPage(pageSize)
                                pdf.addPage(page)
                                stream = PDPageContentStream(pdf, page)
                                y = pageSize.height - margin
                            }
                            if (line.isNotEmpty()) {
                                stream.beginText()
                                stream.setFont(font, fontSize)
                                stream.newLineAtOffset(margin, y)
                                stream.showText(line)
                                stream.endText()
                            }
                            y -= leading
                        }
                    }
                    y -= 3f
                }
            } finally {
                stream.close()
            }
            pdf.save(output)
        }
        require(output.isFile && output.length() > 100L) { "The PDF could not be generated." }
        return output
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
        val output = unique(directory, "${source.nameWithoutExtension}.pdf")
        PDDocument().use { pdf ->
            val size = PDRectangle.LETTER
            var page = PDPage(size)
            pdf.addPage(page)
            var stream = PDPageContentStream(pdf, page)
            var y = size.height - 54f
            try {
                text.split('\n').forEach { raw ->
                    val lines = wrap(raw.replace('\t', ' '), 92)
                    if (lines.isEmpty()) y -= 15f
                    for (line in lines) {
                        if (y < 54f) {
                            stream.close()
                            page = PDPage(size); pdf.addPage(page)
                            stream = PDPageContentStream(pdf, page)
                            y = size.height - 54f
                        }
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 11f)
                        stream.newLineAtOffset(54f, y)
                        stream.showText(line.toPdfWinAnsi())
                        stream.endText()
                        y -= 15f
                    }
                }
            } finally { stream.close() }
            pdf.save(output)
        }
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
        ZipFile(source).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("This is not a valid DOCX document.")
            val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setExpandEntityReferences(false)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val paragraphs = document.getElementsByTagNameNS("*", "p")
            return (0 until paragraphs.length).map { index ->
                val paragraph = paragraphs.item(index)
                val texts = paragraph.childNodes
                val builder = StringBuilder()
                fun walk(node: org.w3c.dom.Node) {
                    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE &&
                        node.localName == "t") builder.append(node.textContent)
                    else if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE &&
                        node.localName == "tab") builder.append('\t')
                    else if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE &&
                        node.localName == "br") builder.append('\n')
                    else {
                        val children = node.childNodes
                        for (i in 0 until children.length) walk(children.item(i))
                    }
                }
                for (i in 0 until texts.length) walk(texts.item(i))
                builder.toString()
            }.joinToString("\n")
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
