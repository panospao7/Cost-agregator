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
 * Strips, per PII-bearing table (all in a single transaction):
 * - `scanned_receipts.rawOcrText`
 * - `raw_notifications` raw content (`title`, `text`, `bigText`, `subText`,
 *   `extrasJson`, `parseResult`)
 * - `notification_intake` raw content (`title`, `text`, `bigText`, `subText`,
 *   `extrasJson`; dedup fingerprint/content hash preserved)
 * - `ai_artifacts` generated text (`summaryText`, `explanationText`,
 *   `payloadJson`, `errorMessage`)
 * - `ai_chat_messages` free-form input (`text` → '', `payloadJson` → NULL)
 * - `merchant_locations` GPS/display PII (`displayName` → '',
 *   `latitude`/`longitude` → 0.0, `displayAddress`/`osmId` → NULL)
 * - `email_receipt_sources` raw email fields (`emailSender`, `emailSubject`,
 *   `emailMessageId` → NULL; dedup hashes/fingerprint preserved)
 * - `pending_reviews` notification text (`notificationText`, `notificationTitle` → NULL)
 * - `bank_statement_import_items` merchant names (`merchant` → '[REDACTED]')
 *
 * The operation is performed **in-place** on the provided file, so callers
 * must pass a **temporary copy** — never the live database file.
 *
 * Each table is redacted only if present (`tableExists`) so the routine is
 * forward/backward compatible across schema versions.
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
                val intakePurged = sanitizeNotificationIntake(db)
                val aiArtifactsPurged = sanitizeAiArtifacts(db)
                val aiChatPurged = sanitizeAiChatMessages(db)
                val merchantLocPurged = sanitizeMerchantLocations(db)
                val emailPurged = sanitizeEmailReceiptSources(db)
                val pendingReviewsPurged = sanitizePendingReviews(db)
                val bankItemsPurged = sanitizeBankStatementImportItems(db)
                db.setTransactionSuccessful()

                Timber.d(
                    "$TAG: Sanitised receipts=$ocrPurged notifications=$notificationPurged " +
                        "notificationIntake=$intakePurged aiArtifacts=$aiArtifactsPurged " +
                        "aiChat=$aiChatPurged merchantLocations=$merchantLocPurged " +
                        "emailSources=$emailPurged pendingReviews=$pendingReviewsPurged " +
                        "bankImportItems=$bankItemsPurged"
                )
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

    /**
     * Nulls out raw content columns in [NotificationIntakeEntity]
     * (`notification_intake`) for rows that still carry visible payload text.
     * Mirrors [sanitizeRawNotifications]; dedup fingerprint/content hash are
     * preserved. Note: `notification_intake` has no `parseResult` column.
     *
     * @return number of rows updated
     */
    private fun sanitizeNotificationIntake(db: SQLiteDatabase): Int {
        if (!tableExists(db, "notification_intake")) return 0

        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM notification_intake WHERE " +
                "title IS NOT NULL OR text IS NOT NULL OR bigText IS NOT NULL " +
                "OR subText IS NOT NULL OR extrasJson IS NOT NULL",
            null
        )
        val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count == 0) return 0

        db.execSQL(
            """
            UPDATE notification_intake SET
                title = NULL,
                text = NULL,
                bigText = NULL,
                subText = NULL,
                extrasJson = NULL
            WHERE title IS NOT NULL OR text IS NOT NULL OR bigText IS NOT NULL
                OR subText IS NOT NULL OR extrasJson IS NOT NULL
            """.trimIndent()
        )
        Timber.d("$TAG: Nulled raw content in $count notification_intake rows")
        return count
    }

    /**
     * Nulls out AI-generated text columns in `ai_artifacts`.
     * @return number of rows updated
     */
    private fun sanitizeAiArtifacts(db: SQLiteDatabase): Int {
        if (!tableExists(db, "ai_artifacts")) return 0
        val where = "summaryText IS NOT NULL OR explanationText IS NOT NULL " +
            "OR payloadJson IS NOT NULL OR errorMessage IS NOT NULL"
        val count = countWhere(db, "ai_artifacts", where)
        if (count == 0) return 0
        db.execSQL(
            "UPDATE ai_artifacts SET summaryText = NULL, explanationText = NULL, " +
                "payloadJson = NULL, errorMessage = NULL WHERE $where"
        )
        Timber.d("$TAG: Nulled AI artifact text in $count rows")
        return count
    }

    /**
     * Clears free-form chat text in `ai_chat_messages`.
     * `text` is NOT NULL so it is set to '' (empty) rather than NULL.
     * @return number of rows updated
     */
    private fun sanitizeAiChatMessages(db: SQLiteDatabase): Int {
        if (!tableExists(db, "ai_chat_messages")) return 0
        val where = "text <> '' OR payloadJson IS NOT NULL"
        val count = countWhere(db, "ai_chat_messages", where)
        if (count == 0) return 0
        db.execSQL("UPDATE ai_chat_messages SET text = '', payloadJson = NULL WHERE $where")
        Timber.d("$TAG: Cleared AI chat text in $count rows")
        return count
    }

    /**
     * Strips GPS/display PII from `merchant_locations`.
     * `latitude`/`longitude` are NOT NULL so they are zeroed.
     * @return number of rows updated
     */
    private fun sanitizeMerchantLocations(db: SQLiteDatabase): Int {
        if (!tableExists(db, "merchant_locations")) return 0
        val where = "displayName <> '' OR displayAddress IS NOT NULL OR osmId IS NOT NULL " +
            "OR latitude <> 0.0 OR longitude <> 0.0"
        val count = countWhere(db, "merchant_locations", where)
        if (count == 0) return 0
        db.execSQL(
            "UPDATE merchant_locations SET displayName = '', displayAddress = NULL, " +
                "osmId = NULL, latitude = 0.0, longitude = 0.0 WHERE $where"
        )
        Timber.d("$TAG: Stripped location PII in $count rows")
        return count
    }

    /**
     * Redacts raw email fields in `email_receipt_sources`, preserving dedup
     * hashes (`emailMessageIdHash`, `contentFingerprintHash`, `fingerprint`)
     * and `provider`. Mirrors EmailReceiptDao.redactSensitiveFieldsOlderThan.
     * @return number of rows updated
     */
    private fun sanitizeEmailReceiptSources(db: SQLiteDatabase): Int {
        if (!tableExists(db, "email_receipt_sources")) return 0
        val where = "emailSender IS NOT NULL OR emailSubject IS NOT NULL OR emailMessageId IS NOT NULL"
        val count = countWhere(db, "email_receipt_sources", where)
        if (count == 0) return 0
        db.execSQL(
            "UPDATE email_receipt_sources SET emailSender = NULL, emailSubject = NULL, " +
                "emailMessageId = NULL WHERE $where"
        )
        Timber.d("$TAG: Redacted email source fields in $count rows")
        return count
    }

    /**
     * PR5: Nulls out notification text/title in `pending_reviews` that carry
     * raw notification content. Preserves structural fields (amount, merchant, status).
     * @return number of rows updated
     */
    private fun sanitizePendingReviews(db: SQLiteDatabase): Int {
        if (!tableExists(db, "pending_reviews")) return 0
        val where = "notificationText IS NOT NULL OR notificationTitle IS NOT NULL"
        val count = countWhere(db, "pending_reviews", where)
        if (count == 0) return 0
        db.execSQL(
            "UPDATE pending_reviews SET notificationText = NULL, notificationTitle = NULL WHERE $where"
        )
        Timber.d("$TAG: Redacted pending_reviews notification fields in $count rows")
        return count
    }

    /**
     * PR5: Redacts merchant names in `bank_statement_import_items` that carry
     * raw bank transaction merchant text. Preserves structural fields (amount, status, fingerprint).
     * @return number of rows updated
     */
    private fun sanitizeBankStatementImportItems(db: SQLiteDatabase): Int {
        if (!tableExists(db, "bank_statement_import_items")) return 0
        val where = "merchant IS NOT NULL"
        val count = countWhere(db, "bank_statement_import_items", where)
        if (count == 0) return 0
        db.execSQL(
            "UPDATE bank_statement_import_items SET merchant = '[REDACTED]' WHERE $where"
        )
        Timber.d("$TAG: Redacted bank import item merchants in $count rows")
        return count
    }

    private fun countWhere(db: SQLiteDatabase, table: String, where: String): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        return cursor.use { it.moveToFirst() }
    }
}
