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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
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

private val Ink = Color(0xFF08050D)
private val Panel = Color(0xFF171020)
private val Muted = Color(0xFFB5A8C8)
private val Accent = Color(0xFFB66CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { XDocsApp(File(filesDir, "XDocs").apply { mkdirs() }) }
    }
}
private enum class Page { HOME, FILES, SCAN, CONVERT, VIEWER, EDITOR, IMAGE_EDITOR }

private data class PdfTextOverlay(
    val page: Int, val x: Float, val y: Float, val text: String, val fontSize: Float,
    val fontKey: String = "sans", val colorArgb: Int = android.graphics.Color.BLACK,
    val signaturePath: String? = null
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
                if (page != Page.VIEWER && page != Page.EDITOR && page != Page.IMAGE_EDITOR) NavigationBar(containerColor = Color(0xFF120B1A)) {
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
                        Action("PDF editor", "Add precisely positioned text and signatures", Icons.Default.Edit) { if (selected?.extension.equals("pdf", true)) page = Page.EDITOR else { message = "Select a PDF first."; page = Page.FILES } }
                        Action("Image editor", "Add movable text and signatures to pictures", Icons.Default.Brush) { if (selected?.extension?.lowercase()?.let { it in listOf("png","jpg","jpeg","webp","bmp") } == true) page = Page.IMAGE_EDITOR else { message = "Select an image first."; page = Page.FILES } }
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
                                        else { selected = item; if (item.extension.equals("pdf", true)) page = Page.VIEWER else if (item.extension.lowercase() in listOf("png","jpg","jpeg","webp","bmp")) page = Page.IMAGE_EDITOR else message = item.name }
                                    }) {
                                        Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(if (item.isDirectory) "Folder" else "${item.length() / 1024} KB", color = Muted, fontSize = 11.sp)
                                    }
                                    if (!item.isDirectory && item.extension.equals("pdf", true)) IconButton(onClick = { selected = item; page = Page.EDITOR }) { Icon(Icons.Default.Edit, "Edit PDF", tint = Accent) }
                                    if (!item.isDirectory && item.extension.lowercase() in listOf("png","jpg","jpeg","webp","bmp")) IconButton(onClick = { selected = item; page = Page.IMAGE_EDITOR }) { Icon(Icons.Default.Brush, "Edit image", tint = Accent) }
                                    IconButton(onClick = { selected = item; renameValue = item.name; renameDialog = true }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename", tint = Muted) }
                                    if (!item.isDirectory) IconButton(onClick = { selected = item; moveTarget = ""; moveDialog = true }) { Icon(Icons.Default.DriveFileMove, "Move", tint = Muted) }
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
                            val output = addTextToPdf(source, textOverlays, source.parentFile ?: root)
                            selected = output; refresh++; message = "Saved edited copy with ${textOverlays.size} item(s): ${output.name}"; page = Page.FILES
                        },
                        onMessage = { message = it }
                    )
                    Page.IMAGE_EDITOR -> ImageWorkspace(
                        file = selected,
                        onBack = { page = Page.FILES },
                        onSave = { imageOverlays ->
                            val source = selected ?: error("No image selected")
                            val output = renderImageWithOverlays(source, imageOverlays, source.parentFile ?: root)
                            selected = output; refresh++; message = "Saved edited image: ${output.name}"; page = Page.FILES
                        },
                        onMessage = { message = it }
                    )
                }
                if (page != Page.VIEWER && page != Page.EDITOR && page != Page.IMAGE_EDITOR) {
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
    val context = LocalContext.current
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var draft by remember(file) { mutableStateOf("") }
    var fontSize by remember(file) { mutableFloatStateOf(16f) }
    var fontKey by remember(file) { mutableStateOf("sans") }
    var textColor by remember(file) { mutableIntStateOf(android.graphics.Color.BLACK) }
    var overlays by remember(file) { mutableStateOf(emptyList<PdfTextOverlay>()) }
    var selected by remember(file) { mutableIntStateOf(-1) }
    var bitmap by remember(file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var pageWidth by remember(file, pageIndex) { mutableFloatStateOf(1f) }
    var pageHeight by remember(file, pageIndex) { mutableFloatStateOf(1f) }
    var fontMenu by remember { mutableStateOf(false) }
    var signatureDialog by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<List<androidx.compose.ui.geometry.Offset>>>(emptyList()) }
    var activeStroke by remember { mutableStateOf(emptyList<androidx.compose.ui.geometry.Offset>()) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun syncSelectedStyle(size: Float = fontSize, key: String = fontKey, color: Int = textColor) {
        if (selected in overlays.indices && overlays[selected].signaturePath == null) {
            val copy = overlays.toMutableList()
            copy[selected] = copy[selected].copy(fontSize = size, fontKey = key, colorArgb = color)
            overlays = copy
        }
    }

    LaunchedEffect(file, pageIndex) {
        bitmap?.recycle(); bitmap = null
        if (file == null || !file.exists()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { PDDocument.load(file).use { doc ->
                require(doc.numberOfPages > 0) { "This PDF has no pages." }
                val idx=pageIndex.coerceIn(0,doc.numberOfPages-1)
                Triple(PDFRenderer(doc).renderImage(idx,1.2f,ImageType.RGB),doc.getPage(idx).mediaBox.width,doc.getPage(idx).mediaBox.height) to doc.numberOfPages
            } }
        }.onSuccess { r -> bitmap=r.first.first; pageWidth=r.first.second; pageHeight=r.first.third; pageCount=r.second }
            .onFailure { onMessage("Could not open PDF: ${it.message}") }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null); Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(if(editing) "KAGE PDF STUDIO" else "PDF Viewer",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
                Text(file?.name ?: "No PDF selected",color=Muted,fontSize=10.sp,maxLines=1)
            }
            if(editing) Text("${overlays.size} items",color=Accent,fontSize=11.sp)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center,modifier=Modifier.fillMaxWidth()) {
            IconButton(onClick={if(pageIndex>0) pageIndex--}) { Icon(Icons.Default.ChevronLeft,null,tint=Accent) }
            Text(if(pageCount>0) "Page ${pageIndex+1} of $pageCount" else "Loading…",color=Color.White)
            IconButton(onClick={if(pageIndex+1<pageCount) pageIndex++}) { Icon(Icons.Default.ChevronRight,null,tint=Accent) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF090711),RoundedCornerShape(14.dp)).padding(6.dp),contentAlignment=Alignment.Center) {
            val current=bitmap
            if(current!=null) BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                val scale=minOf(maxWidth.value/pageWidth.coerceAtLeast(1f),maxHeight.value/pageHeight.coerceAtLeast(1f))
                val dw=(pageWidth*scale).dp; val dh=(pageHeight*scale).dp
                val pageWidthPx = with(LocalDensity.current) { dw.toPx() }
                val pageHeightPx = with(LocalDensity.current) { dh.toPx() }
                val previewFontScale = LocalDensity.current.fontScale
                Box(Modifier.width(dw).height(dh)) {
                    Image(current.asImageBitmap(),contentDescription="PDF page",modifier=Modifier.fillMaxSize())
                    if(editing) {
                        Box(Modifier.fillMaxSize().pointerInput(pageIndex,dw,dh) {
                            detectTapGestures { p ->
                                if(draft.isNotBlank()) {
                                    overlays=overlays+PdfTextOverlay(pageIndex,(p.x/size.width).coerceIn(0f,.95f),(p.y/size.height).coerceIn(0f,.95f),draft,fontSize,fontKey,textColor)
                                    selected=overlays.lastIndex; draft=""
                                    onMessage("Text placed. Tap Update selected to edit it, or drag it to move it.")
                                } else onMessage("Enter text first, then tap the page to place it.")
                            }
                        })
                        overlays.forEachIndexed { i,item ->
                            if(item.page==pageIndex) {
                                if(item.signaturePath==null) {
                                    Text(item.text,color=Color(item.colorArgb),fontSize=(item.fontSize*dw.value/pageWidth.coerceAtLeast(1f)/previewFontScale).sp,
                                        fontWeight=if(item.fontKey.endsWith("bold")) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily=when { item.fontKey.startsWith("serif")->androidx.compose.ui.text.font.FontFamily.Serif; item.fontKey=="mono"->androidx.compose.ui.text.font.FontFamily.Monospace; else->androidx.compose.ui.text.font.FontFamily.SansSerif },
                                        modifier=Modifier.offset(x=(item.x*dw.value).dp,y=(item.y*dh.value).dp)
                                            .graphicsLayer { translationX = -size.width / 2f }
                                            .background(if(selected==i) Color(0x447C3AED) else Color.Transparent)
                                            .pointerInput(i,pageWidthPx,pageHeightPx) { detectDragGestures(onDragStart={selected=i},onDrag={c,d ->
                                                c.consume(); val list=overlays.toMutableList(); val old=list[i]
                                                list[i]=old.copy(x=(old.x+d.x/pageWidthPx.coerceAtLeast(1f)).coerceIn(0f,1f),y=(old.y+d.y/pageHeightPx.coerceAtLeast(1f)).coerceIn(0f,1f)); overlays=list
                                            }) }.clickable { selected=i; draft=item.text; fontSize=item.fontSize; fontKey=item.fontKey; textColor=item.colorArgb }
                                    )
                                } else {
                                    val sig=remember(item.signaturePath) { BitmapFactory.decodeFile(item.signaturePath) }
                                    if(sig!=null) {
                                        val sigWidthDp = dw.value * .28f
                                        val sigHeightDp = sigWidthDp * sig.height.toFloat() / sig.width.coerceAtLeast(1)
                                        Image(sig.asImageBitmap(),contentDescription="Signature",
                                        modifier=Modifier.offset(x=(item.x*dw.value).dp,y=(item.y*dh.value).dp).width(sigWidthDp.dp).height(sigHeightDp.dp)
                                            .graphicsLayer { translationX = -size.width / 2f }
                                            .pointerInput(i,pageWidthPx,pageHeightPx) { detectDragGestures(onDragStart={selected=i},onDrag={c,d ->
                                                c.consume(); val list=overlays.toMutableList(); val old=list[i]
                                                list[i]=old.copy(x=(old.x+d.x/pageWidthPx.coerceAtLeast(1f)).coerceIn(0f,1f),y=(old.y+d.y/pageHeightPx.coerceAtLeast(1f)).coerceIn(0f,1f)); overlays=list
                                            }) }.clickable { selected=i }
                                    )
                                    }
                                }
                            }
                        }
                    }
                }
            } else Text("Rendering PDF…",color=Muted)
        }
        if(editing) Column(Modifier.fillMaxWidth().background(Color(0xFF191020),RoundedCornerShape(14.dp)).padding(9.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(draft,{draft=it},label={Text("Text to add or edit")},modifier=Modifier.fillMaxWidth(),maxLines=2)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick={fontMenu=true}) { Text(when(fontKey){"serif"->"Serif";"serif_bold"->"Serif Bold";"mono"->"Mono";"sans_bold"->"Sans Bold";else->"Sans"}) }
                    DropdownMenu(expanded=fontMenu,onDismissRequest={fontMenu=false}) {
                        listOf("sans" to "Sans","sans_bold" to "Sans Bold","serif" to "Serif","serif_bold" to "Serif Bold","mono" to "Monospace").forEach { (k,v)->DropdownMenuItem(text={Text(v)},onClick={fontKey=k;syncSelectedStyle(key=k);fontMenu=false}) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("${fontSize.toInt()} pt",color=Color.White,fontSize=12.sp)
                Slider(value=fontSize,onValueChange={fontSize=it;syncSelectedStyle(size=it)},valueRange=6f..48f,modifier=Modifier.weight(1f))
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Colour",color=Muted,fontSize=11.sp)
                listOf(android.graphics.Color.BLACK,android.graphics.Color.rgb(35,75,190),android.graphics.Color.rgb(130,40,190),android.graphics.Color.RED).forEach { c ->
                    Box(Modifier.padding(horizontal=5.dp).size(22.dp).background(Color(c),RoundedCornerShape(50)).clickable { textColor=c; syncSelectedStyle(color=c) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick={strokes=emptyList();activeStroke=emptyList();signatureDialog=true}) { Icon(Icons.Default.Draw,null);Text("Sign") }
            }
            Text("Tap the page to place new text. Drag text or a signature to reposition it.",color=Muted,fontSize=10.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick={
                    if(selected in overlays.indices && overlays[selected].signaturePath==null) {
                        val list=overlays.toMutableList(); list[selected]=list[selected].copy(text=draft,fontSize=fontSize,fontKey=fontKey,colorArgb=textColor); overlays=list
                        onMessage("Selected text updated.")
                    } else onMessage("Select a text item to update, or tap the page to add one.")
                },modifier=Modifier.weight(1f)) { Text("Update selected") }
                OutlinedButton(onClick={
                    if(selected in overlays.indices) { overlays=overlays.toMutableList().also{it.removeAt(selected)};selected=-1 }
                    else if(overlays.isNotEmpty()) overlays=overlays.dropLast(1)
                },enabled=overlays.isNotEmpty(),modifier=Modifier.weight(1f)) { Icon(Icons.Default.Delete,null);Text("Remove") }
            }
            Button(onClick={if(overlays.isEmpty()) onMessage("Add text or a signature first.") else onSave(overlays)},
                modifier=Modifier.fillMaxWidth(),enabled=file!=null && bitmap!=null && overlays.isNotEmpty()) { Icon(Icons.Default.Save,null);Text("Save edited PDF copy") }
        } else Text("Read-only preview. Open Edit to add text and signatures.",color=Muted,fontSize=12.sp)
    }
    if(signatureDialog) AlertDialog(
        onDismissRequest={signatureDialog=false},
        title={Text("Draw signature",color=Color.White)},
        text={
            Column {
                Text("Draw with your finger. The PNG background stays transparent.",color=Muted,fontSize=11.sp)
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(160.dp).background(Color.White,RoundedCornerShape(8.dp))
                     .pointerInput(Unit) {
                        detectDragGestures(onDragStart={activeStroke=listOf(it)},onDrag={c,_->activeStroke=activeStroke+c.position},
                            onDragEnd={if(activeStroke.isNotEmpty()){strokes=strokes+listOf(activeStroke);activeStroke=emptyList()}})
                    }) {
                    padSize=size
                    (strokes+listOf(activeStroke)).forEach { pts->pts.zipWithNext().forEach{(p1,p2)->drawLine(Color.Black,p1,p2,strokeWidth=4.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)} }
                }
                TextButton(onClick={strokes=emptyList();activeStroke=emptyList()}) { Text("Clear signature") }
            }
        },
        confirmButton={TextButton(onClick={
            if(strokes.isEmpty()||padSize.width<=0f) onMessage("Draw your signature first.")
            else runCatching {
                val bmp=Bitmap.createBitmap(padSize.width.toInt().coerceAtLeast(1),padSize.height.toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bmp)
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.BLACK;style=android.graphics.Paint.Style.STROKE;strokeWidth=4f;strokeCap=android.graphics.Paint.Cap.ROUND;strokeJoin=android.graphics.Paint.Join.ROUND}
                strokes.forEach{pts->val path=android.graphics.Path();if(pts.size==1)canvas.drawCircle(pts[0].x,pts[0].y,2f,paint) else {path.moveTo(pts.first().x,pts.first().y);pts.drop(1).forEach{path.lineTo(it.x,it.y)};canvas.drawPath(path,paint)}}
                val dest=File(context.cacheDir,"xdocs_signature_${System.currentTimeMillis()}.png")
                FileOutputStream(dest).use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
                overlays=overlays+PdfTextOverlay(pageIndex,.08f,.82f,"",12f,signaturePath=dest.absolutePath);selected=overlays.lastIndex
                signatureDialog=false;onMessage("Transparent signature added. Drag it into position.")
            }.onFailure{onMessage("Signature creation failed: ${it.message}")}
        }){Text("Use signature")}},
        dismissButton={TextButton(onClick={signatureDialog=false}){Text("Cancel")}},
        containerColor=Color(0xFF1A1024)
    )
}

