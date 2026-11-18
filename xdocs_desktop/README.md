# X DOCS Desktop

A local-first Windows desktop productivity workspace for document editing, exam-paper drafting, PDF operations and offline conversion.

## Current implementation

- Browser-based, full-screen desktop workspace served only on `127.0.0.1`.
- Rich-text document canvas with headings, fonts, font sizes, bold, italic, underline, strike-through, alignment, lists, text/highlight colours, undo/redo and tables.
- Import basic `.docx` paragraphs and tables; export editor content to `.docx`.
- Exam paper and memorandum starter templates.
- PDF preview through the computer's installed browser PDF viewer.
- PDF rotate, delete selected pages, add a text overlay and merge multiple PDFs.
- PDF-to-DOCX reconstruction using page dimensions, text-block positions, span font sizes and basic bold/italic metadata.
- DOCX-to-PDF export of paragraphs and tables.
- All processing is local; no account, cloud service or API key is used.

## Important limitations

This is the first desktop foundation, not a complete replacement for Microsoft Word or Google Docs yet. The editor uses the browser's editable document surface; pagination and some advanced formatting are not equivalent to a full word processor. DOCX import/export currently supports common paragraphs and tables, not every Word feature. Word-to-PDF does not faithfully reproduce all embedded images, page backgrounds, advanced styles or complex layouts. PDF-to-Word is best-effort: it reconstructs text blocks and basic styling, but cannot guarantee exact fidelity for multi-column layouts, scanned PDFs, unusual fonts, equations, forms or complex graphics. Scanned PDFs require OCR, which is not included. PDF text overlay adds new text; it does not rewrite existing PDF text. The PDF annotation coordinate defaults are basic and need a more visual placement editor in a later iteration.

## Build the portable Windows package

The GitHub Actions workflow builds a portable Windows x64 folder using Python 3.8 and PyInstaller. Download the `X-DOCS-Desktop-Windows` artifact, extract it, and run `X-DOCS-Desktop.exe`.

The packaged application opens the default browser at `http://127.0.0.1:8765`. Keep the application folder together. The application does not need internet access after it has been packaged.

## Compatibility warning

The workflow targets Windows x64 and uses a Python 3.8 runtime to improve compatibility with older Windows installations. It is not guaranteed to run on 32-bit Windows, Windows XP, or every old laptop. The target laptop must have a supported Windows version and a working browser. Check the laptop's Windows version and System type before promising installation. If the laptop is too old for this build, use a compatible alternate machine or create a separate legacy build after checking its exact specifications.

## Test checklist before handover

1. Launch the EXE with Wi-Fi turned off.
2. Create a document, apply formatting, insert a table, export DOCX and reopen it.
3. Generate an exam paper and memorandum template.
4. Open a normal selectable-text PDF, convert it to DOCX, and compare the page order, spacing and font hierarchy.
5. Test a two-column PDF and a scanned PDF separately; explain limitations if the result is not suitable.
6. Rotate, delete pages, merge PDFs and add a text overlay.
7. Convert a simple DOCX with paragraphs and a table to PDF.
8. Confirm downloads save correctly and originals remain unchanged.

A successful Windows build proves packaging, not full end-to-end testing on the teacher's laptop. Test the target computer before handing it over as production-ready.
