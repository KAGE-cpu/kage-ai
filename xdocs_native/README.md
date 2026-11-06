# X DOCS — Native Android app

X DOCS is a Kotlin/Jetpack Compose Android app. It does not run Flask or use a WebView. File operations are performed locally; the app does not request internet permission.

## Original X DOCS feature coverage

- Local document library with top-level folders and files.
- Import multiple document files from Android's document picker, into the current folder or main library.
- Create folders; browse folder contents; rename, move and delete files.
- Export any imported file through Android's document picker.
- PDF page viewer with page navigation.
- PDF editor with tap-to-position text, multiple text items across pages, undo-last-item, font-size selection and saving a separate edited PDF copy.
- Camera scanning using the full-size camera capture intent; capture multiple pages and combine them into a PDF.
- Import gallery images and convert multiple images into a multi-page PDF.
- Merge two or more PDFs in picker selection order.
- Convert every page of a PDF into PNG files and export them together as a ZIP.

The editor adds text overlays; it does not rewrite or remove existing PDF text. The app currently supports the original app's one-level folder structure. It does not promise arbitrary file-format preview: non-PDF files can be stored, renamed, moved, deleted and exported.

## Build

The GitHub Actions workflow `.github/workflows/xdocs-android.yml` builds a debug APK with JDK 17, Gradle 8.9 and Android SDK Platform 35.

From this directory, with Android SDK installed:
`gradle --no-daemon :app:assembleDebug`

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Verification

A successful CI build proves compilation and packaging, not device-level correctness. Install on the target Android phone and test: multi-file import, folder uploads, rename/move/delete, non-PDF export, PDF page navigation, multiple text overlays and undo, camera scanning at full resolution, gallery image-to-PDF, PDF merge, PDF-to-PNG ZIP export, and airplane-mode operation. Very large PDFs or photos can still exceed available memory on older devices.

## Offline

Once installed, document operations are local. Gradle requires internet only while building to download dependencies.
