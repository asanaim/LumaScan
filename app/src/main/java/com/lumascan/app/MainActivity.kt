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
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

enum class OcrScript(val label: String) {
    LATIN("English / Latin"),
    BANG_ENG("Bangla + English"),
    DEVANAGARI_ENG("Devanagari + English"),
    CHINESE_ENG("Chinese + English"),
    JAPANESE_ENG("Japanese + English"),
    KOREAN_ENG("Korean + English")
}

data class ScannedDoc(
    val id: Long,
    val title: String,
    val pdfUri: Uri?,
    val pageCount: Int,
    val date: String,
    val scriptLabel: String,
    val ocrText: String,
    val isSearchablePdf: Boolean = false,
    val isOcrProcessing: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    private fun getMlKitRecognizer(script: OcrScript): TextRecognizer {
        return recognizers.getOrPut(script) {
            when (script) {
                OcrScript.DEVANAGARI_ENG -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                OcrScript.CHINESE_ENG -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrScript.JAPANESE_ENG -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrScript.KOREAN_ENG -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

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
            val appleBg = Color(0xFFF2F2F7)

            var selectedScript by remember { mutableStateOf(OcrScript.LATIN) }
            val scannedDocs = remember { mutableStateListOf<ScannedDoc>() }
            var activeOcrDoc by remember { mutableStateOf<ScannedDoc?>(null) }

            val scannerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                    val rawPdf = scanResult?.pdf
                    val pages = scanResult?.pages.orEmpty()
                    val docId = System.currentTimeMillis()
                    val timestamp = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date())
                    val baseName = "LumaScan_$docId"
                    val pdfFileName = "$baseName.pdf"
                    val currentScript = selectedScript

                    val newDoc = ScannedDoc(
                        id = docId,
                        title = pdfFileName,
                        pdfUri = rawPdf?.uri,
                        pageCount = pages.size.coerceAtLeast(rawPdf?.pageCount ?: 1),
                        date = timestamp,
                        scriptLabel = currentScript.label,
                        ocrText = "",
                        isSearchablePdf = false,
                        isOcrProcessing = pages.isNotEmpty()
                    )
                    scannedDocs.add(0, newDoc)

                    if (pages.isNotEmpty()) {
                        processPagesAndBuildSearchablePdf(
                            pageUris = pages.map { it.imageUri },
                            script = currentScript,
                            outputPdfName = pdfFileName
                        ) { extractedText, searchablePdfUri ->
                            val finalPdfUri = searchablePdfUri ?: rawPdf?.uri
                            if (finalPdfUri != null) {
                                savePdfToDownloads(finalPdfUri, pdfFileName)
                            }
                            if (extractedText.isNotBlank()) {
                                saveTextToDownloads(extractedText, "$baseName.txt")
                            }

                            val index = scannedDocs.indexOfFirst { item -> item.id == docId }
                            if (index != -1) {
                                scannedDocs[index] = scannedDocs[index].copy(
                                    pdfUri = finalPdfUri,
                                    ocrText = extractedText,
                                    isSearchablePdf = searchablePdfUri != null && extractedText.isNotBlank(),
                                    isOcrProcessing = false
                                )
                            }
                        }
                    } else if (rawPdf != null) {
                        savePdfToDownloads(rawPdf.uri, pdfFileName)
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
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "LumaScan",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "Searchable PDF & Multi-Script Offline OCR",
                        fontSize = 15.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "OCR LANGUAGE SCRIPT",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OcrScript.entries.forEach { script ->
                            val isSelected = selectedScript == script
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedScript = script },
                                label = { Text(script.label, fontSize = 13.sp) },
                                shape = RoundedCornerShape(20.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = appleBlue,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { startScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = appleBlue),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Text(
                            text = "New Scan (${selectedScript.label})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    Text(
                        text = "RECENT SEARCHABLE SCANS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (scannedDocs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Select your script and tap 'New Scan' to create searchable PDFs.",
                                color = Color.Gray
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(scannedDocs, key = { it.id }) { doc ->
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
                                                    fontSize = 16.sp,
                                                    color = Color.Black
                                                )
                                                Text(
                                                    text = "${doc.pageCount} page(s) • ${doc.scriptLabel}",
                                                    fontSize = 12.sp,
                                                    color = Color.Gray
                                                )
                                                if (doc.isSearchablePdf) {
                                                    Text(
                                                        text = "✓ Searchable PDF Layer Embedded",
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
                                                    text = "Embedding ${doc.scriptLabel} searchable PDF layer...",
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
                                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                        OutlinedButton(
                                                            onClick = { activeOcrDoc = doc },
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("View Full OCR", fontSize = 13.sp, color = appleBlue)
                                                        }
                                                        Button(
                                                            onClick = { copyToClipboard(doc.ocrText) },
                                                            colors = ButtonDefaults.buttonColors(containerColor = appleGreen),
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("Copy Text", fontSize = 13.sp, color = Color.White)
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            Text("Image-only PDF saved (no text detected).", fontSize = 13.sp, color = Color.LightGray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                activeOcrDoc?.let { doc ->
                    AlertDialog(
                        onDismissRequest = { activeOcrDoc = null },
                        title = {
                            Text("Extracted Text (${doc.scriptLabel})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                                TextButton(onClick = { sharePlainText(doc.ocrText) }) {
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

    private fun processPagesAndBuildSearchablePdf(
        pageUris: List<Uri>,
        script: OcrScript,
        outputPdfName: String,
        onComplete: (extractedText: String, searchablePdfUri: Uri?) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val pageDataList = if (script == OcrScript.BANG_ENG) {
                extractTesseractPageData(pageUris, "ben+eng")
            } else {
                extractMlKitPageData(pageUris, script)
            }

            val combinedText = pageDataList.mapIndexedNotNull { index, page ->
                val content = page.fullPageText.trim()
                if (content.isNotEmpty()) {
                    if (pageDataList.size == 1) content else "--- Page ${index + 1} ---
$content"
                } else null
            }.joinToString("

")

            val searchablePdfUri = SearchablePdfGenerator.generateSearchablePdf(
                context = this@MainActivity,
                pages = pageDataList,
                outputFileName = outputPdfName
            )

            pageDataList.forEach { it.bitmap.recycle() }

            withContext(Dispatchers.Main) {
                onComplete(combinedText, searchablePdfUri)
            }
        }
    }

    private fun extractMlKitPageData(pageUris: List<Uri>, script: OcrScript): List<PageOcrData> {
        val recognizer = getMlKitRecognizer(script)
        val results = mutableListOf<PageOcrData>()

        for (uri in pageUris) {
            val bitmap = decodeMutableBitmap(uri) ?: continue
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(recognizer.process(inputImage))
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
                results.add(PageOcrData(bitmap, wordBoxes, visionText.text))
            } catch (e: Exception) {
                results.add(PageOcrData(bitmap, emptyList(), ""))
            }
        }
        return results
    }

    private fun extractTesseractPageData(pageUris: List<Uri>, langCode: String): List<PageOcrData> {
        val results = mutableListOf<PageOcrData>()
        val tessDataParentDir = prepareTessDataFiles(listOf("ben.traineddata", "eng.traineddata"))

        val tess = TessBaseAPI()
        val isInitialized = tessDataParentDir != null && tess.init(tessDataParentDir, langCode)

        for (uri in pageUris) {
            val bitmap = decodeMutableBitmap(uri) ?: continue
            if (!isInitialized) {
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

                results.add(PageOcrData(bitmap, wordBoxes, fullText))
            } catch (e: Exception) {
                results.add(PageOcrData(bitmap, emptyList(), ""))
            }
        }

        if (isInitialized) {
            tess.recycle()
        }
        return results
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

    private fun prepareTessDataFiles(fileNames: List<String>): String? {
        return try {
            val tessDir = File(filesDir, "tessdata")
            if (!tessDir.exists()) tessDir.mkdirs()

            for (name in fileNames) {
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
        clipboard.setPrimaryClip(ClipData.newPlainText("LumaScan OCR", text))
        Toast.makeText(this, "OCR text copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun savePdfToDownloads(sourceUri: Uri, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LumaScan")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { dest ->
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    contentResolver.openOutputStream(dest)?.use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(this, "Searchable PDF saved to Downloads/LumaScan/$fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTextToDownloads(ocrContent: String, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LumaScan")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { dest ->
                contentResolver.openOutputStream(dest)?.use { output ->
                    output.write(ocrContent.toByteArray(Charsets.UTF_8))
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

    private fun sharePlainText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share Extracted OCR Text"))
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizers.values.forEach { it.close() }
    }
}
