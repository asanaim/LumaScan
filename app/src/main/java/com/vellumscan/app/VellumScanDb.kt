package com.vellumscan.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

class VellumScanDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "vellumscan.db"
        const val DATABASE_VERSION = 1

        const val TABLE_DOCS = "scanned_docs"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_URI = "pdf_uri"
        const val COL_PAGE_COUNT = "page_count"
        const val COL_DATE = "scan_date"
        const val COL_SCRIPT = "script_label"
        const val COL_OCR_TEXT = "ocr_text"
        const val COL_CSV_TABLE = "csv_table"
        const val COL_PHONE = "detected_phone"
        const val COL_EMAIL = "detected_email"
        const val COL_IS_REDACTED = "is_redacted"
        const val COL_IS_ID_STITCH = "is_id_stitch"
        const val COL_IS_SEARCHABLE = "is_searchable"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createQuery = "CREATE TABLE " + TABLE_DOCS + " (" +
            COL_ID + " INTEGER PRIMARY KEY, " +
            COL_TITLE + " TEXT, " +
            COL_URI + " TEXT, " +
            COL_PAGE_COUNT + " INTEGER, " +
            COL_DATE + " TEXT, " +
            COL_SCRIPT + " TEXT, " +
            COL_OCR_TEXT + " TEXT, " +
            COL_CSV_TABLE + " TEXT, " +
            COL_PHONE + " TEXT, " +
            COL_EMAIL + " TEXT, " +
            COL_IS_REDACTED + " INTEGER, " +
            COL_IS_ID_STITCH + " INTEGER, " +
            COL_IS_SEARCHABLE + " INTEGER)"
        db.execSQL(createQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DOCS)
        onCreate(db)
    }

    fun insertOrUpdateDoc(doc: ScannedDoc) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, doc.id)
            put(COL_TITLE, doc.title)
            put(COL_URI, doc.pdfUri?.toString())
            put(COL_PAGE_COUNT, doc.pageCount)
            put(COL_DATE, doc.date)
            put(COL_SCRIPT, doc.scriptLabel)
            put(COL_OCR_TEXT, doc.ocrText)
            put(COL_CSV_TABLE, doc.csvTable)
            put(COL_PHONE, doc.detectedPhone)
            put(COL_EMAIL, doc.detectedEmail)
            put(COL_IS_REDACTED, if (doc.isRedacted) 1 else 0)
            put(COL_IS_ID_STITCH, if (doc.isIdStitch) 1 else 0)
            put(COL_IS_SEARCHABLE, if (doc.isSearchablePdf) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_DOCS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllDocs(): List<ScannedDoc> {
        val docs = mutableListOf<ScannedDoc>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DOCS,
            null,
            null,
            null,
            null,
            null,
            COL_ID + " DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val uriStr = c.getString(c.getColumnIndexOrThrow(COL_URI))
                docs.add(
                    ScannedDoc(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        title = c.getString(c.getColumnIndexOrThrow(COL_TITLE)) ?: "",
                        pdfUri = if (!uriStr.isNullOrEmpty()) Uri.parse(uriStr) else null,
                        pageCount = c.getInt(c.getColumnIndexOrThrow(COL_PAGE_COUNT)),
                        date = c.getString(c.getColumnIndexOrThrow(COL_DATE)) ?: "",
                        scriptLabel = c.getString(c.getColumnIndexOrThrow(COL_SCRIPT)) ?: "English / Latin",
                        ocrText = c.getString(c.getColumnIndexOrThrow(COL_OCR_TEXT)) ?: "",
                        csvTable = c.getString(c.getColumnIndexOrThrow(COL_CSV_TABLE)) ?: "",
                        detectedPhone = c.getString(c.getColumnIndexOrThrow(COL_PHONE)),
                        detectedEmail = c.getString(c.getColumnIndexOrThrow(COL_EMAIL)),
                        isRedacted = c.getInt(c.getColumnIndexOrThrow(COL_IS_REDACTED)) == 1,
                        isIdStitch = c.getInt(c.getColumnIndexOrThrow(COL_IS_ID_STITCH)) == 1,
                        isSearchablePdf = c.getInt(c.getColumnIndexOrThrow(COL_IS_SEARCHABLE)) == 1,
                        isOcrProcessing = false
                    )
                )
            }
        }
        return docs
    }

    fun deleteDoc(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_DOCS, COL_ID + " = ?", arrayOf(id.toString()))
    }
}
