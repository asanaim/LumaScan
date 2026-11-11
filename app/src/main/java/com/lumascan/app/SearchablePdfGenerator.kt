package com.lumascan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

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

    fun generateSearchablePdf(
        context: Context,
        pages: List<PageOcrData>,
        outputFileName: String
    ): Uri? {
        if (pages.isEmpty()) return null

        val pdfDocument = PdfDocument()

        val invisibleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(1, 0, 0, 0)
            typeface = Typeface.DEFAULT
            style = Paint.Style.FILL
        }

        try {
            pages.forEachIndexed { index, pageData ->
                val bmp = pageData.bitmap
                val pageInfo = PdfDocument.PageInfo.Builder(
                    bmp.width,
                    bmp.height,
                    index + 1
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawBitmap(bmp, 0f, 0f, null)

                for (word in pageData.words) {
                    val box = word.boundingBox
                    val value = word.text.trim()
                    if (value.isEmpty() || box.width() <= 0 || box.height() <= 0) continue

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

                pdfDocument.finishPage(page)
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
