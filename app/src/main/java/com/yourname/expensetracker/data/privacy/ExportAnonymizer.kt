package com.yourname.expensetracker.data.privacy

import android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sanitises a copy of the database before export by removing sensitive raw
 * data fields that are not needed by the user receiving the backup.
 *
 * Currently strips:
 * - `rawOcrText` from `scanned_receipts`
 * - Raw notification content fields (`title`, `text`, `bigText`, `subText`,
 *   `extrasJson`, `parseResult`) from `raw_notifications`
 *
 * The operation is performed **in-place** on the provided file, so callers
 * must pass a **temporary copy** — never the live database file.
 */
@Singleton
class ExportAnonymizer @Inject constructor() {

    companion object {
        private const val TAG = "ExportAnonymizer"
    }

    /**
     * Sanitises [dbCopy] in-place by nulling out columns that contain raw
     * OCR text and raw notification content.
     *
     * @param dbCopy a temporary copy of the live database file (will be modified)
     * @throws IllegalStateException if the file cannot be opened as a valid SQLite database
     */
    fun sanitizeExport(dbCopy: File) {
        if (!dbCopy.exists() || dbCopy.length() == 0L) {
            Timber.d("$TAG: Skipping sanitize — file missing or empty")
            return
        }

        val db = try {
            SQLiteDatabase.openDatabase(
                dbCopy.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Cannot open database for sanitisation")
            throw IllegalStateException("Cannot open database copy for sanitisation: ${e.message}", e)
        }

        try {
            db.beginTransaction()
            try {
                val ocrPurged = sanitizeScannedReceipts(db)
                val notificationPurged = sanitizeRawNotifications(db)
                db.setTransactionSuccessful()

                Timber.d("$TAG: Sanitised $ocrPurged scanned_receipts and $notificationPurged raw_notifications")
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to sanitise database copy")
            throw e
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * Nulls out [ScannedReceipt.rawOcrText] for rows where it is not null.
     * Uses direct SQL because this operates on a standalone SQLite file.
     *
     * @return number of rows updated
     */
    private fun sanitizeScannedReceipts(db: SQLiteDatabase): Int {
        if (!tableExists(db, "scanned_receipts")) return 0

        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM scanned_receipts WHERE rawOcrText IS NOT NULL",
            null
        )
        val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count == 0) return 0

        db.execSQL("UPDATE scanned_receipts SET rawOcrText = NULL WHERE rawOcrText IS NOT NULL")
        Timber.d("$TAG: Nulled rawOcrText in $count scanned_receipt rows")
        return count
    }

    /**
     * Nulls out raw content columns in [RawNotification] for rows that have
     * not already been purged.
     *
     * @return number of rows updated
     */
    private fun sanitizeRawNotifications(db: SQLiteDatabase): Int {
        if (!tableExists(db, "raw_notifications")) return 0

        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM raw_notifications WHERE " +
                "title IS NOT NULL OR text IS NOT NULL OR bigText IS NOT NULL " +
                "OR subText IS NOT NULL OR extrasJson IS NOT NULL OR parseResult IS NOT NULL",
            null
        )
        val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count == 0) return 0

        db.execSQL(
            """
            UPDATE raw_notifications SET
                title = NULL,
                text = NULL,
                bigText = NULL,
                subText = NULL,
                extrasJson = NULL,
                parseResult = NULL
            WHERE title IS NOT NULL OR text IS NOT NULL OR bigText IS NOT NULL
                OR subText IS NOT NULL OR extrasJson IS NOT NULL OR parseResult IS NOT NULL
            """.trimIndent()
        )
        Timber.d("$TAG: Nulled raw content in $count raw_notification rows")
        return count
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        return cursor.use { it.moveToFirst() }
    }
}
