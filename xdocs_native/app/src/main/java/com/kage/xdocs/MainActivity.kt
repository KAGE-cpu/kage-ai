package com.kage.xdocs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF090E1C)
private val Panel = Color(0xFF151F35)
private val Muted = Color(0xFF9AA8C5)
private val Accent = Color(0xFF9AA8FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { XDocsApp(File(filesDir, "XDocs").apply { mkdirs() }) }
    }
}
private enum class Page { HOME, FILES, SCAN, CONVERT, VIEWER, EDITOR }

private data class PdfTextOverlay(
    val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val fontSize: Float
)

@Composable
private fun XDocsApp(root: File) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.HOME) }
    var folder by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("Your documents stay on this device.") }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var renameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var moveDialog by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf("") }
    var scanBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var cameraCaptureFile by remember { mutableStateOf<File?>(null) }
    var exportZip by remember { mutableStateOf<File?>(null) }

    fun currentDir() = folder?.let { File(root, it) } ?: root
    fun copyUri(uri: android.net.Uri, destination: File): File {
        val raw = uri.lastPathSegment ?: "document.pdf"
        val name = raw.substringAfterLast('/').substringAfterLast(':').safeFile()
        val target = uniqueFile(destination, name)
        context.contentResolver.openInputStream(uri).use { input ->
            target.outputStream().use { output -> input?.copyTo(output) ?: error("Cannot read selected file") }
        }
        return target
    }
    fun createPdf(bitmaps: List<Bitmap>, output: File) {
        require(bitmaps.isNotEmpty()) { "No images were selected." }
        PDDocument().use { doc ->
            bitmaps.forEach { bitmap ->
                val pdfPage = PDPage(PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat()))
                doc.addPage(pdfPage)
                val image = LosslessFactory.createFromImage(doc, bitmap)
                PDPageContentStream(doc, pdfPage).use { it.drawImage(image, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()) }
            }
            doc.save(output)
        }
    }
    fun mergePdfs(uris: List<android.net.Uri>): File {
        require(uris.size >= 2) { "Choose at least two PDF files." }
        val tempDir = File(root, "merge_temp").apply { mkdirs() }
        val temp = uris.map { copyUri(it, tempDir) }
        val output = uniqueFile(root, "Merged_Documents.pdf")
        val merger = PDFMergerUtility()
        temp.forEach { merger.addSource(it) }
        merger.destinationFileName = output.absolutePath
        merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
        temp.forEach { it.delete() }; tempDir.delete()
        return output
    }

    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runCatching {
            val imported = uris.map { copyUri(it, currentDir()) }
            selected = imported.lastOrNull()
            page = Page.FILES
            refresh++
            message = "Imported ${imported.size} file(s)"
        }.onFailure { message = "Import failed: ${it.message}" }
    }
    val importImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) runCatching {
            val bitmaps = uris.map { uri -> decodeSampledBitmap(context.contentResolver, uri) }
            val output = uniqueFile(currentDir(), "Images_to_PDF.pdf")
            createPdf(bitmaps, output)
            bitmaps.forEach { it.recycle() }
            selected = output; page = Page.FILES; refresh++
            message = "Created ${output.name}"
        }.onFailure { message = "Image conversion failed: ${it.message}" }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val capture = cameraCaptureFile
        cameraCaptureFile = null
        if (success && capture != null) runCatching {
            val bitmap = decodeSampledBitmap(capture)
            scanBitmaps = scanBitmaps + bitmap
            message = "Captured full-resolution page ${scanBitmaps.size}. Capture another page or create the PDF."
        }.onFailure { message = "Could not read camera image: ${it.message}" }
        else message = "Camera capture cancelled."
        capture?.delete()
    }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runCatching {
            val output = mergePdfs(uris)
            selected = output; page = Page.FILES; refresh++
            message = "Merged ${uris.size} PDFs into ${output.name}"
        }.onFailure { message = "Merge failed: ${it.message}" }
    }
    val exportSelected = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val source = selected
        if (uri != null && source != null) runCatching {
            context.contentResolver.openOutputStream(uri).use { output ->
                source.inputStream().use { it.copyTo(output ?: error("Cannot write destination")) }
            }
            message = "Exported ${source.name}"
        }.onFailure { message = "Export failed: ${it.message}" }
    }
    val exportArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = exportZip
        if (uri != null && source != null) runCatching {
            context.contentResolver.openOutputStream(uri).use { output ->
                source.inputStream().use { it.copyTo(output ?: error("Cannot write destination")) }
            }
            message = "Exported ${source.name}"
        }.onFailure { message = "ZIP export failed: ${it.message}" }
    }
    val entries = remember(refresh, folder) {
        currentDir().listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                if (page != Page.VIEWER && page != Page.EDITOR) NavigationBar(containerColor = Color(0xFF111A2D)) {
                    listOf(Page.HOME to Icons.Default.Home, Page.FILES to Icons.Default.Folder, Page.SCAN to Icons.Default.CameraAlt, Page.CONVERT to Icons.Default.Transform).forEach { (p, icon) ->
                        NavigationBarItem(selected = page == p, onClick = { page = p; if (p != Page.FILES) folder = null }, icon = { Icon(icon, null) }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("X DOCS", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                        Text("Your documents. On your device.", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Description, null, tint = Accent, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(14.dp))
                when (page) {
                    Page.HOME -> {
                        Text("Welcome back", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("Your everyday document workspace, offline.", color = Muted)
                        Spacer(Modifier.height(14.dp))
                        Action("My Documents", "Browse, rename, move and organise files", Icons.Default.FolderOpen) { page = Page.FILES; folder = null }
                        Action("Import files", "Copy documents and other files into X DOCS", Icons.Default.UploadFile) { importFiles.launch(arrayOf("*/*")) }
                        Action("Open PDF viewer", "Read and navigate PDF pages", Icons.Default.MenuBook) { if (selected?.extension.equals("pdf", true)) page = Page.VIEWER else { message = "Select a PDF first."; page = Page.FILES } }
                        Action("PDF editor", "Tap the page to position new text", Icons.Default.Edit) { if (selected?.extension.equals("pdf", true)) page = Page.EDITOR else { message = "Select a PDF first."; page = Page.FILES } }
                        Action("Scan documents", "Capture multiple camera pages into one PDF", Icons.Default.CameraAlt) { page = Page.SCAN }
                        Action("Convert and merge", "Image-to-PDF, PDF merge and PDF-to-image", Icons.Default.Transform) { page = Page.CONVERT }
                    }
                    Page.FILES -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(folder ?: "My Documents", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { folderDialog = true }) { Icon(Icons.Default.CreateNewFolder, null); Text("Folder") }
                            TextButton(onClick = { importFiles.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, null); Text("Import") }
                        }
                        if (folder != null) TextButton(onClick = { folder = null }) { Icon(Icons.Default.ArrowBack, null); Text("All documents") }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.weight(1f)) {
                            items(entries, key = { it.absolutePath }) { item ->
                                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp)).padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (item.isDirectory) Icons.Default.Folder else if (item.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null, tint = Accent)
                                    Column(Modifier.weight(1f).padding(start = 8.dp).clickable {
                                        if (item.isDirectory) folder = item.name
                                        else { selected = item; if (item.extension.equals("pdf", true)) page = Page.VIEWER else message = item.name }
                                    }) {
                                        Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(if (item.isDirectory) "Folder" else "${item.length() / 1024} KB", color = Muted, fontSize = 11.sp)
                                    }
                                    if (!item.isDirectory && item.extension.equals("pdf", true)) IconButton(onClick = { selected = item; page = Page.EDITOR }) { Icon(Icons.Default.Edit, "Edit", tint = Accent) }
                                    IconButton(onClick = { selected = item; renameValue = item.name; renameDialog = true }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename", tint = Muted) }
                                    IconButton(onClick = { selected = item; moveTarget = ""; moveDialog = true }) { Icon(Icons.Default.DriveFileMove, "Move", tint = Muted) }
                                    if (!item.isDirectory) IconButton(onClick = { selected = item; exportSelected.launch(item.name) }) { Icon(Icons.Default.FileDownload, "Export", tint = Accent) }
                                    IconButton(onClick = { item.deleteRecursively(); if (selected == item) selected = null; refresh++; message = "Deleted ${item.name}" }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF8795)) }
                                }
                            }
                        }
                        if (entries.isEmpty()) EmptyState("No files here yet", "Import a PDF or create a folder to get started.")
                    }
                    Page.SCAN -> {
                        Text("Scan documents", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Capture pages with your camera or select images from your gallery.", color = Muted)
                        Action("Capture page", "Use your phone camera at full resolution", Icons.Default.CameraAlt) {
                            runCatching {
                                val capture = File(context.cacheDir, "xdocs_scan_${System.currentTimeMillis()}.jpg")
                                cameraCaptureFile = capture
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", capture)
                                camera.launch(uri)
                            }.onFailure { cameraCaptureFile?.delete(); cameraCaptureFile = null; message = "Camera could not start: ${it.message}" }
                        }
                        Action("Add gallery images", "Create a PDF from existing photos", Icons.Default.AddPhotoAlternate) { importImages.launch("image/*") }
                        Text("Captured pages waiting: ${scanBitmaps.size}", color = Accent, fontWeight = FontWeight.SemiBold)
                        if (scanBitmaps.isNotEmpty()) {
                            Action("Create scanned PDF", "Combine captured pages into one document", Icons.Default.PictureAsPdf) {
                                runCatching {
                                    val output = uniqueFile(currentDir(), "Scanned_Document.pdf")
                                    createPdf(scanBitmaps, output)
                                    scanBitmaps.forEach { it.recycle() }; scanBitmaps = emptyList()
                                    selected = output; page = Page.FILES; refresh++
                                    message = "Saved ${output.name}"
                                }.onFailure { message = "Scan failed: ${it.message}" }
                            }
                            TextButton(onClick = { scanBitmaps.forEach { it.recycle() }; scanBitmaps = emptyList() }) { Text("Clear captured pages") }
                        }
                    }
                    Page.CONVERT -> {
                        Text("Convert documents", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Action("Images to PDF", "Combine selected pictures into one PDF", Icons.Default.Image) { importImages.launch("image/*") }
                        Action("Merge PDF files", "Join two or more PDFs in selection order", Icons.Default.MergeType) { mergePicker.launch(arrayOf("application/pdf")) }
                        Action("PDF to images", "Render pages to PNG and package them in a ZIP", Icons.Default.Collections) {
                            val source = selected?.takeIf { it.extension.equals("pdf", true) }
                            if (source == null) message = "Select a PDF from My Documents first."
                            else runCatching {
                                exportZip = renderPdfToImages(source, root)
                                message = "Created ${exportZip!!.name}. Use Export page images ZIP below."
                            }.onFailure { message = "PDF conversion failed: ${it.message}" }
                        }
                        Action("Export selected file", "Save a copy to a location you choose", Icons.Default.FileDownload) {
                            selected?.let { exportSelected.launch(it.name) } ?: run { message = "Select a file first." }
                        }
                        if (exportZip != null) Action("Export page images ZIP", "Save rendered PNG pages outside X DOCS", Icons.Default.FolderZip) { exportArchive.launch(exportZip!!.name) }
                        Text("PDF-to-image creates one PNG per page and a ZIP archive for export.", color = Muted, fontSize = 12.sp)
                    }
                    Page.VIEWER, Page.EDITOR -> PdfWorkspace(
                        file = selected,
                        editing = page == Page.EDITOR,
                        onBack = { page = Page.FILES },
                        onSave = { textOverlays ->
                            val source = selected ?: error("No PDF selected")
                            val output = addTextToPdf(source, textOverlays, currentDir())
                            selected = output; refresh++; message = "Saved edited copy with ${textOverlays.size} text item(s): ${output.name}"; page = Page.FILES
                        },
                        onMessage = { message = it }
                    )
                }
                if (page != Page.VIEWER && page != Page.EDITOR) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }

    if (folderDialog) AlertDialog(
        onDismissRequest = { folderDialog = false },
        title = { Text("New folder") },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("Folder name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val clean = folderName.trim().safeFile()
            if (clean.isNotBlank()) {
                val dest = File(root, clean)
                if (dest.exists()) message = "A folder with that name already exists."
                else { dest.mkdirs(); message = "Created folder ${clean}" }
            }
            folderName = ""; folderDialog = false; refresh++
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Cancel") } }
    )
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false },
        title = { Text("Rename item") },
        text = { OutlinedTextField(renameValue, { renameValue = it }, label = { Text("New name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val source = selected
            val clean = renameValue.trim().safeFile()
            if (source != null && clean.isNotBlank()) {
                val dest = File(source.parentFile, clean)
                if (dest.exists()) message = "That name already exists."
                else if (source.renameTo(dest)) { selected = dest; refresh++; message = "Renamed to ${dest.name}" }
                else message = "Rename failed."
            }
            renameDialog = false
        }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } }
    )
    if (moveDialog) AlertDialog(
        onDismissRequest = { moveDialog = false },
        title = { Text("Move item") },
        text = {
            Column {
                Text("Choose destination")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = moveTarget == "@root", onClick = { moveTarget = "@root" })
                    Text("Main documents")
                }
                root.listFiles()?.filter { it.isDirectory && it != selected?.parentFile }?.forEach { dir ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = moveTarget == dir.name, onClick = { moveTarget = dir.name })
                        Text(dir.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            val source = selected
            val destDir = if (moveTarget == "@root") root else File(root, moveTarget)
            if (source != null && moveTarget.isNotBlank() && destDir.isDirectory) {
                val dest = File(destDir, source.name)
                if (dest.exists()) message = "An item with that name already exists."
                else if (source.renameTo(dest)) { selected = dest; refresh++; message = "Moved ${dest.name}" }
                else message = "Move failed."
            } else message = "Choose a destination."
            moveDialog = false
        }) { Text("Move") } },
        dismissButton = { TextButton(onClick = { moveDialog = false }) { Text("Cancel") } }
    )
}

