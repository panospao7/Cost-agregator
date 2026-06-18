package com.yourname.expensetracker.data.privacy

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P8 (P8-NEW-A / P8-CURRENT-017): ExportAnonymizer must redact every PII-bearing
 * table in the export copy, not only OCR + raw notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportAnonymizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val anonymizer = ExportAnonymizer()

    private fun seedDb(file: File) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE scanned_receipts (id INTEGER PRIMARY KEY, rawOcrText TEXT)")
            db.execSQL("INSERT INTO scanned_receipts(id, rawOcrText) VALUES (1, 'SECRET_OCR')")

            db.execSQL("CREATE TABLE ai_artifacts (id INTEGER PRIMARY KEY, summaryText TEXT, explanationText TEXT, payloadJson TEXT, errorMessage TEXT)")
            db.execSQL("INSERT INTO ai_artifacts(id, summaryText, explanationText, payloadJson, errorMessage) VALUES (1, 'SECRET_SUMMARY', 'SECRET_EXPL', 'SECRET_PAYLOAD', 'SECRET_ERR')")

            db.execSQL("CREATE TABLE ai_chat_messages (id INTEGER PRIMARY KEY, text TEXT NOT NULL, payloadJson TEXT)")
            db.execSQL("INSERT INTO ai_chat_messages(id, text, payloadJson) VALUES (1, 'SECRET_CHAT', 'SECRET_CHAT_PAYLOAD')")

            db.execSQL("CREATE TABLE merchant_locations (id INTEGER PRIMARY KEY, displayName TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, displayAddress TEXT, osmId TEXT)")
            db.execSQL("INSERT INTO merchant_locations(id, displayName, latitude, longitude, displayAddress, osmId) VALUES (1, 'SECRET_MERCHANT', 37.98, 23.72, 'SECRET_ADDR', 'node/123')")

            db.execSQL("CREATE TABLE email_receipt_sources (id INTEGER PRIMARY KEY, emailSender TEXT, emailSubject TEXT, emailMessageId TEXT, emailMessageIdHash TEXT, fingerprint TEXT)")
            db.execSQL("INSERT INTO email_receipt_sources(id, emailSender, emailSubject, emailMessageId, emailMessageIdHash, fingerprint) VALUES (1, 'secret@example.com', 'SECRET_SUBJECT', 'SECRET_MSGID', 'KEEP_HASH', 'KEEP_FP')")

            db.execSQL("CREATE TABLE notification_intake (id INTEGER PRIMARY KEY, title TEXT, text TEXT, bigText TEXT, subText TEXT, extrasJson TEXT, dedupeFingerprint TEXT, contentHash TEXT)")
            db.execSQL("INSERT INTO notification_intake(id, title, text, bigText, subText, extrasJson, dedupeFingerprint, contentHash) VALUES (1, 'SECRET_TITLE', 'SECRET_TEXT', 'SECRET_BIG', 'SECRET_SUB', 'SECRET_EXTRAS', 'KEEP_FP', 'KEEP_HASH')")
        } finally {
            db.close()
        }
    }

    private fun queryString(file: File, sql: String): String? {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        } finally {
            db.close()
        }
    }

    private fun queryDouble(file: File, sql: String): Double {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getDouble(0) else -1.0 }
        } finally {
            db.close()
        }
    }

    @Test
    fun sanitizeExport_redacts_all_pii_bearing_tables() {
        val file = tempFolder.newFile("export.db")
        seedDb(file)

        anonymizer.sanitizeExport(file)

        // OCR + chat text
        assertEquals(null, queryString(file, "SELECT rawOcrText FROM scanned_receipts WHERE id=1"))
        assertEquals("", queryString(file, "SELECT text FROM ai_chat_messages WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT payloadJson FROM ai_chat_messages WHERE id=1"))

        // AI artifacts
        assertEquals(null, queryString(file, "SELECT summaryText FROM ai_artifacts WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT explanationText FROM ai_artifacts WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT payloadJson FROM ai_artifacts WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT errorMessage FROM ai_artifacts WHERE id=1"))

        // Merchant location GPS/display
        assertEquals("", queryString(file, "SELECT displayName FROM merchant_locations WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT displayAddress FROM merchant_locations WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT osmId FROM merchant_locations WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT latitude FROM merchant_locations WHERE id=1"), 0.0)
        assertEquals(0.0, queryDouble(file, "SELECT longitude FROM merchant_locations WHERE id=1"), 0.0)

        // Email raw fields nulled, dedup hashes preserved
        assertEquals(null, queryString(file, "SELECT emailSender FROM email_receipt_sources WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT emailSubject FROM email_receipt_sources WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT emailMessageId FROM email_receipt_sources WHERE id=1"))
        assertEquals("KEEP_HASH", queryString(file, "SELECT emailMessageIdHash FROM email_receipt_sources WHERE id=1"))
        assertEquals("KEEP_FP", queryString(file, "SELECT fingerprint FROM email_receipt_sources WHERE id=1"))

        // Notification intake raw payload text nulled, dedup fingerprint/hash preserved
        assertEquals(null, queryString(file, "SELECT title FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT text FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT bigText FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT subText FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT extrasJson FROM notification_intake WHERE id=1"))
        assertEquals("KEEP_FP", queryString(file, "SELECT dedupeFingerprint FROM notification_intake WHERE id=1"))
        assertEquals("KEEP_HASH", queryString(file, "SELECT contentHash FROM notification_intake WHERE id=1"))
    }

    @Test
    fun sanitizeExport_is_safe_when_optional_tables_missing() {
        val file = tempFolder.newFile("minimal.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE scanned_receipts (id INTEGER PRIMARY KEY, rawOcrText TEXT)")
        db.execSQL("INSERT INTO scanned_receipts(id, rawOcrText) VALUES (1, 'SECRET_OCR')")
        db.close()

        // Must not throw when ai_artifacts / merchant_locations / email tables are absent.
        anonymizer.sanitizeExport(file)
        assertTrue(queryString(file, "SELECT rawOcrText FROM scanned_receipts WHERE id=1") == null)
    }

    @Test
    fun sanitizeExport_redacts_pending_reviews_notification_text() {
        val file = tempFolder.newFile("pending_reviews.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE pending_reviews (id INTEGER PRIMARY KEY, notificationText TEXT, notificationTitle TEXT, suggestedMerchant TEXT)")
        db.execSQL("INSERT INTO pending_reviews(id, notificationText, notificationTitle, suggestedMerchant) VALUES (1, 'SECRET_TEXT', 'SECRET_TITLE', 'KEEP_MERCHANT')")
        db.execSQL("INSERT INTO pending_reviews(id, notificationText, notificationTitle, suggestedMerchant) VALUES (2, NULL, NULL, 'KEEP_MERCHANT2')")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT notificationText FROM pending_reviews WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT notificationTitle FROM pending_reviews WHERE id=1"))
        assertEquals("KEEP_MERCHANT", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=1"))
        // Row 2 already null — should not be affected
        assertEquals("KEEP_MERCHANT2", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=2"))
    }

    @Test
    fun sanitizeExport_redacts_bank_statement_import_items_merchant() {
        val file = tempFolder.newFile("bank_items.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE bank_statement_import_items (id INTEGER PRIMARY KEY, merchant TEXT, amount REAL, transactionFingerprint TEXT)")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount, transactionFingerprint) VALUES (1, 'SECRET_MERCHANT', 42.5, 'KEEP_FP')")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount, transactionFingerprint) VALUES (2, NULL, 10.0, 'KEEP_FP2')")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals("[REDACTED]", queryString(file, "SELECT merchant FROM bank_statement_import_items WHERE id=1"))
        assertEquals("KEEP_FP", queryString(file, "SELECT transactionFingerprint FROM bank_statement_import_items WHERE id=1"))
        // Row 2 merchant is null — should not be affected
        assertEquals(null, queryString(file, "SELECT merchant FROM bank_statement_import_items WHERE id=2"))
        assertEquals("KEEP_FP2", queryString(file, "SELECT transactionFingerprint FROM bank_statement_import_items WHERE id=2"))
    }
}
