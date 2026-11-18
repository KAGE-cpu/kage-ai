import io
import os
import re
import sys
import tempfile
import threading
import time
import webbrowser
from html.parser import HTMLParser
from pathlib import Path
from xml.sax.saxutils import escape

import fitz
from flask import Flask, jsonify, make_response, render_template, request, send_file
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Inches, Pt, RGBColor
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib import colors

APP_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
app = Flask(__name__, template_folder=str(APP_DIR / "templates"))
app.config["MAX_CONTENT_LENGTH"] = 80 * 1024 * 1024
TEMP_DIR = Path(tempfile.gettempdir()) / "xdocs_desktop"
TEMP_DIR.mkdir(parents=True, exist_ok=True)


def clean_name(value, fallback="document"):
    value = re.sub(r"[^A-Za-z0-9 _.-]", "", value or "").strip(" .")
    return (value[:70] or fallback)


def uploaded_file(field="file"):
    f = request.files.get(field)
    if not f or not f.filename:
        raise ValueError("Choose a file first.")
    suffix = Path(f.filename).suffix.lower()
    path = TEMP_DIR / (str(time.time_ns()) + suffix)
    f.save(path)
    return path, Path(f.filename).name


@app.get("/")
def home():
    return render_template("index.html")


@app.get("/api/health")
def health():
    return jsonify({"ok": True, "version": "1.0.0", "offline": True})


@app.post("/api/docx/import")
def import_docx():
    path, original = uploaded_file()
    if path.suffix != ".docx":
        path.unlink(missing_ok=True)
        return jsonify(error="Please choose a modern .docx Word document."), 400
    try:
        doc = Document(str(path))
        chunks = []
        for item in doc.element.body:
            tag = item.tag.split("}")[-1]
            if tag == "p":
                from docx.text.paragraph import Paragraph
                p = Paragraph(item, doc)
                text = []
                for run in p.runs:
                    value = escape(run.text).replace("\n", "<br>")
                    if run.bold: value = "<strong>" + value + "</strong>"
                    if run.italic: value = "<em>" + value + "</em>"
                    if run.underline: value = "<u>" + value + "</u>"
                    text.append(value)
                style = p.style.name.lower() if p.style else ""
                tag_name = "h1" if "title" in style else ("h2" if "heading 1" in style else ("h3" if "heading 2" in style else "p"))
                chunks.append("<" + tag_name + ">" + "".join(text) + "</" + tag_name + ">")
            elif tag == "tbl":
                from docx.table import Table as DocxTable
                table = DocxTable(item, doc)
                rows = []
                for row in table.rows:
                    cells = "".join("<td>" + escape(cell.text).replace("\n", "<br>") + "</td>" for cell in row.cells)
                    rows.append("<tr>" + cells + "</tr>")
                chunks.append("<table>" + "".join(rows) + "</table>")
        path.unlink(missing_ok=True)
        return jsonify(html="".join(chunks) or "<p></p>", name=Path(original).stem)
    except Exception as exc:
        path.unlink(missing_ok=True)
        return jsonify(error="This DOCX could not be opened: " + str(exc)), 400


