package com.vellumscan.app

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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import java.text.SimpleDateFormat
import java.util.*

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
    private val dbHelper by lazy { VellumScanDbHelper(this) }

    private fun getOrInitTesseract(langCode: String): TessBaseAPI? {
        LanguageModelManager.ensureDefaultAssetsCopied(this)
        if (cachedTessApi != null && cachedTessLang == langCode) {
            return cachedTessApi
        }
        cachedTessApi?.recycle()
        cachedTessApi = null
        cachedTessLang = null

        val parentDir = filesDir.absolutePath
        val tess = TessBaseAPI()
        return if (tess.init(parentDir, langCode)) {
            cachedTessApi = tess
            cachedTessLang = langCode
            tess
        } else {
            tess.recycle()
            null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

            var selectedScript by remember { mutableStateOf(OcrScript.LATIN) }
            var installedScripts by remember { mutableStateOf(setOf(OcrScript.LATIN, OcrScript.BANG_ENG)) }
            var downloadingScripts by remember { mutableStateOf(mapOf<OcrScript, Float>()) }

            var idCardStitchMode by remember { mutableStateOf(false) }
            var redactSensitiveMode by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }

            val scannedDocs = remember { mutableStateListOf<ScannedDoc>() }
            var activeOcrDoc by remember { mutableStateOf<ScannedDoc?>(null) }
            var showLanguageSheet by remember { mutableStateOf(false) }
            var scriptToRemoveDialog by remember { mutableStateOf<OcrScript?>(null) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    LanguageModelManager.ensureDefaultAssetsCopied(this@MainActivity)
                    val installed = LanguageModelManager.getInstalledScripts(this@MainActivity)
                    val saved = dbHelper.getAllDocs()
                    withContext(Dispatchers.Main) {
                        installedScripts = installed
                        scannedDocs.clear()
                        scannedDocs.addAll(saved)
                    }
                }
            }

            fun triggerLanguageDownload(script: OcrScript) {
                if (downloadingScripts.containsKey(script)) return
                downloadingScripts = downloadingScripts + (script to 0.05f)
                lifecycleScope.launch {
                    val success = LanguageModelManager.downloadScriptModel(
                        context = this@MainActivity,
                        script = script
                    ) { progress ->
                        downloadingScripts = downloadingScripts + (script to progress)
                    }
                    downloadingScripts = downloadingScripts - script
                    if (success) {
                        installedScripts = LanguageModelManager.getInstalledScripts(this@MainActivity)
                        selectedScript = script
                        Toast.makeText(
                            this@MainActivity,
                            "${script.label} ready offline",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Download failed. Check internet connection.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            fun removeLanguagePack(script: OcrScript) {
                if (script.isDefaultBuiltIn) return
                LanguageModelManager.deleteScriptModel(this@MainActivity, script)
                if (cachedTessLang == script.tessCode) {
                    cachedTessApi?.recycle()
                    cachedTessApi = null
                    cachedTessLang = null
                }
                installedScripts = LanguageModelManager.getInstalledScripts(this@MainActivity)
                if (selectedScript == script) {
                    selectedScript = OcrScript.LATIN
                }
                Toast.makeText(this@MainActivity, "Removed ${script.label}", Toast.LENGTH_SHORT).show()
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

                    // Minimalist Cupertino Top Header (No Download Link Icon on UI)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VellumScan",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "Offline Searchable Scanner • Multi-Script OCR",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                        FilledTonalButton(
                            onClick = { showLanguageSheet = true },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White,
                                contentColor = appleBlue
                            )
                        ) {
                            Text("Languages", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Hybrid Cupertino Language Selector Bar:
                    // English & Bangla are default; installed optional languages appear here; "+ Add" opens manager
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OcrScript.entries.filter { installedScripts.contains(it) }.forEach { script ->
                            val isSelected = selectedScript == script
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) appleBlue else Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .combinedClickable(
                                        onClick = { selectedScript = script },
                                        onLongClick = {
                                            if (!script.isDefaultBuiltIn) {
                                                scriptToRemoveDialog = script
                                            }
                                        }
                                    )
                            ) {
                                Text(
                                    text = script.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else Color.Black,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // Compact "+ Languages" pill to download/manage Devanagari, Chinese, Japanese, Korean
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFE5E5EA),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .combinedClickable(onClick = { showLanguageSheet = true })
                        ) {
                            Text(
                                text = "+ Add Script",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = appleBlue,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Clean Feature Toggles Row (No PDF App Link Chip on UI)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                    // Primary Cupertino Action Buttons
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
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("Import Gallery", fontSize = 14.sp, color = appleBlue, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Instant Keyword Search Across Historical Documents
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search inside scanned documents & OCR text...", fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White
                        ),
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

                // Hybrid Cupertino Modal Bottom Sheet: Manage Offline OCR Languages
                if (showLanguageSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showLanguageSheet = false },
                        containerColor = appleBg,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Offline OCR Languages",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Text(
                                text = "English and Bangla are built-in. Download or remove extra scripts anytime.",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    OcrScript.entries.forEachIndexed { index, script ->
                                        val isInstalled = installedScripts.contains(script)
                                        val progress = downloadingScripts[script]

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = script.label,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.Black
                                                )
                                                Text(
                                                    text = if (script.isDefaultBuiltIn) "Default • Built-in Offline"
                                                    else if (isInstalled) "Downloaded • ${script.sizeLabel} Offline"
                                                    else "Available • ${script.sizeLabel}",
                                                    fontSize = 12.sp,
                                                    color = Color.Gray
                                                )
                                            }

                                            if (progress != null) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CircularProgressIndicator(
                                                        progress = { progress },
                                                        modifier = Modifier.size(22.dp),
                                                        strokeWidth = 2.5.dp,
                                                        color = appleBlue
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = appleBlue)
                                                }
                                            } else if (script.isDefaultBuiltIn) {
                                                Text(
                                                    text = "Default",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = appleGreen
                                                )
                                            } else if (isInstalled) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            selectedScript = script
                                                            showLanguageSheet = false
                                                        },
                                                        shape = RoundedCornerShape(16.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                    ) {
                                                        Text("Use", fontSize = 12.sp, color = appleBlue)
                                                    }
                                                    TextButton(
                                                        onClick = { removeLanguagePack(script) },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text("Remove", fontSize = 12.sp, color = appleRed)
                                                    }
                                                }
                                            } else {
                                                Button(
                                                    onClick = { triggerLanguageDownload(script) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = appleBlue),
                                                    shape = RoundedCornerShape(16.dp),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Download", fontSize = 12.sp, color = Color.White)
                                                }
                                            }
                                        }

                                        if (index < OcrScript.entries.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = Color(0xFFE5E5EA)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(28.dp))
                        }
                    }
                }

                // Long-Press Removal Dialog on Main Language Chips
                scriptToRemoveDialog?.let { script ->
                    AlertDialog(
                        onDismissRequest = { scriptToRemoveDialog = null },
                        title = { Text("Remove ${script.label}?", fontWeight = FontWeight.Bold) },
                        text = {
                            Text("This will free ${script.sizeLabel} of offline storage. You can re-download it anytime from Languages.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    removeLanguagePack(script)
                                    scriptToRemoveDialog = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = appleRed)
                            ) {
                                Text("Remove")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { scriptToRemoveDialog = null }) {
                                Text("Cancel", color = appleBlue)
                            }
                        }
                    )
                }

                // Full OCR Viewer Modal
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
        val tempName = "VellumScan_${docId % 10000}.pdf"

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
                extractMlKitLatinParallel(processedBitmaps)
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

    private suspend fun extractMlKitLatinParallel(
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

    private fun generateSmartFileName(pages: List<PageOcrData>, docId: Long): String {
        val firstLine = pages.firstOrNull()?.fullPageText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.length in 4..45 && it.any { ch -> ch.isLetter() } }

        // Preserve Unicode letters AND combining vowel marks/matras (\p{M}) for Bangla/Devanagari
        val sanitized = firstLine
            ?.replace(Regex("[^\\p{L}\\p{M}\\p{N}_-]+"), "_")
            ?.trim('_')
            ?.take(32)

        return if (!sanitized.isNullOrBlank()) {
            "${sanitized}_${docId % 10000}"
        } else {
            "VellumScan_${docId % 100000}"
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

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VellumScan OCR", text))
        Toast.makeText(this, "OCR text copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun savePdfToDownloads(sourceUri: Uri, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VellumScan")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { dest ->
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    contentResolver.openOutputStream(dest)?.use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, "Saved $fileName to Downloads/VellumScan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTextToDownloads(content: String, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VellumScan")
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
