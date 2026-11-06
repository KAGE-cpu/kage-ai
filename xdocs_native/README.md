# X DOCS — Native Android migration

This project is a Kotlin/Jetpack Compose Android application, not a WebView and not a Flask server. It is designed to keep document operations on-device and work without internet after installation.

## Current scope
- Native dark dashboard and bottom navigation.
- Import PDF files into app-private storage.
- Create and browse local folders.
- Delete local files and folders.
- Add a text overlay to a selected PDF page and save an edited copy.
- Convert multiple selected images into a multi-page PDF.
- Export a selected PDF using Android's document picker.

## Not yet implemented
The original Flask app also has PDF page preview, tap-anywhere text placement, PDF merge, PDF-to-image export, camera capture, rename and move. Those still need native implementation and device testing. The text overlay does not modify existing PDF text.

## Build
JDK 17, Android SDK Platform 35 and Gradle 8.9 are required.
From this folder run:
`gradle --no-daemon :app:assembleDebug`
APK: `app/build/outputs/apk/debug/app-debug.apk`

The GitHub Actions workflow in the repository root builds a debug APK and uploads it as an artifact. A successful build still needs installation and testing on the teacher's Android device before handover.

## Offline
The manifest declares no internet permission. Gradle uses internet only while building to download dependencies; the installed app's listed document operations are local.