class RichDocxParser(HTMLParser):
    def __init__(self, document):
        super().__init__(convert_charrefs=True)
        self.doc = document
        self.paragraph = None
        self.stack = []
        self.in_table = False
        self.table_rows = []
        self.table_row = None
        self.cell_text = None
        self.list_kind = None

    def new_paragraph(self, style=None):
        self.paragraph = self.doc.add_paragraph(style=style) if style else self.doc.add_paragraph()
        return self.paragraph

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("p", "div", "h1", "h2", "h3", "li"):
            style = {"h1":"Title", "h2":"Heading 1", "h3":"Heading 2"}.get(tag)
            self.new_paragraph(style)
            if tag == "li":
                self.paragraph.style = "List Number" if self.list_kind == "ol" else "List Bullet"
        elif tag in ("ul", "ol"):
            self.list_kind = tag
        elif tag == "br":
            if self.paragraph is None: self.new_paragraph()
            self.paragraph.add_run().add_break()
        elif tag == "table":
            self.in_table = True
            self.table_rows = []
        elif tag == "tr" and self.in_table:
            self.table_row = []
        elif tag in ("td", "th") and self.in_table:
            self.cell_text = ""
        elif tag in ("strong", "b", "em", "i", "u", "sup", "sub", "span", "font"):
            self.stack.append((tag, attrs))
        elif tag == "hr":
            self.new_paragraph()
            self.paragraph.add_run("────────────────────────────────")

    def handle_endtag(self, tag):
        if tag in ("strong", "b", "em", "i", "u", "sup", "sub", "span", "font") and self.stack:
            self.stack.pop()
        elif tag in ("ul", "ol"):
            self.list_kind = None
        elif tag in ("td", "th") and self.in_table:
            self.table_row.append(self.cell_text or "")
            self.cell_text = None
        elif tag == "tr" and self.in_table and self.table_row is not None:
            self.table_rows.append(self.table_row)
            self.table_row = None
        elif tag == "table" and self.in_table:
            if self.table_rows:
                width = max(len(r) for r in self.table_rows)
                table = self.doc.add_table(rows=len(self.table_rows), cols=width)
                table.style = "Table Grid"
                for ri, row in enumerate(self.table_rows):
                    for ci in range(width):
                        table.cell(ri, ci).text = row[ci] if ci < len(row) else ""
            self.in_table = False
        elif tag in ("p", "div", "h1", "h2", "h3", "li"):
            self.paragraph = None

    def handle_data(self, data):
        if self.in_table and self.cell_text is not None:
            self.cell_text += data
            return
        if not data:
            return
        if self.paragraph is None:
            if not data.strip(): return
            self.new_paragraph()
        run = self.paragraph.add_run(data)
        for tag, attrs in self.stack:
            if tag in ("strong", "b"): run.bold = True
            if tag in ("em", "i"): run.italic = True
            if tag == "u": run.underline = True
            if tag == "sup": run.font.superscript = True
            if tag == "sub": run.font.subscript = True
            style = attrs.get("style", "")
            family = re.search(r"font-family\s*:\s*([^;]+)", style, re.I)
            size = re.search(r"font-size\s*:\s*(\d+(?:\.\d+)?)pt", style, re.I)
            color = re.search(r"color\s*:\s*#([0-9a-f]{6})", style, re.I)
            if family: run.font.name = family.group(1).strip(" '\"")
            if size:
                try: run.font.size = Pt(float(size.group(1)))
                except ValueError: pass
            if color:
                try: run.font.color.rgb = RGBColor.from_string(color.group(1).upper())
                except ValueError: pass


@app.post("/api/docx/export")
def export_docx():
    data = request.get_json(force=True)
    name = clean_name(data.get("name"), "document")
    html = data.get("html", "")
    if not html.strip():
        return jsonify(error="The document is empty."), 400
    doc = Document()
    section = doc.sections[0]
    section.top_margin = Inches(0.72)
    section.bottom_margin = Inches(0.72)
    section.left_margin = Inches(0.78)
    section.right_margin = Inches(0.78)
    parser = RichDocxParser(doc)
    parser.feed(html)
    if not doc.paragraphs and not doc.tables:
        doc.add_paragraph(" ")
    out = TEMP_DIR / (name + ".docx")
    doc.save(str(out))
    return send_file(out, as_attachment=True, download_name=name + ".docx",
                     mimetype="application/vnd.openxmlformats-officedocument.wordprocessingml.document")


