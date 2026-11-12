package com.vellumscan.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class OcrScript(
    val label: String,
    val shortLabel: String,
    val tessCode: String,
    val fileName: String,
    val sizeLabel: String,
    val isDefaultBuiltIn: Boolean
) {
    LATIN(
        label = "English / Latin",
        shortLabel = "English",
        tessCode = "eng",
        fileName = "eng.traineddata",
        sizeLabel = "Built-in",
        isDefaultBuiltIn = true
    ),
    BANG_ENG(
        label = "Bangla + English",
        shortLabel = "বাংলা + Eng",
        tessCode = "ben+eng",
        fileName = "ben.traineddata",
        sizeLabel = "Built-in",
        isDefaultBuiltIn = true
    ),
    DEVANAGARI_ENG(
        label = "Devanagari + English",
        shortLabel = "हिन्दी + Eng",
        tessCode = "hin+eng",
        fileName = "hin.traineddata",
        sizeLabel = "2.5 MB",
        isDefaultBuiltIn = false
    ),
    CHINESE_ENG(
        label = "Chinese + English",
        shortLabel = "中文 + Eng",
        tessCode = "chi_sim+eng",
        fileName = "chi_sim.traineddata",
        sizeLabel = "2.4 MB",
        isDefaultBuiltIn = false
    ),
    JAPANESE_ENG(
        label = "Japanese + English",
        shortLabel = "日本語 + Eng",
        tessCode = "jpn+eng",
        fileName = "jpn.traineddata",
        sizeLabel = "2.4 MB",
        isDefaultBuiltIn = false
    ),
    KOREAN_ENG(
        label = "Korean + English",
        shortLabel = "한국어 + Eng",
        tessCode = "kor+eng",
        fileName = "kor.traineddata",
        sizeLabel = "1.7 MB",
        isDefaultBuiltIn = false
    )
}

object LanguageModelManager {

    private const val TESSDATA_FAST_BASE_URL =
        "https://github.com/tesseract-ocr/tessdata_fast/raw/main/"

    fun getTessDataDir(context: Context): File {
        val dir = File(context.filesDir, "tessdata")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copies built-in default models (eng.traineddata, ben.traineddata) from APK assets
     * into filesDir/tessdata if not already present.
     */
    fun ensureDefaultAssetsCopied(context: Context) {
        val tessDir = getTessDataDir(context)
        val defaults = listOf("eng.traineddata", "ben.traineddata")
        for (name in defaults) {
            val target = File(tessDir, name)
            if (!target.exists()) {
                try {
                    context.assets.open("tessdata/$name").use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun isScriptInstalled(context: Context, script: OcrScript): Boolean {
        if (script.isDefaultBuiltIn) return true
        val file = File(getTessDataDir(context), script.fileName)
        return file.exists() && file.length() > 100_000L
    }

    fun getInstalledScripts(context: Context): Set<OcrScript> {
        return OcrScript.entries.filter { isScriptInstalled(context, it) }.toSet()
    }

    suspend fun downloadScriptModel(
        context: Context,
        script: OcrScript,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (script.isDefaultBuiltIn) return@withContext true
        ensureDefaultAssetsCopied(context)

        val targetFile = File(getTessDataDir(context), script.fileName)
        val tempFile = File(getTessDataDir(context), "${script.fileName}.tmp")

        try {
            val url = URL(TESSDATA_FAST_BASE_URL + script.fileName)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                connect()
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext false
            }

            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            if (tempFile.exists() && tempFile.length() > 100_000L) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (_: Exception) {
            if (tempFile.exists()) tempFile.delete()
            false
        }
    }

    fun deleteScriptModel(context: Context, script: OcrScript): Boolean {
        if (script.isDefaultBuiltIn) return false
        val file = File(getTessDataDir(context), script.fileName)
        return if (file.exists()) file.delete() else true
    }
}
