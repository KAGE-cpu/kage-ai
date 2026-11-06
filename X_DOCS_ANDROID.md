# X DOCS Android project

The native Android migration lives in [xdocs_native/](xdocs_native/).

It is a separate project from the existing KAGE AI web application. The X DOCS Android app is Kotlin/Jetpack Compose and does not use Flask or a WebView.

The GitHub Actions workflow `.github/workflows/xdocs-android.yml` builds a debug APK whenever files under `xdocs_native/` change on `main`. A successful run publishes the `X-DOCS-debug-apk` artifact.

See [the migration README](xdocs_native/README.md) and [feature checklist](xdocs_native/FEATURE_MIGRATION.md) for current functionality and outstanding work.