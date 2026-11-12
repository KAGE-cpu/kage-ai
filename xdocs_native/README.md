# X DOCS — Native Android app

X DOCS is a Kotlin/Jetpack Compose Android app. It does not run Flask or use a WebView. File operations are performed locally; the app does not request internet permission.

## Original X DOCS feature coverage

- Local document library with top-level folders and files.
- Import multiple document files from Android's document picker, into the current folder or main library.
- Create folders; browse folder contents; rename, move and delete files.
- Export any imported file through Android's document picker.
- PDF page viewer with page navigation.
- PDF editor with a live page preview, draggable text and signature overlays, editable added text before saving, font family presets (sans, serif and monospace), bold presets, a live size slider, text colours, and transparent finger-drawn signature PNGs.
- Added text and signatures are saved as a separate edited PDF copy; existing PDF text is not rewritten. Text placement uses the page as the coordinate reference, with centred placement and preview sizing tied to PDF page dimensions. Selected text styling updates live as the font size or colour changes.
- Offline image editor for imported PNG/JPEG/WebP/BMP images: place text by tapping, drag text and transparent signatures, adjust text size and colour, and save a new PNG copy. Original images are preserved.
- Camera scanning using the full-size camera capture intent; capture multiple pages and combine them into a PDF.
- Import gallery images and convert multiple images into a multi-page PDF.
- Merge two or more PDFs in picker selection order.
- Offline document conversions: selectable-text PDF to DOCX/TXT, DOCX to PDF/TXT, TXT to DOCX/PDF, plus JPG/PNG/WebP image format conversion.
- Text-based DOCX/TXT-to-PDF uses Android's native PDF writer for more reliable Unicode text and multi-page output. DOCX parsing uses Android's XML pull parser.
- Convert every page of a PDF into PNG files and export them together as a ZIP.

The editor adds text overlays; it does not rewrite or remove existing PDF text. Document conversion is text-first and does not preserve complex Word page layout, embedded images, tables, headers, footers, or exact typography. PDF-to-Word/Text requires selectable PDF text; scanned PDFs need OCR, which is not included. Word conversions support modern `.docx` files, not legacy `.doc` files. The app currently supports the original app's one-level folder structure. It does not promise arbitrary file-format preview: non-PDF files can be stored, renamed, moved, deleted and exported.

## Build

The GitHub Actions workflow `.github/workflows/xdocs-android.yml` builds a debug APK with JDK 17, Gradle 8.9 and Android SDK Platform 35.

From this directory, with Android SDK installed:
`gradle --no-daemon :app:assembleDebug`

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Verification

A successful CI build proves compilation and packaging, not device-level correctness. Install on the target Android phone and test: multi-file import, folder uploads, rename/move/delete, non-PDF export, PDF page navigation, centred text placement and sizing, continuous multi-stroke signatures, image text/signature editing, camera scanning at full resolution, gallery image-to-PDF, PDF merge, PDF-to-PNG ZIP export, and airplane-mode operation. Very large PDFs or photos can still exceed available memory on older devices.

## Offline

Once installed, document operations are local. Gradle requires internet only while building to download dependencies.
