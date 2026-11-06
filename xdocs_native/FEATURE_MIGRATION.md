# X DOCS native feature checklist

| Original Flask feature | Native implementation |
|---|---|
| Dashboard/navigation | Implemented |
| Create and browse folders | Implemented (top-level folders) |
| Upload/import PDF | Implemented with Android document picker |
| View PDF | Implemented with rendered page preview and previous/next navigation |
| Add text to PDF | Implemented as a new text overlay; tap-to-position and font-size selection |
| Save edited PDF copy | Implemented |
| Image to PDF | Implemented for multiple gallery images |
| Merge PDFs | Implemented for two or more selected PDFs |
| PDF to images | Implemented as PNG pages bundled into an exportable ZIP |
| Camera scanning | Implemented using camera capture preview; supports multiple captured pages |
| Rename files | Implemented |
| Move files between main storage and top-level folders | Implemented |
| Delete files/folders | Implemented |

## Known boundaries
- PDF text overlay adds new text; it does not edit or remove existing PDF text.
- Camera capture uses Android's camera capture activity preview; it is not an advanced edge-detection/document-correction scanner.
- Folder management is one level deep, matching the original Flask app's top-level folder structure.
- PDF-to-image output is a ZIP of PNG page images.
- App-private storage is used. Use the export actions to copy finished PDFs/ZIPs to a user-selected shared location.
- A successful CI build is not a substitute for installation and feature testing on the intended Android phone.