@Composable
private fun PdfWorkspace(file: File?, editing: Boolean, onBack: () -> Unit, onSave: (List<PdfTextOverlay>) -> Unit, onMessage: (String) -> Unit) {
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var textDraft by remember(file) { mutableStateOf("") }
    var fontSize by remember(file) { mutableStateOf("14") }
    var tapX by remember(file) { mutableFloatStateOf(48f) }
    var tapY by remember(file) { mutableFloatStateOf(48f) }
    var positionChosen by remember(file) { mutableStateOf(false) }
    var overlays by remember(file) { mutableStateOf(emptyList<PdfTextOverlay>()) }
    var bitmap by remember(file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var pageWidth by remember(file, pageIndex) { mutableFloatStateOf(1f) }
    var pageHeight by remember(file, pageIndex) { mutableFloatStateOf(1f) }
    LaunchedEffect(file, pageIndex) {
        bitmap?.recycle(); bitmap = null
        if (file == null || !file.exists()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(file).use { doc ->
                    require(doc.numberOfPages > 0) { "This PDF has no pages." }
                    val idx = pageIndex.coerceIn(0, doc.numberOfPages - 1)
                    val pdfPage = doc.getPage(idx)
                    val rendered = PDFRenderer(doc).renderImage(idx, 1.15f, ImageType.RGB)
                    Triple(rendered, pdfPage.mediaBox.width, pdfPage.mediaBox.height) to doc.numberOfPages
                }
            }
        }.onSuccess { result ->
            bitmap = result.first.first
            pageWidth = result.first.second
            pageHeight = result.first.third
            pageCount = result.second
        }.onFailure { onMessage("Could not open PDF: ${it.message}") }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
            Text(if (editing) "PDF Editor" else "PDF Viewer", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Text(file?.name ?: "No PDF selected", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { if (pageIndex > 0) { pageIndex--; positionChosen = false } }) { Icon(Icons.Default.ChevronLeft, null, tint = Accent) }
            Text(if (pageCount > 0) "Page ${pageIndex + 1} of ${pageCount}" else "Loading page…", color = Color.White)
            IconButton(onClick = { if (pageIndex + 1 < pageCount) { pageIndex++; positionChosen = false } }) { Icon(Icons.Default.ChevronRight, null, tint = Accent) }
        }
        if (editing) {
            OutlinedTextField(
                value = textDraft,
                onValueChange = { textDraft = it },
                label = { Text("Text to add") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3
            )
            OutlinedTextField(
                value = fontSize,
                onValueChange = { fontSize = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                label = { Text("Font size (6–72)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("Tap the page to choose where this text will be placed.", color = Accent, fontSize = 12.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202A3D), RoundedCornerShape(12.dp)).padding(5.dp), contentAlignment = Alignment.Center) {
            val current = bitmap
            if (current != null) {
                Image(current.asImageBitmap(), contentDescription = "PDF page ${pageIndex + 1}", modifier = Modifier.fillMaxWidth().wrapContentHeight().pointerInput(editing, current, pageWidth, pageHeight) {
                    detectTapGestures { offset ->
                        if (editing) {
                            val sx = pageWidth / size.width.coerceAtLeast(1)
                            val sy = pageHeight / size.height.coerceAtLeast(1)
                            tapX = (offset.x * sx).coerceIn(0f, pageWidth - 4f)
                            tapY = (pageHeight - offset.y * sy).coerceIn(4f, pageHeight - 4f)
                            positionChosen = true
                            onMessage("Text position selected on page ${pageIndex + 1}.")
                        }
                    }
                })
            } else Text("Rendering PDF page…", color = Muted)
        }
        if (editing) {
            Text("Position: ${tapX.toInt()}, ${tapY.toInt()} PDF points", color = Muted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (!positionChosen) onMessage("Tap the PDF page first.")
                        else if (textDraft.isBlank()) onMessage("Enter the text to add.")
                        else {
                            val size = (fontSize.toFloatOrNull() ?: 14f).coerceIn(6f, 72f)
                            overlays = overlays + PdfTextOverlay(pageIndex, tapX, tapY, textDraft.trim(), size)
                            textDraft = ""
                            positionChosen = false
                            onMessage("Added text item. Total: ${overlays.size}")
                        }
                    },
                    enabled = bitmap != null && positionChosen,
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Default.Add, null); Text("Add text") }
                OutlinedButton(
                    onClick = {
                        if (overlays.isNotEmpty()) {
                            overlays = overlays.dropLast(1)
                            onMessage("Removed the last text item.")
                        }
                    },
                    enabled = overlays.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Default.Undo, null); Text("Undo") }
            }
            Text("${overlays.size} text item(s) queued across pages", color = Muted, fontSize = 11.sp)
            Button(
                onClick = {
                    if (overlays.isEmpty()) onMessage("Add at least one text item first.")
                    else onSave(overlays)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = file != null && bitmap != null && overlays.isNotEmpty()
            ) {
                Icon(Icons.Default.Save, null); Text("Save edited copy")
            }
        } else Text("Read-only preview. Open Edit to add text.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Action(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Panel, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}
@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.FolderOpen, null, tint = Muted, modifier = Modifier.size(38.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

private fun addTextToPdf(source: File, overlays: List<PdfTextOverlay>, directory: File): File {
    require(overlays.isNotEmpty()) { "Add at least one text item." }
    val output = uniqueFile(directory, "${source.nameWithoutExtension}_edited.pdf")
    PDDocument.load(source).use { doc ->
        val embeddedFont = runCatching {
            val fontFile = File("/system/fonts/Roboto-Regular.ttf")
            if (fontFile.isFile) com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, fontFile) else null
        }.getOrNull()
        val font = embeddedFont ?: PDType1Font.HELVETICA
        overlays.forEach { item ->
            require(item.page in 0 until doc.numberOfPages) { "Page ${item.page + 1} does not exist." }
            val page = doc.getPage(item.page)
            val px = item.x.coerceIn(0f, page.mediaBox.width - 2f)
            val py = item.y.coerceIn(item.fontSize, page.mediaBox.height - 2f)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.beginText()
                stream.setFont(font, item.fontSize)
                stream.setNonStrokingColor(0, 0, 0)
                stream.newLineAtOffset(px, py)
                item.text.split('\n').take(40).forEachIndexed { index, line ->
                    if (index > 0) stream.newLineAtOffset(0f, -item.fontSize * 1.2f)
                    val printable = if (embeddedFont != null) line.take(2000) else line.map { c ->
                        if (c.code in 32..255) c else '?'
                    }.joinToString("").take(2000)
                    stream.showText(printable)
                }
                stream.endText()
            }
        }
        doc.save(output)
    }
    return output
}
private fun renderPdfToImages(source: File, root: File): File {
    val folder = File(root, "${source.nameWithoutExtension}_pages_${System.currentTimeMillis()}").apply { mkdirs() }
    val zip = uniqueFile(root, "${source.nameWithoutExtension}_pages.zip")
    PDDocument.load(source).use { doc ->
        val renderer = PDFRenderer(doc)
        for (i in 0 until doc.numberOfPages) {
            val bitmap = renderer.renderImage(i, 1.5f, ImageType.RGB)
            FileOutputStream(File(folder, "page_${(i + 1).toString().padStart(3, '0')}.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    ZipOutputStream(FileOutputStream(zip)).use { out ->
        folder.listFiles()?.sortedBy { it.name }?.forEach { image ->
            out.putNextEntry(ZipEntry(image.name))
            image.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
    return zip
}
private fun uniqueFile(dir: File, desired: String): File {
    dir.mkdirs()
    val base = desired.substringBeforeLast('.', desired)
    val ext = desired.substringAfterLast('.', "").let { if (it.isBlank() || it == desired) "" else ".$it" }
    var candidate = File(dir, desired)
    var n = 2
    while (candidate.exists()) { candidate = File(dir, "${base}_${n++}${ext}") }
    return candidate
}
private fun decodeSampledBitmap(file: File, maxDimension: Int = 2000): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, options) ?: error("Could not decode image.")
}

private fun decodeSampledBitmap(resolver: android.content.ContentResolver, uri: android.net.Uri, maxDimension: Int = 2000): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return resolver.openInputStream(uri).use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: error("Could not decode selected image.")
}

private fun String.safeFile(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100).ifBlank { "document.pdf" }