def pdf_to_layout_docx(pdf_path, output_path):
    pdf = fitz.open(str(pdf_path))
    if pdf.is_encrypted:
        raise ValueError("This PDF is password-protected. Unlock it first.")
    if len(pdf) == 0:
        raise ValueError("The PDF has no pages.")
    doc = Document()
    first = doc.sections[0]
    page0 = pdf[0]
    first.page_width = Inches(page0.rect.width / 72)
    first.page_height = Inches(page0.rect.height / 72)
    first.top_margin = Inches(0.28)
    first.bottom_margin = Inches(0.28)
    first.left_margin = Inches(0.28)
    first.right_margin = Inches(0.28)
    normal = doc.styles["Normal"]
    normal.font.name = "Arial"
    normal.font.size = Pt(10)
    previous_bottom = 20.0
    for page_index, page in enumerate(pdf):
        if page_index:
            doc.add_page_break()
            previous_bottom = 20.0
        blocks = page.get_text("dict").get("blocks", [])
        text_blocks = [b for b in blocks if b.get("type") == 0 and b.get("lines")]
        text_blocks.sort(key=lambda b: (round(b["bbox"][1], 1), round(b["bbox"][0], 1)))
        for block in text_blocks:
            x0, y0, x1, y1 = block["bbox"]
            lines = block.get("lines", [])
            paragraph = doc.add_paragraph()
            paragraph.paragraph_format.left_indent = Pt(max(0, x0 - 20))
            gap = max(0, y0 - previous_bottom)
            paragraph.paragraph_format.space_before = Pt(max(0, gap))
            paragraph.paragraph_format.space_after = Pt(max(1, min(10, (y1-y0)*0.08)))
            paragraph.paragraph_format.line_spacing = 1.0
            for line_index, line in enumerate(lines):
                spans = line.get("spans", [])
                for span in spans:
                    text = span.get("text", "")
                    if not text:
                        continue
                    run = paragraph.add_run(text)
                    font_name = span.get("font", "Arial")
                    run.font.name = "Arial" if "symbol" in font_name.lower() else font_name.split("+")[-1][:40]
                    run.font.size = Pt(max(5, min(48, float(span.get("size", 10)))))
                    flags = int(span.get("flags", 0))
                    run.bold = bool(flags & 16)
                    run.italic = bool(flags & 2)
                    color = int(span.get("color", 0))
                    run.font.color.rgb = RGBColor((color >> 16) & 255, (color >> 8) & 255, color & 255)
                if line_index < len(lines)-1:
                    paragraph.add_run().add_break()
            previous_bottom = max(previous_bottom, y1)
    pdf.close()
    doc.save(str(output_path))
    return output_path


