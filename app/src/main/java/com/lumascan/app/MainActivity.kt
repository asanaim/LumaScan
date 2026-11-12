package com.lumascan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

enum class OcrScript(
    val label: String,
    val tessCode: String,
    val trainedDataFile: String?,
    val isBuiltInDefault: Boolean,
    val approxSizeMb: String
) {
    LATIN("English", "eng", null, true, "Built-in"),
    BANG_ENG("Bangla + English", "ben+eng", "ben.traineddata", true, "Built-in"),
    DEVANAGARI_ENG("Devanagari + English", "hin+eng", "hin.traineddata", false, "1.8 MB"),
    CHINESE_ENG("Chinese + English", "chi_sim+eng", "chi_sim.traineddata", false, "2.4 MB"),
    JAPANESE_ENG("Japanese + English", "jpn+eng", "jpn.traineddata", false, "2.4 MB"),
    KOREAN_ENG("Korean + English", "kor+eng", "kor.traineddata", false, "1.6 MB")
}

data class ScannedDoc(
    val id: Long,
    val title: String,
    val pdfUri: Uri?,
    val pageCount: Int,
    val date: String,
    val scriptLabel: String,
    val ocrText: String,
    val csvTable: String = "",
    val detectedPhone: String? = null,
    val detectedEmail: String? = null,
    val isRedacted: Boolean = false,
    val isIdStitch: Boolean = false,
    val isSearchablePdf: Boolean = false,
    val isOcrProcessing: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val tessMutex = Mutex()
    private var cachedTessApi: TessBaseAPI? = null
    private var cachedTessLang: String? = null
    private val dbHelper by lazy { LumaScanDbHelper(this) }

    private fun getOrInitTesseract(langCode: String): TessBaseAPI? {
        if (cachedTessApi != null && cachedTessLang == langCode) {
            return cachedTessApi
        }
        cachedTessApi?.recycle()
        cachedTessApi = null

        val tessDataParentDir = prepareDefaultTessDataFiles() ?: return null
        val tess = TessBaseAPI()
        return if (tess.init(tessDataParentDir, langCode)) {
            cachedTessApi = tess
            cachedTessLang = langCode
            tess
        } else {
            tess.recycle()
            null
        }
    }

    private fun isScriptInstalled(script: OcrScript): Boolean {
        if (script.isBuiltInDefault) return true
        val fileName = script.trainedDataFile ?: return true
        val file = File(File(filesDir, "tessdata"), fileName)
        return file.exists() && file.length() > 100_000L
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scannerOptions = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(RESULT_FORMAT_JPEG, RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(scannerOptions)

        setContent {
            val appleBlue = Color(0xFF0A84FF)
            val appleGreen = Color(0xFF30D158)
            val appleOrange = Color(0xFFFF9F0A)
            val appleRed = Color(0xFFFF3B30)
            val appleBg = Color(0xFFF2F2F7)

            var selectedScript by remember { mutableStateOf(OcrScript.BANG_ENG) }
            var idCardStitchMode by remember { mutableStateOf(false) }
            var redactSensitiveMode by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }

            val installedScripts = remember {
                mutableStateListOf<OcrScript>().apply {
                    OcrScript.entries.forEach { if (isScriptInstalled(it)) add(it) }
                }
            }
            var showLanguageManager by remember { mutableStateOf(false) }
            var scriptToRemoveConfirm by remember { mutableStateOf<OcrScript?>(null) }
            var downloadingScript by remember { mutableStateOf<OcrScript?>(null) }

            val scannedDocs = remember { mutableStateListOf<ScannedDoc>() }
            var activeOcrDoc by remember { mutableStateOf<ScannedDoc?>(null) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    prepareDefaultTessDataFiles()
                    val saved = dbHelper.getAllDocs()
                    withContext(Dispatchers.Main) {
                        scannedDocs.clear()
                        scannedDocs.addAll(saved)
                    }
                }
            }

            val filteredDocs = remember(scannedDocs.size, searchQuery, scannedDocs.toList()) {
                if (searchQuery.isBlank()) {
                    scannedDocs
                } else {
                    scannedDocs.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                            it.ocrText.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            val galleryLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
            ) { uris ->
                if (uris.isNotEmpty()) {
                    handlePageUris(
                        uris = uris,
                        currentScript = selectedScript,
                        stitchId = idCardStitchMode,
                        redactPii = redactSensitiveMode,
                        scannedDocs = scannedDocs,
                        rawPdfUri = null
                    )
                }
            }

            val scannerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                    val rawPdf = scanResult?.pdf
                    val pages = scanResult?.pages.orEmpty()
                    if (pages.isNotEmpty()) {
                        handlePageUris(
                            uris = pages.map { it.imageUri },
                            currentScript = selectedScript,
                            stitchId = idCardStitchMode,
                            redactPii = redactSensitiveMode,
                            scannedDocs = scannedDocs,
                            rawPdfUri = rawPdf?.uri
                        )
                    }
                }
            }

            fun startScan() {
                scanner.getStartScanIntent(this@MainActivity)
                    .addOnSuccessListener { intentSender ->
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this@MainActivity,
                            "Scanner unavailable: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = appleBg) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "NuvioScan",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "Fast Offline Scanner • Searchable PDF • Multi-Script",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Hybrid Cupertino Language Bar:
                    // - English & Bangla+English are built-in by default
                    // - Downloaded languages appear here; long-press any downloaded language to remove it
                    // - "+ Languages" button opens the on-demand Downloader / Manager sheet
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        installedScripts.forEach { script ->
                            val isSelected = selectedScript == script
                            Surface(
                                color = if (isSelected) appleBlue else Color.White,
                                shape = RoundedCornerShape(20.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .combinedClickable(
                                        onClick = { selectedScript = script },
                                        onLongClick = {
                                            if (!script.isBuiltInDefault) {
                                                scriptToRemoveConfirm = script
                                            } else {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "${script.label} is a built-in default language.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    )
                            ) {
                                Text(
                                    text = script.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) Color.White else Color.Black,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { showLanguageManager = true },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(35.dp)
                        ) {
                            Text("+ Languages", fontSize = 12.sp, color = appleBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Clean Feature Chips (Removed the PDF App Link chip from UI as requested)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = idCardStitchMode,
                            onClick = { idCardStitchMode = !idCardStitchMode },
                            label = { Text("🪪 ID Card 2-in-1 Page", fontSize = 12.sp) },
                            shape = RoundedCornerShape(18.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = appleOrange,
                                selectedLabelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = redactSensitiveMode,
                            onClick = { redactSensitiveMode = !redactSensitiveMode },
                            label = { Text("🔒 Auto-Redact PII", fontSize = 12.sp) },
                            shape = RoundedCornerShape(18.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.Black,
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { startScan() },
                            colors = ButtonDefaults.buttonColors(containerColor = appleBlue),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Text(
                                text = "Camera Scan",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("Import Gallery", fontSize = 14.sp, color = appleBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search inside scanned documents & OCR text...", fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (filteredDocs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (searchQuery.isBlank())
                                    "Tap 'Camera Scan' or 'Import Gallery' to create searchable PDFs."
                                else "No scans found matching \"$searchQuery\".",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(filteredDocs, key = { it.id }) { doc ->
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = doc.title,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 15.sp,
                                                    color = Color.Black
                                                )
                                                Text(
                                                    text = "${doc.pageCount} page(s) • ${doc.scriptLabel} • ${doc.date}",
                                                    fontSize = 12.sp,
                                                    color = Color.Gray
                                                )
                                                if (doc.isSearchablePdf) {
                                                    val badgeText = buildString {
                                                        append("✓ Searchable PDF")
                                                        if (doc.isIdStitch) append(" • 2-in-1 ID")
                                                        if (doc.isRedacted) append(" • PII Redacted")
                                                    }
                                                    Text(
                                                        text = badgeText,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = appleGreen
                                                    )
                                                }
                                            }
                                            TextButton(onClick = { doc.pdfUri?.let { sharePdf(it) } }) {
                                                Text("Share PDF", color = appleBlue, fontWeight = FontWeight.SemiBold)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        if (doc.isOcrProcessing) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = appleBlue
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Extracting text & optimizing PDF size...",
                                                    fontSize = 13.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                        } else if (doc.ocrText.isNotBlank()) {
                                            Surface(
                                                color = Color(0xFFF2F2F7),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(
                                                        text = doc.ocrText,
                                                        fontSize = 13.sp,
                                                        color = Color.DarkGray,
                                                        maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Row(
                                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        OutlinedButton(
                                                            onClick = { activeOcrDoc = doc },
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("Full OCR", fontSize = 12.sp, color = appleBlue)
                                                        }
                                                        Button(
                                                            onClick = { copyToClipboard(doc.ocrText) },
                                                            colors = ButtonDefaults.buttonColors(containerColor = appleGreen),
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("Copy Text", fontSize = 12.sp, color = Color.White)
                                                        }
                                                        if (doc.csvTable.isNotBlank()) {
                                                            OutlinedButton(
                                                                onClick = { sharePlainText(doc.csvTable, "Export Table CSV") },
                                                                shape = RoundedCornerShape(8.dp),
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                            ) {
                                                                Text("Table CSV", fontSize = 12.sp, color = appleBlue)
                                                            }
                                                        }
                                                        if (doc.detectedPhone != null || doc.detectedEmail != null) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    saveBusinessCardContact(
                                                                        name = doc.title.removeSuffix(".pdf"),
                                                                        phone = doc.detectedPhone,
                                                                        email = doc.detectedEmail
                                                                    )
                                                                },
                                                                shape = RoundedCornerShape(8.dp),
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                            ) {
                                                                Text("Save Contact", fontSize = 12.sp, color = appleOrange)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Hybrid Cupertino Language Manager Dialog (Download / Remove optional scripts)
                if (showLanguageManager) {
                    AlertDialog(
                        onDismissRequest = { if (downloadingScript == null) showLanguageManager = false },
                        title = {
                            Text("Manage OCR Languages", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "English and Bangla are built-in by default. Download additional offline scripts when needed, or remove them (or long-press their chip) to free space.",
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                                OcrScript.entries.forEach { script ->
                                    val isInstalled = installedScripts.contains(script)
                                    val isDownloading = downloadingScript == script
                                    Surface(
                                        color = Color(0xFFF2F2F7),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(script.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                Text(script.approxSizeMb, fontSize = 12.sp, color = Color.Gray)
                                            }
                                            if (script.isBuiltInDefault) {
                                                Text("Default", fontSize = 12.sp, color = appleGreen, fontWeight = FontWeight.SemiBold)
                                            } else if (isDownloading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = appleBlue
                                                )
                                            } else if (isInstalled) {
                                                TextButton(
                                                    onClick = {
                                                        deleteScriptModel(script)
                                                        installedScripts.remove(script)
                                                        if (selectedScript == script) {
                                                            selectedScript = OcrScript.BANG_ENG
                                                        }
                                                    }
                                                ) {
                                                    Text("Remove", color = appleRed, fontWeight = FontWeight.SemiBold)
                                                }
                                            } else {
                                                Button(
                                                    onClick = {
                                                        downloadingScript = script
                                                        downloadScriptModel(script) { success ->
                                                            downloadingScript = null
                                                            if (success) {
                                                                if (!installedScripts.contains(script)) {
                                                                    installedScripts.add(script)
                                                                }
                                                                selectedScript = script
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = appleBlue),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Download", fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { if (downloadingScript == null) showLanguageManager = false }
                            ) {
                                Text("Done", color = appleBlue, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }

                // Long-press quick removal confirmation dialog
                scriptToRemoveConfirm?.let { script ->
                    AlertDialog(
                        onDismissRequest = { scriptToRemoveConfirm = null },
                        title = { Text("Remove ${script.label}?", fontWeight = FontWeight.Bold) },
                        text = {
                            Text("This will delete the offline ${script.label} recognition model (${script.approxSizeMb}) from your device. You can re-download it anytime.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    deleteScriptModel(script)
                                    installedScripts.remove(script)
                                    if (selectedScript == script) {
                                        selectedScript = OcrScript.BANG_ENG
                                    }
                                    scriptToRemoveConfirm = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = appleRed)
                            ) {
                                Text("Remove")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { scriptToRemoveConfirm = null }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }

                activeOcrDoc?.let { doc ->
                    AlertDialog(
                        onDismissRequest = { activeOcrDoc = null },
                        title = {
                            Text(doc.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        },
                        text = {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 380.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(text = doc.ocrText, fontSize = 14.sp, color = Color.Black)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    copyToClipboard(doc.ocrText)
                                    activeOcrDoc = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = appleBlue)
                            ) {
                                Text("Copy All")
                            }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { sharePlainText(doc.ocrText, "Share Extracted OCR Text") }) {
                                    Text("Share Text", color = appleBlue)
                                }
                                TextButton(onClick = { activeOcrDoc = null }) {
                                    Text("Close", color = Color.Gray)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun downloadScriptModel(script: OcrScript, onResult: (Boolean) -> Unit) {
        val fileName = script.trainedDataFile
        if (fileName == null) {
            onResult(true)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val tessDir = File(filesDir, "tessdata")
                if (!tessDir.exists()) tessDir.mkdirs()
                val targetFile = File(tessDir, fileName)
                val tempFile = File(tessDir, "$fileName.tmp")

                val url = URL("https://github.com/tesseract-ocr/tessdata_fast/raw/main/$fileName")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() > 100_000L) {
                    tempFile.renameTo(targetFile)
                    true
                } else {
                    tempFile.delete()
                    false
                }
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "${script.label} downloaded for offline use", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Download failed. Check internet connection.", Toast.LENGTH_SHORT).show()
                }
                onResult(success)
            }
        }
    }

    private fun deleteScriptModel(script: OcrScript) {
        val fileName = script.trainedDataFile ?: return
        val file = File(File(filesDir, "tessdata"), fileName)
        if (file.exists()) {
            file.delete()
        }
        Toast.makeText(this, "${script.label} removed", Toast.LENGTH_SHORT).show()
    }

    private fun handlePageUris(
        uris: List<Uri>,
        currentScript: OcrScript,
        stitchId: Boolean,
        redactPii: Boolean,
        scannedDocs: MutableList<ScannedDoc>,
        rawPdfUri: Uri?
    ) {
        val docId = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date())
        val tempName = "NuvioScan_${docId % 10000}.pdf"

        val placeholderDoc = ScannedDoc(
            id = docId,
            title = tempName,
            pdfUri = rawPdfUri,
            pageCount = uris.size,
            date = timestamp,
            scriptLabel = currentScript.label,
            ocrText = "",
            isRedacted = redactPii,
            isIdStitch = stitchId,
            isSearchablePdf = false,
            isOcrProcessing = true
        )
        scannedDocs.add(0, placeholderDoc)

        processPagesFast(
            pageUris = uris,
            script = currentScript,
            docId = docId,
            stitchTwoPages = stitchId,
            redactPii = redactPii
        ) { smartPdfName, extractedText, csvContent, searchablePdfUri, pageCount ->
            val finalPdfUri = searchablePdfUri ?: rawPdfUri
            if (finalPdfUri != null) {
                savePdfToDownloads(finalPdfUri, smartPdfName)
            }
            val baseSmartName = smartPdfName.removeSuffix(".pdf")
            if (extractedText.isNotBlank()) {
                saveTextToDownloads(extractedText, "$baseSmartName.txt", "text/plain")
            }
            if (csvContent.isNotBlank()) {
                saveTextToDownloads(csvContent, "${baseSmartName}_table.csv", "text/csv")
            }

            val phone = extractPhoneNumber(extractedText)
            val email = extractEmailAddress(extractedText)

            val updatedDoc = ScannedDoc(
                id = docId,
                title = smartPdfName,
                pdfUri = finalPdfUri,
                pageCount = pageCount,
                date = timestamp,
                scriptLabel = currentScript.label,
                ocrText = extractedText,
                csvTable = csvContent,
                detectedPhone = phone,
                detectedEmail = email,
                isRedacted = redactPii,
                isIdStitch = stitchId,
                isSearchablePdf = searchablePdfUri != null && extractedText.isNotBlank(),
                isOcrProcessing = false
            )

            val index = scannedDocs.indexOfFirst { item -> item.id == docId }
            if (index != -1) {
                scannedDocs[index] = updatedDoc
            }

            lifecycleScope.launch(Dispatchers.IO) {
                dbHelper.insertOrUpdateDoc(updatedDoc)
            }
        }
    }

    private fun processPagesFast(
        pageUris: List<Uri>,
        script: OcrScript,
        docId: Long,
        stitchTwoPages: Boolean,
        redactPii: Boolean,
        onComplete: (
            smartPdfName: String,
            extractedText: String,
            csvContent: String,
            searchablePdfUri: Uri?,
            finalPageCount: Int
        ) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val rawBitmaps = pageUris.mapNotNull { decodeMutableBitmap(it) }
            val processedBitmaps = if (stitchTwoPages && rawBitmaps.size >= 2) {
                val stitched = SearchablePdfGenerator.stitchTwoPagesOntoA4(rawBitmaps[0], rawBitmaps[1])
                rawBitmaps[0].recycle()
                rawBitmaps[1].recycle()
                listOf(stitched) + rawBitmaps.drop(2)
            } else {
                rawBitmaps
            }

            val pageDataList = if (script == OcrScript.LATIN) {
                extractMlKitParallelFromBitmaps(processedBitmaps)
            } else {
                extractTesseractFromBitmaps(processedBitmaps, script.tessCode)
            }

            val smartBaseName = generateSmartFileName(pageDataList, docId)
            val smartPdfName = "$smartBaseName.pdf"

            val lineBreak = "\n"
            val doubleLineBreak = "\n\n"
            val combinedText = pageDataList.mapIndexedNotNull { index, page ->
                val rawContent = page.fullPageText.trim()
                val content = if (redactPii) {
                    rawContent.split(Regex("\\s+")).joinToString(" ") { token ->
                        if (SearchablePdfGenerator.isSensitiveToken(token)) "[REDACTED]" else token
                    }
                } else {
                    rawContent
                }
                if (content.isNotEmpty()) {
                    if (pageDataList.size == 1) content else "--- Page ${index + 1} ---" + lineBreak + content
                } else null
            }.joinToString(doubleLineBreak)

            val csvContent = SearchablePdfGenerator.convertWordsToCsv(pageDataList)

            val searchablePdfUri = SearchablePdfGenerator.generateSearchablePdf(
                context = this@MainActivity,
                pages = pageDataList,
                outputFileName = smartPdfName,
                redactSensitivePii = redactPii
            )

            pageDataList.forEach { it.bitmap.recycle() }

            withContext(Dispatchers.Main) {
                onComplete(smartPdfName, combinedText, csvContent, searchablePdfUri, pageDataList.size.coerceAtLeast(1))
            }
        }
    }

    private suspend fun extractMlKitParallelFromBitmaps(
        bitmaps: List<Bitmap>
    ): List<PageOcrData> = coroutineScope {
        bitmaps.map { bitmap ->
            async(Dispatchers.Default) {
                try {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText = Tasks.await(latinRecognizer.process(inputImage))
                    val wordBoxes = mutableListOf<OcrWordBox>()

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                val rect = element.boundingBox
                                if (rect != null && element.text.isNotBlank()) {
                                    wordBoxes.add(OcrWordBox(element.text, rect))
                                }
                            }
                        }
                    }
                    PageOcrData(bitmap, wordBoxes, visionText.text)
                } catch (e: Exception) {
                    PageOcrData(bitmap, emptyList(), "")
                }
            }
        }.awaitAll()
    }

    private suspend fun extractTesseractFromBitmaps(
        bitmaps: List<Bitmap>,
        langCode: String
    ): List<PageOcrData> = tessMutex.withLock {
        val results = mutableListOf<PageOcrData>()
        val tess = getOrInitTesseract(langCode)

        for (bitmap in bitmaps) {
            if (tess == null) {
                results.add(PageOcrData(bitmap, emptyList(), ""))
                continue
            }
            try {
                tess.setImage(bitmap)
                val fullText = tess.utF8Text?.trim().orEmpty()
                val wordBoxes = mutableListOf<OcrWordBox>()

                val iterator = tess.resultIterator
                if (iterator != null) {
                    iterator.begin()
                    do {
                        val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (!word.isNullOrBlank() && rect != null) {
                            wordBoxes.add(OcrWordBox(word, rect))
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                    iterator.delete()
                }
                tess.clear()
                results.add(PageOcrData(bitmap, wordBoxes, fullText))
            } catch (e: Exception) {
                results.add(PageOcrData(bitmap, emptyList(), ""))
            }
        }
        results
    }

    /**
     * Preserves Unicode combining marks (\p{M}) so Bangla vowel signs (া, ি, ী, ু, ূ, ৃ, ে, ৈ, ো, ৌ, ং, ্)
     * remain intact in filenames instead of being replaced with underscores.
     */
    private fun generateSmartFileName(pages: List<PageOcrData>, docId: Long): String {
        val firstLine = pages.firstOrNull()?.fullPageText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.length in 3..45 && it.any { ch -> ch.isLetter() } }

        val sanitized = firstLine
            ?.replace(Regex("[^\\p{L}\\p{M}\\p{N}_-]+"), "_")
            ?.trim('_')
            ?.take(32)

        return if (!sanitized.isNullOrBlank()) {
            "${sanitized}_${docId % 10000}"
        } else {
            "NuvioScan_${docId % 100000}"
        }
    }

    private fun extractPhoneNumber(text: String): String? {
        val match = Regex("(\\+?\\d[\\d -]{7,14}\\d)").find(text)
        return match?.value?.trim()
    }

    private fun extractEmailAddress(text: String): String? {
        val match = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(text)
        return match?.value?.trim()
    }

    private fun saveBusinessCardContact(name: String, phone: String?, email: String?) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        }
        startActivity(intent)
    }

    private fun decodeMutableBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    ?.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun prepareDefaultTessDataFiles(): String? {
        return try {
            val tessDir = File(filesDir, "tessdata")
            if (!tessDir.exists()) tessDir.mkdirs()

            for (name in listOf("ben.traineddata", "eng.traineddata")) {
                val outFile = File(tessDir, name)
                if (!outFile.exists()) {
                    assets.open("tessdata/$name").use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            filesDir.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NuvioScan OCR", text))
        Toast.makeText(this, "OCR text copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun savePdfToDownloads(sourceUri: Uri, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NuvioScan")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { dest ->
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    contentResolver.openOutputStream(dest)?.use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, "Saved $fileName to Downloads/NuvioScan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTextToDownloads(content: String, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NuvioScan")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { dest ->
                contentResolver.openOutputStream(dest)?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun sharePdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Searchable PDF"))
    }

    private fun sharePlainText(text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
    }

    override fun onDestroy() {
        super.onDestroy()
        latinRecognizer.close()
        cachedTessApi?.recycle()
        cachedTessApi = null
    }
}
