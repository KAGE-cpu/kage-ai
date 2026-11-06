package com.kage.xdocs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        val root = File(filesDir, "XDocs").apply { mkdirs() }
        setContent { XDocsApp(root) }
    }
}

private enum class Page { HOME, FILES, SCAN, CONVERT }

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
    var editorDialog by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf("") }
    var pageNumber by remember { mutableStateOf("1") }

    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = (uri.lastPathSegment ?: "document.pdf").substringAfterLast('/').substringAfterLast(':').safeFile()
            val target = File(root, name)
            context.contentResolver.openInputStream(uri).use { input ->
                target.outputStream().use { output -> input?.copyTo(output) ?: error("Cannot read PDF") }
            }
            selected = target; page = Page.FILES; folder = null; refresh++
            message = "Imported ${target.name}"
        }.onFailure { message = "Import failed: ${it.message}" }
    }
    val importImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) runCatching {
            PDDocument().use { doc ->
                uris.forEach { uri ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
                        ?: error("Could not read selected image")
                    val pdfPage = PDPage(PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat()))
                    doc.addPage(pdfPage)
                    val image = LosslessFactory.createFromImage(doc, bitmap)
                    PDPageContentStream(doc, pdfPage).use { it.drawImage(image, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()) }
                    bitmap.recycle()
                }
                val output = File(root, "Images_${System.currentTimeMillis()}.pdf")
                doc.save(output); selected = output; refresh++
                message = "Created ${output.name}"
            }
        }.onFailure { message = "Image conversion failed: ${it.message}" }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = selected
        if (uri != null && source != null) runCatching {
            context.contentResolver.openOutputStream(uri).use { output ->
                source.inputStream().use { input -> input.copyTo(output ?: error("Cannot write file")) }
            }
            message = "PDF exported"
        }.onFailure { message = "Export failed: ${it.message}" }
    }
    val directory = folder?.let { File(root, it) } ?: root
    val entries = remember(refresh, folder) {
        directory.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9AA8FF), background = Color(0xFF090E1C), surface = Color(0xFF151F35))) {
        Scaffold(
            containerColor = Color(0xFF090E1C),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF111A2D)) {
                    listOf(Page.HOME to Icons.Default.Home, Page.FILES to Icons.Default.Folder, Page.SCAN to Icons.Default.CameraAlt, Page.CONVERT to Icons.Default.Transform).forEach { (p, icon) ->
                        NavigationBarItem(selected = page == p, onClick = { page = p; folder = null }, icon = { Icon(icon, null) }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("X DOCS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("Your documents. On your device.", color = Color(0xFF9AA8C5), fontSize = 12.sp)
                    }
                    Icon(Icons.Default.Description, null, tint = Color(0xFF9AA8FF), modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(20.dp))
                when (page) {
                    Page.HOME -> {
                        Text("Welcome back", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("Work with your documents offline.", color = Color(0xFF9AA8C5))
                        Spacer(Modifier.height(18.dp))
                        Action("My Documents", "Browse local files and folders", Icons.Default.FolderOpen) { page = Page.FILES }
                        Action("Import PDF", "Copy a PDF into X DOCS", Icons.Default.UploadFile) { importPdf.launch(arrayOf("application/pdf")) }
                        Action("PDF Editor", "Add text and save an edited copy", Icons.Default.Edit) { if (selected != null) editorDialog = true else message = "Import a PDF first" }
                        Action("Images to PDF", "Create a PDF from selected pictures", Icons.Default.Image) { importImages.launch("image/*") }
                    }
                    Page.FILES -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(folder ?: "My Documents", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { folderDialog = true }) { Icon(Icons.Default.CreateNewFolder, null); Text("Folder") }
                            TextButton(onClick = { importPdf.launch(arrayOf("application/pdf")) }) { Icon(Icons.Default.Add, null); Text("Import") }
                        }
                        if (folder != null) TextButton(onClick = { folder = null }) { Icon(Icons.Default.ArrowBack, null); Text("All documents") }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(entries, key = { it.absolutePath }) { item ->
                                Row(Modifier.fillMaxWidth().background(Color(0xFF151F35), RoundedCornerShape(14.dp)).clickable {
                                    if (item.isDirectory) folder = item.name
                                    else if (item.extension.equals("pdf", true)) { selected = item; editorDialog = true }
                                    else message = item.name
                                }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (item.isDirectory) Icons.Default.Folder else Icons.Default.PictureAsPdf, null, tint = Color(0xFF9AA8FF))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(if (item.isDirectory) "Folder" else "${item.length() / 1024} KB", color = Color(0xFF9AA8C5), fontSize = 12.sp)
                                    }
                                    IconButton(onClick = { if (item.isDirectory) item.deleteRecursively() else item.delete(); refresh++ }) {
                                        Icon(Icons.Default.Delete, null, tint = Color(0xFFFF8795))
                                    }
                                }
                            }
                        }
                    }
                    Page.SCAN -> {
                        Text("Scan documents", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Select photos from your device and turn them into a PDF.", color = Color(0xFF9AA8C5))
                        Spacer(Modifier.height(14.dp))
                        Action("Select images", "Create a PDF from multiple images", Icons.Default.AddPhotoAlternate) { importImages.launch("image/*") }
                        Text("This first build uses the image picker; direct camera capture is not included yet.", color = Color(0xFFFFD28A), fontSize = 12.sp)
                    }
                    Page.CONVERT -> {
                        Text("Convert documents", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Action("Images to PDF", "Combine selected images", Icons.Default.Image) { importImages.launch("image/*") }
                        Action("Export selected PDF", "Save a copy to a location you choose", Icons.Default.FileDownload) {
                            selected?.let { exportPdf.launch(it.name) } ?: run { message = "Select a PDF first" }
                        }
                        Text("PDF merge and PDF-to-image export are not included in this migration build.", color = Color(0xFFFFD28A), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFFB8C6FF), fontSize = 12.sp)
            }
        }
    }
    if (folderDialog) AlertDialog(
        onDismissRequest = { folderDialog = false },
        title = { Text("New folder") },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("Folder name") }) },
        confirmButton = { TextButton(onClick = {
            val clean = folderName.trim().safeFile()
            if (clean.isNotBlank()) File(root, clean).mkdirs()
            folderName = ""; folderDialog = false; refresh++
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Cancel") } }
    )
    if (editorDialog) AlertDialog(
        onDismissRequest = { editorDialog = false },
        title = { Text("Add text to PDF") },
        text = {
            Column {
                Text("Selected: ${selected?.name ?: "None"}")
                OutlinedTextField(overlay, { overlay = it }, label = { Text("Text to add") })
                OutlinedTextField(pageNumber, { pageNumber = it.filter(Char::isDigit) }, label = { Text("Page number") })
                Text("Adds a text overlay at a fixed margin. It does not edit existing PDF text.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            val source = selected
            if (source != null && overlay.isNotBlank()) runCatching {
                PDDocument.load(source).use { doc ->
                    val index = ((pageNumber.toIntOrNull() ?: 1) - 1).coerceIn(0, doc.numberOfPages - 1)
                    val targetPage = doc.getPage(index)
                    PDPageContentStream(doc, targetPage, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 14f)
                        stream.newLineAtOffset(55f, targetPage.mediaBox.height - 70f)
                        stream.showText(overlay)
                        stream.endText()
                    }
                    val output = File(root, "${source.nameWithoutExtension}_edited_${System.currentTimeMillis()}.pdf")
                    doc.save(output); selected = output; refresh++
                    message = "Saved ${output.name}"
                }
            }.onFailure { message = "PDF edit failed: ${it.message}" }
            editorDialog = false
        }) { Text("Save edited copy") } },
        dismissButton = { TextButton(onClick = { editorDialog = false }) { Text("Cancel") } }
    )
}

@Composable
private fun Action(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Color(0xFF151F35), RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color(0xFF9EAEFF), modifier = Modifier.size(27.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF9AA8C5), fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF9AA8C5))
    }
}
private fun String.safeFile(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80).ifBlank { "document.pdf" }