@Composable
private fun ImageWorkspace(file: File?, onBack: () -> Unit, onSave: (List<PdfTextOverlay>) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var draft by remember(file) { mutableStateOf("") }
    var fontSize by remember(file) { mutableFloatStateOf(28f) }
    var fontKey by remember(file) { mutableStateOf("sans") }
    var textColor by remember(file) { mutableIntStateOf(android.graphics.Color.BLACK) }
    var overlays by remember(file) { mutableStateOf(emptyList<PdfTextOverlay>()) }
    var selected by remember(file) { mutableIntStateOf(-1) }
    var signatureDialog by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<List<androidx.compose.ui.geometry.Offset>>>(emptyList()) }
    var activeStroke by remember { mutableStateOf(emptyList<androidx.compose.ui.geometry.Offset>()) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun syncSelectedImageStyle(size: Float = fontSize, key: String = fontKey, color: Int = textColor) {
        if (selected in overlays.indices && overlays[selected].signaturePath == null) {
            val copy = overlays.toMutableList()
            copy[selected] = copy[selected].copy(fontSize = size, fontKey = key, colorArgb = color)
            overlays = copy
        }
    }

    LaunchedEffect(file) {
        bitmap?.recycle(); bitmap = null
        if (file != null) withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(file, 2400) }
        }.onSuccess { bitmap = it }.onFailure { onMessage("Could not open image: ${it.message}") }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
            Column(Modifier.weight(1f)) {
                Text("KAGE IMAGE STUDIO", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(file?.name ?: "No image selected", color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            Text("${overlays.size} items", color = Accent, fontSize = 11.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF090711), RoundedCornerShape(14.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val scale = minOf(maxWidth.value / image.width.coerceAtLeast(1), maxHeight.value / image.height.coerceAtLeast(1))
                val dw = (image.width * scale).dp
                val dh = (image.height * scale).dp
                val widthPx = with(LocalDensity.current) { dw.toPx() }
                val heightPx = with(LocalDensity.current) { dh.toPx() }
                val fontScale = LocalDensity.current.fontScale
                Box(Modifier.width(dw).height(dh)) {
                    Image(image.asImageBitmap(), contentDescription = "Image being edited", modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { p ->
                            if (draft.isNotBlank()) {
                                overlays = overlays + PdfTextOverlay(0, (p.x / size.width).coerceIn(0f,1f), (p.y / size.height).coerceIn(0f,1f), draft, fontSize, fontKey, textColor)
                                selected = overlays.lastIndex
                                draft = ""
                                onMessage("Text added. Drag it to position it.")
                            } else onMessage("Enter text first, then tap the image.")
                        }
                    })
                    overlays.forEachIndexed { index, item ->
                        if (item.signaturePath == null) {
                            Text(item.text, color = Color(item.colorArgb),
                                fontSize = (item.fontSize * dw.value / image.width.coerceAtLeast(1) / fontScale).sp,
                                fontWeight = if (item.fontKey.endsWith("bold")) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = when {
                                    item.fontKey.startsWith("serif") -> androidx.compose.ui.text.font.FontFamily.Serif
                                    item.fontKey == "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
                                    else -> androidx.compose.ui.text.font.FontFamily.SansSerif
                                },
                                modifier = Modifier.offset(x = (item.x * dw.value).dp, y = (item.y * dh.value).dp)
                                    .graphicsLayer { translationX = -size.width / 2f }
                                    .background(if (selected == index) Color(0x447C3AED) else Color.Transparent)
                                    .pointerInput(index, widthPx, heightPx) {
                                        detectDragGestures(onDragStart = { selected = index }, onDrag = { change, delta ->
                                            change.consume()
                                            val copy = overlays.toMutableList()
                                            val old = copy[index]
                                            copy[index] = old.copy(
                                                x = (old.x + delta.x / widthPx.coerceAtLeast(1f)).coerceIn(0f,1f),
                                                y = (old.y + delta.y / heightPx.coerceAtLeast(1f)).coerceIn(0f,1f)
                                            )
                                            overlays = copy
                                        })
                                    }
                                    .clickable { selected = index; draft = item.text; fontSize = item.fontSize; fontKey = item.fontKey; textColor = item.colorArgb }
                            )
                        } else {
                            val sig = remember(item.signaturePath) { BitmapFactory.decodeFile(item.signaturePath) }
                            if (sig != null) {
                                val sigWidthDp = dw.value * .28f
                                val sigHeightDp = sigWidthDp * sig.height.toFloat() / sig.width.coerceAtLeast(1)
                                Image(sig.asImageBitmap(), contentDescription = "Signature",
                                modifier = Modifier.offset(x = (item.x * dw.value).dp, y = (item.y * dh.value).dp)
                                    .width(sigWidthDp.dp).height(sigHeightDp.dp)
                                    .pointerInput(index, widthPx, heightPx) {
                                        detectDragGestures(onDragStart = { selected = index }, onDrag = { change, delta ->
                                            change.consume()
                                            val copy = overlays.toMutableList()
                                            val old = copy[index]
                                            copy[index] = old.copy(
                                                x = (old.x + delta.x / widthPx.coerceAtLeast(1f)).coerceIn(0f,1f),
                                                y = (old.y + delta.y / heightPx.coerceAtLeast(1f)).coerceIn(0f,1f)
                                            )
                                            overlays = copy
                                        })
                                    }.clickable { selected = index }
                            )
                            }
                        }
                    }
                }
            } else Text("Loading image…", color = Muted)
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF191020), RoundedCornerShape(14.dp)).padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Text to add or edit") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fontSize.toInt()} px", color = Color.White, fontSize = 12.sp)
                Slider(value = fontSize, onValueChange = { fontSize = it; syncSelectedImageStyle(size = it) }, valueRange = 10f..100f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Colour", color = Muted, fontSize = 11.sp)
                listOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE, android.graphics.Color.rgb(35,75,190), android.graphics.Color.rgb(130,40,190), android.graphics.Color.RED).forEach { color ->
                    Box(Modifier.padding(horizontal = 4.dp).size(22.dp).background(Color(color), RoundedCornerShape(50)).clickable { textColor = color; syncSelectedImageStyle(color = color) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { strokes = emptyList(); activeStroke = emptyList(); signatureDialog = true }) { Icon(Icons.Default.Draw, null); Text("Sign") }
            }
            Text("Tap the image to place text. Drag text or a signature to move it.", color = Muted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    if (selected in overlays.indices && overlays[selected].signaturePath == null) {
                        val copy = overlays.toMutableList()
                        copy[selected] = copy[selected].copy(text = draft, fontSize = fontSize, fontKey = fontKey, colorArgb = textColor)
                        overlays = copy
                    } else onMessage("Select a text item to update it.")
                }, modifier = Modifier.weight(1f)) { Text("Update selected") }
                OutlinedButton(onClick = {
                    if (selected in overlays.indices) { overlays = overlays.toMutableList().also { it.removeAt(selected) }; selected = -1 }
                }, enabled = overlays.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Text("Remove") }
            }
            Button(onClick = { if (overlays.isEmpty()) onMessage("Add text or a signature first.") else onSave(overlays) },
                modifier = Modifier.fillMaxWidth(), enabled = bitmap != null && overlays.isNotEmpty()) { Icon(Icons.Default.Save, null); Text("Save edited image copy") }
        }
    }
    if (signatureDialog) AlertDialog(
        onDismissRequest = { signatureDialog = false },
        title = { Text("Draw signature", color = Color.White) },
        text = {
            Column {
                Text("Draw with your finger. You can make multiple strokes.", color = Muted, fontSize = 11.sp)
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(160.dp).background(Color.White, RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { activeStroke = listOf(it) }, onDrag = { change, _ ->
                            activeStroke = activeStroke + change.position
                        }, onDragEnd = {
                            if (activeStroke.isNotEmpty()) { strokes = strokes + listOf(activeStroke); activeStroke = emptyList() }
                        })
                    }) {
                    padSize = size
                    (strokes + listOf(activeStroke)).forEach { points ->
                        if (points.size == 1) drawCircle(Color.Black, 2.dp.toPx(), points.first())
                        else if (points.size > 1) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, Color.Black, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                    }
                }
                TextButton(onClick = { strokes = emptyList(); activeStroke = emptyList() }) { Text("Clear") }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (strokes.isEmpty() || padSize.width <= 0f) onMessage("Draw your signature first.")
            else runCatching {
                val out = Bitmap.createBitmap(padSize.width.toInt().coerceAtLeast(1), padSize.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(out)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                }
                strokes.forEach { points ->
                    if (points.size == 1) canvas.drawCircle(points.first().x, points.first().y, 2f, paint)
                    else {
                        val path = android.graphics.Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                val target = File(context.cacheDir, "xdocs_image_signature_${System.currentTimeMillis()}.png")
                FileOutputStream(target).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.recycle()
                overlays = overlays + PdfTextOverlay(0, .5f, .75f, "", 12f, signaturePath = target.absolutePath)
                selected = overlays.lastIndex
                signatureDialog = false
                onMessage("Signature added. Drag it into position.")
            }.onFailure { onMessage("Could not create signature: ${it.message}") }
        }) { Text("Use signature") } },
        dismissButton = { TextButton(onClick = { signatureDialog = false }) { Text("Cancel") } },
        containerColor = Color(0xFF1A1024)
    )
}

