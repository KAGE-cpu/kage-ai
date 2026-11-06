# X DOCS — Native Android app

X DOCS is a Kotlin/Jetpack Compose Android app. It is not a WebView and does not run Flask. Document operations are performed locally on the device; no internet permission is declared in the app manifest.

## Native features in this version
- Dashboard and bottom navigation.
- Import PDFs into app-private document storage.
- Create, browse, rename, move and delete local files and top-level folders.
- PDF page viewer with page navigation.
- PDF text overlay editor: tap the rendered page to choose a position, set font size, and save an edited copy. It adds new text; it does not alter existing PDF text.
- Convert multiple gallery images into a multi-page PDF.
- Camera capture of multiple document pages into a PDF, plus gallery-image import.
- Merge two or more selected PDFs in selection order.
- Convert every page of a PDF into PNG files and package them into a ZIP for export.
- Export a selected PDF through Android's document picker.

## Build
The GitHub Actions workflow `.github/workflows/xdocs-android.yml` builds a debug APK with JDK 17, Gradle 8.9 and Android SDK Platform 35.

From this directory, with Android SDK installed:
`gradle --no-daemon :app:assembleDebug`

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Important verification notes
A successful CI build confirms compilation and packaging only. It does not prove every operation works on every Android device. Install the APK on the target phone and test import, camera capture, PDF rendering, text placement, merging, image conversion, ZIP export, rename, move and delete before handover. Large PDFs and high-resolution images may require substantial device memory.

## Offline
The installed app declares no internet permission. Gradle needs internet only during the build to download dependencies. Once installed, document operations are local.