@app.post("/api/pdf/to-word")
def pdf_to_word():
    path, original = uploaded_file()
    out = TEMP_DIR / (clean_name(Path(original).stem) + "_converted.docx")
    try:
        pdf_to_layout_docx(path, out)
        path.unlink(missing_ok=True)
        return send_file(out, as_attachment=True, download_name=out.name,
                         mimetype="application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    except Exception as exc:
        path.unlink(missing_ok=True)
        out.unlink(missing_ok=True)
        return jsonify(error="PDF-to-Word could not complete: " + str(exc)), 400


@app.post("/api/pdf/rotate")
def rotate_pdf():
    path, original = uploaded_file()
    angle = int(request.form.get("angle", "90")) % 360
    try:
        pdf = fitz.open(str(path))
        for page in pdf:
            page.set_rotation((page.rotation + angle) % 360)
        out = TEMP_DIR / (clean_name(Path(original).stem) + "_rotated.pdf")
        pdf.save(str(out), garbage=4, deflate=True)
        pdf.close()
        path.unlink(missing_ok=True)
        return send_file(out, as_attachment=True, download_name=out.name, mimetype="application/pdf")
    except Exception as exc:
        path.unlink(missing_ok=True)
        return jsonify(error=str(exc)), 400


@app.post("/api/pdf/delete-pages")
def delete_pdf_pages():
    path, original = uploaded_file()
    raw = request.form.get("pages", "")
    try:
        indexes = sorted({int(x.strip())-1 for x in raw.split(",") if x.strip()}, reverse=True)
        pdf = fitz.open(str(path))
        if not indexes or any(i < 0 or i >= len(pdf) for i in indexes) or len(indexes) >= len(pdf):
            raise ValueError("Enter valid page numbers. At least one page must remain.")
        for index in indexes:
            pdf.delete_page(index)
        out = TEMP_DIR / (clean_name(Path(original).stem) + "_pages-edited.pdf")
        pdf.save(str(out), garbage=4, deflate=True)
        pdf.close()
        path.unlink(missing_ok=True)
        return send_file(out, as_attachment=True, download_name=out.name, mimetype="application/pdf")
    except Exception as exc:
        path.unlink(missing_ok=True)
        return jsonify(error=str(exc)), 400


@app.post("/api/pdf/add-text")
def add_pdf_text():
    path, original = uploaded_file()
    try:
        page_no = max(1, int(request.form.get("page", "1"))) - 1
        x = float(request.form.get("x", "60"))
        y = float(request.form.get("y", "80"))
        text = request.form.get("text", "")
        size = max(6, min(48, float(request.form.get("size", "12"))))
        if not text.strip(): raise ValueError("Enter the text to add.")
        pdf = fitz.open(str(path))
        if page_no >= len(pdf): raise ValueError("That page number does not exist.")
        pdf[page_no].insert_text((x, y), text, fontsize=size, fontname="helv", color=(0,0,0))
        out = TEMP_DIR / (clean_name(Path(original).stem) + "_annotated.pdf")
        pdf.save(str(out), garbage=4, deflate=True)
        pdf.close()
        path.unlink(missing_ok=True)
        return send_file(out, as_attachment=True, download_name=out.name, mimetype="application/pdf")
    except Exception as exc:
        path.unlink(missing_ok=True)
        return jsonify(error=str(exc)), 400


@app.post("/api/pdf/merge")
def merge_pdf():
    files = request.files.getlist("files")
    if len(files) < 2: return jsonify(error="Choose at least two PDF files."), 400
    result = fitz.open()
    saved = []
    try:
        for f in files:
            if not f.filename.lower().endswith(".pdf"): continue
            path = TEMP_DIR / (str(time.time_ns()) + ".pdf")
            f.save(path); saved.append(path)
            source = fitz.open(str(path))
            result.insert_pdf(source)
            source.close()
        if result.page_count < 1: raise ValueError("No valid PDF pages were found.")
        out = TEMP_DIR / ("merged_" + str(time.time_ns()) + ".pdf")
        result.save(str(out), garbage=4, deflate=True)
        result.close()
        return send_file(out, as_attachment=True, download_name="merged_documents.pdf", mimetype="application/pdf")
    except Exception as exc:
        result.close()
        return jsonify(error=str(exc)), 400
    finally:
        for path in saved: path.unlink(missing_ok=True)


@app.post("/api/docx/to-pdf")
def docx_to_pdf():
    path, original = uploaded_file()
    try:
        doc = Document(str(path))
        out = TEMP_DIR / (clean_name(Path(original).stem) + ".pdf")
        styles = getSampleStyleSheet()
        story = []
        for paragraph in doc.paragraphs:
            text = escape(paragraph.text).replace("\n", "<br/>")
            if text.strip():
                story.append(Paragraph(text, styles["BodyText"]))
                story.append(Spacer(1, 3*mm))
        for table in doc.tables:
            data = [[escape(cell.text).replace("\n", "<br/>") for cell in row.cells] for row in table.rows]
            if data:
                t = Table(data, repeatRows=1)
                t.setStyle(TableStyle([("GRID",(0,0),(-1,-1),0.5,colors.grey),("VALIGN",(0,0),(-1,-1),"TOP")]))
                story.append(t); story.append(Spacer(1, 4*mm))
        if not story: story = [Paragraph(" ", styles["BodyText"])]
        SimpleDocTemplate(str(out), pagesize=A4, rightMargin=18*mm, leftMargin=18*mm,
                          topMargin=18*mm, bottomMargin=18*mm).build(story)
        path.unlink(missing_ok=True)
        return send_file(out, as_attachment=True, download_name=out.name, mimetype="application/pdf")
    except Exception as exc:
        path.unlink(missing_ok=True)
        return jsonify(error="Word-to-PDF failed: " + str(exc)), 400


@app.post("/api/pdf/view")
def view_pdf():
    path, original = uploaded_file()
    response = make_response(send_file(path, mimetype="application/pdf"))
    response.headers["Content-Disposition"] = "inline; filename=" + clean_name(original) + ".pdf"
    return response


@app.post("/api/quit")
def quit_app():
    threading.Timer(0.5, lambda: os._exit(0)).start()
    return jsonify(ok=True)


if __name__ == "__main__":
    threading.Timer(1.0, lambda: webbrowser.open("http://127.0.0.1:8765")).start()
    app.run(host="127.0.0.1", port=8765, debug=False, threaded=True)