private fun renderImageWithOverlays(source: File, overlays: List<PdfTextOverlay>, directory: File): File {
    val original = decodeSampledBitmap(source, 2400)
    val outputBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(outputBitmap)
    canvas.drawBitmap(original, 0f, 0f, null)
    overlays.forEach { item ->
        if (item.signaturePath != null) {
            val signature = BitmapFactory.decodeFile(item.signaturePath) ?: return@forEach
            val width = original.width * .28f
            val height = width * signature.height.toFloat() / signature.width.coerceAtLeast(1)
            val left = (item.x.coerceIn(0f,1f) * original.width - width / 2f).coerceIn(0f,(original.width-width).coerceAtLeast(0f))
            val top = (item.y.coerceIn(0f,1f) * original.height).coerceIn(0f,(original.height-height).coerceAtLeast(0f))
            canvas.drawBitmap(signature, null, android.graphics.RectF(left, top, left + width, top + height), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
            signature.recycle()
        } else {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = item.colorArgb
                textSize = item.fontSize.coerceIn(10f,100f)
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = when (item.fontKey) {
                    "serif" -> android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                    "serif_bold" -> android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
                    "mono" -> android.graphics.Typeface.MONOSPACE
                    "sans_bold" -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    else -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                }
            }
            item.text.split('\n').take(40).forEachIndexed { line, text ->
                canvas.drawText(text.take(2000), item.x.coerceIn(0f,1f) * original.width,
                    item.y.coerceIn(0f,1f) * original.height + paint.textSize * (line + 1.0f), paint)
            }
        }
    }
    original.recycle()
    val output = uniqueFile(directory, "${source.nameWithoutExtension}_edited.png")
    FileOutputStream(output).use { outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    outputBitmap.recycle()
    return output
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
    require(overlays.isNotEmpty()) { "Add at least one item." }
    val output=uniqueFile(directory,"${source.nameWithoutExtension}_edited.pdf")
    PDDocument.load(source).use { doc ->
        overlays.forEach { item ->
            require(item.page in 0 until doc.numberOfPages) { "Page ${item.page+1} does not exist." }
            val page=doc.getPage(item.page)
            if(item.signaturePath!=null) {
                val bmp=BitmapFactory.decodeFile(item.signaturePath) ?: error("Could not load signature.")
                val image=LosslessFactory.createFromImage(doc,bmp)
                val width=page.mediaBox.width*.28f
                val x=(item.x.coerceIn(0f,1f)*page.mediaBox.width-width/2f).coerceIn(0f,(page.mediaBox.width-width).coerceAtLeast(0f))
                val top=item.y.coerceIn(0f,1f)*page.mediaBox.height
                val height=width*bmp.height.toFloat()/bmp.width.coerceAtLeast(1)
                PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use{it.drawImage(image,x,(page.mediaBox.height-top-height).coerceAtLeast(0f),width,height)}
                bmp.recycle()
            } else {
                val font=when(item.fontKey){"serif"->PDType1Font.TIMES_ROMAN;"serif_bold"->PDType1Font.TIMES_BOLD;"mono"->PDType1Font.COURIER;"sans_bold"->PDType1Font.HELVETICA_BOLD;else->PDType1Font.HELVETICA}
                val cleanLines=item.text.split('\n').take(40).map { it.filter { ch -> ch.code in 32..255 }.take(2000) }
                val measuredWidth=cleanLines.maxOfOrNull { line -> runCatching { font.getStringWidth(line)*item.fontSize/1000f }.getOrDefault(0f) } ?: 0f
                val x=(item.x.coerceIn(0f,1f)*page.mediaBox.width-measuredWidth/2f).coerceIn(0f,(page.mediaBox.width-measuredWidth).coerceAtLeast(0f))
                val y=(page.mediaBox.height-item.y.coerceIn(0f,1f)*page.mediaBox.height-item.fontSize).coerceAtLeast(2f)
                PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true).use{stream->
                    stream.beginText();stream.setFont(font,item.fontSize.coerceIn(6f,48f))
                    stream.setNonStrokingColor(android.graphics.Color.red(item.colorArgb),android.graphics.Color.green(item.colorArgb),android.graphics.Color.blue(item.colorArgb))
                    stream.newLineAtOffset(x,y)
                    cleanLines.forEachIndexed{index,line->if(index>0)stream.newLineAtOffset(0f,-item.fontSize*1.2f);stream.showText(line)}
                    stream.endText()
                }
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