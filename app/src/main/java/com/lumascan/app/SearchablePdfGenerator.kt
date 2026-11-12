package com.lumascan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

data class OcrWordBox(
    val text: String,
    val boundingBox: Rect
)

data class PageOcrData(
    val bitmap: Bitmap,
    val words: List<OcrWordBox>,
    val fullPageText: String
)

object SearchablePdfGenerator {

    const val DOWNLOAD_PAGE_URL = "https://asanaim.github.io/LumaScan/"
    const val DIRECT_APK_DOWNLOAD_URL = "https://github.com/asanaim/LumaScan/releases/latest/download/NuvioScan.apk"

    private val sensitivePattern = Regex(
        "(\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4,7}\\b)|(\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b)|(\\+?\\d[\\d -]{7,14}\\d)"
    )

    fun isSensitiveToken(text: String): Boolean {
        return sensitivePattern.containsMatchIn(text)
    }

    fun stitchTwoPagesOntoA4(front: Bitmap, back: Bitmap): Bitmap {
        val a4Width = maxOf(front.width, back.width, 1600)
        val a4Height = (a4Width * 1.414f).toInt()
        val combined = Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(Color.WHITE)

        val padding = (a4Width * 0.06f).toInt()
        val slotWidth = a4Width - (padding * 2)
        val slotHeight = (a4Height - (padding * 3)) / 2

        fun drawCentered(bmp: Bitmap, topY: Int) {
            val scale = minOf(
                slotWidth.toFloat() / bmp.width.toFloat(),
                slotHeight.toFloat() / bmp.height.toFloat()
            )
            val dstW = (bmp.width * scale).toInt()
            val dstH = (bmp.height * scale).toInt()
            val leftX = (a4Width - dstW) / 2
            val offsetY = topY + (slotHeight - dstH) / 2
            val dstRect = Rect(leftX, offsetY, leftX + dstW, offsetY + dstH)
            canvas.drawBitmap(bmp, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }

        drawCentered(front, padding)
        drawCentered(back, padding * 2 + slotHeight)
        return combined
    }

    fun convertWordsToCsv(pages: List<PageOcrData>): String {
        val sb = StringBuilder()
        val lineBreak = "\n"

        pages.forEachIndexed { pageIndex, page ->
            if (page.words.isEmpty()) return@forEachIndexed
            if (pages.size > 1) {
                sb.append("--- Page ").append(pageIndex + 1).append(" ---").append(lineBreak)
            }

            val sortedByY = page.words.sortedBy { it.boundingBox.centerY() }
            val rows = mutableListOf<MutableList<OcrWordBox>>()

            for (word in sortedByY) {
                val wordY = word.boundingBox.centerY()
                val tolerance = (word.boundingBox.height() * 0.6f).toInt().coerceAtLeast(12)
                val existingRow = rows.lastOrNull { row ->
                    val avgY = row.map { it.boundingBox.centerY() }.average().toInt()
                    abs(wordY - avgY) <= tolerance
                }
                if (existingRow != null) {
                    existingRow.add(word)
                } else {
                    rows.add(mutableListOf(word))
                }
            }

            for (row in rows) {
                val sortedRow = row.sortedBy { it.boundingBox.left }
                val cells = mutableListOf<String>()
                var currentCell = StringBuilder()
                var lastRight = -1

                for (w in sortedRow) {
                    val gap = if (lastRight == -1) 0 else w.boundingBox.left - lastRight
                    val spaceThreshold = (w.boundingBox.height() * 1.3f).toInt().coerceAtLeast(24)
                    if (lastRight != -1 && gap > spaceThreshold) {
                        cells.add(escapeCsvCell(currentCell.toString().trim()))
                        currentCell = StringBuilder(w.text)
                    } else {
                        if (currentCell.isNotEmpty()) currentCell.append(" ")
                        currentCell.append(w.text)
                    }
                    lastRight = w.boundingBox.right
                }
                if (currentCell.isNotEmpty()) {
                    cells.add(escapeCsvCell(currentCell.toString().trim()))
                }
                sb.append(cells.joinToString(",")).append(lineBreak)
            }
        }
        return sb.toString().trim()
    }

    private fun escapeCsvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"")) "\"$escaped\"" else escaped
    }

    private fun compressBitmapEfficient(bmp: Bitmap): Bitmap {
        return try {
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val byteArray = stream.toByteArray()
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size) ?: bmp
        } catch (_: Exception) {
            bmp
        }
    }

    /**
     * Generates a searchable PDF where both the OCR text layer and the embedded download URL
     * are written strictly in the invisible PDF text stream (nothing is visibly printed over the scan).
     */
    fun generateSearchablePdf(
        context: Context,
        pages: List<PageOcrData>,
        outputFileName: String,
        redactSensitivePii: Boolean = false
    ): Uri? {
        if (pages.isEmpty()) return null

        val pdfDocument = PdfDocument()

        val invisibleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(1, 0, 0, 0)
            typeface = Typeface.DEFAULT
            style = Paint.Style.FILL
        }

        val redactionPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        try {
            pages.forEachIndexed { index, pageData ->
                val compressedBmp = compressBitmapEfficient(pageData.bitmap)

                val pageInfo = PdfDocument.PageInfo.Builder(
                    compressedBmp.width,
                    compressedBmp.height,
                    index + 1
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // 1. Draw clean scanned page image (no visible watermarks or printed footers)
                canvas.drawBitmap(compressedBmp, 0f, 0f, null)

                // 2. Draw invisible selectable OCR text overlay
                for (word in pageData.words) {
                    val box = word.boundingBox
                    val value = word.text.trim()
                    if (value.isEmpty() || box.width() <= 0 || box.height() <= 0) continue

                    if (redactSensitivePii && isSensitiveToken(value)) {
                        canvas.drawRect(box, redactionPaint)
                        continue
                    }

                    val targetHeight = box.height().toFloat()
                    invisibleTextPaint.textSize = targetHeight * 0.85f
                    invisibleTextPaint.textScaleX = 1.0f

                    val measuredWidth = invisibleTextPaint.measureText(value)
                    if (measuredWidth > 0f) {
                        invisibleTextPaint.textScaleX =
                            (box.width().toFloat() / measuredWidth).coerceIn(0.4f, 3.0f)
                    }

                    val baselineY = box.bottom.toFloat() - (targetHeight * 0.12f)
                    canvas.drawText(value, box.left.toFloat(), baselineY, invisibleTextPaint)
                }

                // 3. Embed the download link invisibly in the PDF stream (not printed visibly on the page)
                val hiddenFooterY = compressedBmp.height.toFloat() - 12f
                invisibleTextPaint.textSize = 18f
                invisibleTextPaint.textScaleX = 1.0f
                canvas.drawText(DIRECT_APK_DOWNLOAD_URL, 24f, hiddenFooterY, invisibleTextPaint)

                pdfDocument.finishPage(page)
                if (compressedBmp != pageData.bitmap) {
                    compressedBmp.recycle()
                }
            }

            val outFile = File(context.cacheDir, outputFileName)
            FileOutputStream(outFile).use { outStream ->
                pdfDocument.writeTo(outStream)
            }

            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                outFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            pdfDocument.close()
        }
    }
}
