package com.yourname.expensetracker.data.privacy

import android.database.sqlite.SQLiteDatabase
import com.yourname.expensetracker.data.database.entity.PendingReview
import timber.log.Timber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun sanitizeExport_clears_legacy_item_text_but_keeps_approved_numeric_projection() {
        val file = tempFolder.newFile("structured_receipts.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val legacyItems = """[{"description":"SECRET_ITEM","quantity":1,"totalPrice":5.0}]"""
        val approvedItems = """{"schema":"REDACTED_V1","items":[{"quantity":1,"unitPrice":5.0,"totalPrice":5.0,"currency":"EUR"}]}"""
        val unexpectedRootItems = """{"schema":"REDACTED_V1","items":[{"quantity":1,"totalPrice":5.0,"currency":"EUR"}],"description":"SECRET_ITEM"}"""
        db.execSQL("CREATE TABLE scanned_receipts (id INTEGER PRIMARY KEY, rawOcrText TEXT, parsedItems TEXT)")
        db.execSQL(
            "INSERT INTO scanned_receipts(id, rawOcrText, parsedItems) VALUES (?, NULL, ?)",
            arrayOf<Any>(1, legacyItems)
        )
        db.execSQL(
            "INSERT INTO scanned_receipts(id, rawOcrText, parsedItems) VALUES (?, NULL, ?)",
            arrayOf<Any>(2, approvedItems)
        )
        db.execSQL(
            "INSERT INTO scanned_receipts(id, rawOcrText, parsedItems) VALUES (?, NULL, ?)",
            arrayOf<Any>(3, unexpectedRootItems)
        )
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT parsedItems FROM scanned_receipts WHERE id=1"))
        assertEquals(approvedItems, queryString(file, "SELECT parsedItems FROM scanned_receipts WHERE id=2"))
        assertEquals(null, queryString(file, "SELECT parsedItems FROM scanned_receipts WHERE id=3"))
    }

    @Test
    fun sanitizeExport_processes_large_scanned_receipt_table_in_bounded_batches() {
        val file = tempFolder.newFile("large_structured_receipts.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val approvedItems = """{"schema":"REDACTED_V1","items":[{"totalPrice":5.0,"currency":"EUR"}]}"""
        val rawItems = """[{"description":"SECRET_ITEM","totalPrice":5.0}]"""
        db.execSQL("CREATE TABLE scanned_receipts (id INTEGER PRIMARY KEY, rawOcrText TEXT, parsedItems TEXT)")
        for (id in 1..257) {
            db.execSQL(
                "INSERT INTO scanned_receipts(id, rawOcrText, parsedItems) VALUES (?, ?, ?)",
                arrayOf<Any>(id, "SECRET_OCR_$id", if (id % 2 == 0) approvedItems else rawItems)
            )
        }
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(128.0, queryDouble(file, "SELECT COUNT(*) FROM scanned_receipts WHERE parsedItems IS NOT NULL"), 0.0)
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM scanned_receipts WHERE rawOcrText IS NOT NULL"), 0.0)
        assertEquals(null, queryString(file, "SELECT parsedItems FROM scanned_receipts WHERE id=1"))
        assertEquals(approvedItems, queryString(file, "SELECT parsedItems FROM scanned_receipts WHERE id=2"))
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

    // ── S7 (J-11-4 / D6): pending_reviews.explanation ────────────────────────

    /** Mirrors the real Room table shape of PendingReview (all columns, real names). */
    private fun createRealShapePendingReviews(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pending_reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                rawNotificationId INTEGER,
                scannedReceiptId INTEGER,
                suggestedAmount REAL,
                suggestedCurrency TEXT NOT NULL,
                suggestedMerchant TEXT NOT NULL,
                suggestedMerchantKey TEXT,
                suggestedType TEXT NOT NULL,
                suggestedCategoryId INTEGER,
                suggestedDate INTEGER,
                confidence REAL NOT NULL,
                matchType TEXT,
                explanation TEXT,
                packageName TEXT NOT NULL,
                notificationTitle TEXT,
                notificationText TEXT,
                createdAt INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                suggestedDirection TEXT,
                suggestedAccountName TEXT,
                suggestedLatitude REAL,
                suggestedLongitude REAL,
                extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION',
                bankReviewIdentity TEXT,
                bankConnectionScopeHash TEXT
            )"""
        )
    }

    private fun insertRealShapePendingReview(
        db: SQLiteDatabase,
        id: Int,
        merchant: String,
        title: String?,
        text: String?,
        explanation: String?
    ) {
        db.execSQL(
            "INSERT INTO pending_reviews(id, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, " +
                "confidence, matchType, explanation, packageName, notificationTitle, notificationText, createdAt, status) " +
                "VALUES (?, 12.5, 'EUR', ?, 'EXPENSE', 0.9, 'KEYWORD', ?, 'com.bank.app', ?, ?, 1700000000000, 'PENDING')",
            arrayOf<Any?>(id, merchant, explanation, title, text)
        )
    }

    /** Runs [block] while capturing every Timber message emitted by the anonymizer. */
    private fun captureTimber(block: () -> Unit): List<String> {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
            }
        }
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return messages
    }

    @Test
    fun sanitizeExport_pending_reviews_explanation_column_name_matches_PendingReview_entity() {
        // Guard against a typo in the anonymizer's guarded column name hiding a no-op:
        // Room derives the column name from the field name (no @ColumnInfo(name=) on this field).
        val field = PendingReview::class.java.getDeclaredField("explanation")
        assertEquals(String::class.java, field.type)
        assertEquals("explanation", field.name)

        // The real-shape table (built from the entity's property names) must expose that column,
        // and the anonymizer must act on it.
        val file = tempFolder.newFile("pending_reviews_column_name.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createRealShapePendingReviews(db)
        insertRealShapePendingReview(db, 1, "KEEP_MERCHANT", "SECRET_TITLE", "SECRET_TEXT", "SECRET_EXPLANATION")
        db.close()

        anonymizer.sanitizeExport(file)

        assertNull(queryString(file, "SELECT ${field.name} FROM pending_reviews WHERE id=1"))
    }

    @Test
    fun sanitizeExport_nulls_pending_reviews_explanation_and_keeps_other_fields_and_rows() {
        val file = tempFolder.newFile("pending_reviews_explanation.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createRealShapePendingReviews(db)
        insertRealShapePendingReview(db, 1, "KEEP_MERCHANT", "SECRET_TITLE", "SECRET_TEXT", "SECRET_EXPLANATION")
        insertRealShapePendingReview(db, 2, "KEEP_MERCHANT2", null, null, null)
        db.close()

        anonymizer.sanitizeExport(file)

        assertNull(queryString(file, "SELECT explanation FROM pending_reviews WHERE id=1"))
        assertNull(queryString(file, "SELECT notificationText FROM pending_reviews WHERE id=1"))
        assertNull(queryString(file, "SELECT notificationTitle FROM pending_reviews WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM pending_reviews WHERE explanation LIKE '%SECRET%'"), 0.0)
        // Other fields follow the existing rule: structural fields preserved.
        assertEquals("KEEP_MERCHANT", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=1"))
        assertEquals("KEYWORD", queryString(file, "SELECT matchType FROM pending_reviews WHERE id=1"))
        assertEquals("PENDING", queryString(file, "SELECT status FROM pending_reviews WHERE id=1"))
        assertEquals("com.bank.app", queryString(file, "SELECT packageName FROM pending_reviews WHERE id=1"))
        assertEquals("EUR", queryString(file, "SELECT suggestedCurrency FROM pending_reviews WHERE id=1"))
        assertEquals(12.5, queryDouble(file, "SELECT suggestedAmount FROM pending_reviews WHERE id=1"), 0.0)
        // Rows retained (no deletion), untouched row intact.
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM pending_reviews"), 0.0)
        assertEquals("KEEP_MERCHANT2", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=2"))
    }

    @Test
    fun sanitizeExport_nulls_pending_review_explanation_when_notification_text_and_title_are_null() {
        val file = tempFolder.newFile("pending_reviews_explanation_only.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createRealShapePendingReviews(db)
        // Row 1: only explanation is sensitive. Row 2: all three sensitive. Row 3: nothing sensitive.
        insertRealShapePendingReview(db, 1, "KEEP_M1", null, null, "SECRET_EXPLANATION_ONLY")
        insertRealShapePendingReview(db, 2, "KEEP_M2", "SECRET_TITLE", "SECRET_TEXT", "SECRET_EXPLANATION")
        insertRealShapePendingReview(db, 3, "KEEP_M3", null, null, null)
        db.close()

        val messages = captureTimber { anonymizer.sanitizeExport(file) }

        assertNull(queryString(file, "SELECT explanation FROM pending_reviews WHERE id=1"))
        assertNull(queryString(file, "SELECT explanation FROM pending_reviews WHERE id=2"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM pending_reviews WHERE explanation IS NOT NULL"), 0.0)
        assertEquals(3.0, queryDouble(file, "SELECT COUNT(*) FROM pending_reviews"), 0.0)
        assertEquals("KEEP_M1", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=1"))
        assertEquals("KEEP_M3", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=3"))
        // The sanitiser reports (via its bounded summary line) exactly the two rows it changed.
        assertTrue(
            "expected summary to report pendingReviews=2, got $messages",
            messages.any { it.contains("pendingReviews=2 ") }
        )
    }

    @Test
    fun sanitizeExport_pending_reviews_explanation_only_row_is_counted_as_one() {
        val file = tempFolder.newFile("pending_reviews_explanation_only_count.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createRealShapePendingReviews(db)
        insertRealShapePendingReview(db, 1, "KEEP_M1", null, null, "SECRET_EXPLANATION_ONLY")
        db.close()

        val messages = captureTimber { anonymizer.sanitizeExport(file) }

        assertNull(queryString(file, "SELECT explanation FROM pending_reviews WHERE id=1"))
        assertTrue(
            "explanation-only row must be counted, got $messages",
            messages.any { it.contains("pendingReviews=1 ") }
        )
    }

    @Test
    fun sanitizeExport_pending_reviews_second_pass_is_idempotent_and_counts_zero() {
        val file = tempFolder.newFile("pending_reviews_idempotent.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createRealShapePendingReviews(db)
        insertRealShapePendingReview(db, 1, "KEEP_M1", "SECRET_TITLE", "SECRET_TEXT", "SECRET_EXPLANATION")
        db.close()

        anonymizer.sanitizeExport(file)
        val messages = captureTimber { anonymizer.sanitizeExport(file) }

        assertNull(queryString(file, "SELECT explanation FROM pending_reviews WHERE id=1"))
        assertEquals(1.0, queryDouble(file, "SELECT COUNT(*) FROM pending_reviews"), 0.0)
        assertEquals("KEEP_M1", queryString(file, "SELECT suggestedMerchant FROM pending_reviews WHERE id=1"))
        assertTrue(
            "second pass must report pendingReviews=0, got $messages",
            messages.any { it.contains("pendingReviews=0 ") }
        )
    }

    @Test
    fun sanitizeExport_pending_reviews_without_explanation_column_still_counts_and_redacts() {
        // Legacy shape (no explanation column): column guard must not break the other fields.
        val file = tempFolder.newFile("pending_reviews_legacy_count.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE pending_reviews (id INTEGER PRIMARY KEY, notificationText TEXT, notificationTitle TEXT, suggestedMerchant TEXT)")
        db.execSQL("INSERT INTO pending_reviews(id, notificationText, notificationTitle, suggestedMerchant) VALUES (1, 'SECRET_TEXT', NULL, 'KEEP_M1')")
        db.execSQL("INSERT INTO pending_reviews(id, notificationText, notificationTitle, suggestedMerchant) VALUES (2, NULL, NULL, 'KEEP_M2')")
        db.close()

        val messages = captureTimber { anonymizer.sanitizeExport(file) }

        assertNull(queryString(file, "SELECT notificationText FROM pending_reviews WHERE id=1"))
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM pending_reviews"), 0.0)
        assertTrue(
            "expected pendingReviews=1, got $messages",
            messages.any { it.contains("pendingReviews=1 ") }
        )
    }

    // ── S5: diagnostic free-text scrub (redacted mode only) ──────────────────

    @Test
    fun sanitizeExport_nulls_pipeline_diagnostic_event_free_text_and_keeps_rows() {
        val file = tempFolder.newFile("pipeline_diag.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE pipeline_diagnostic_events (id INTEGER PRIMARY KEY, pipeline TEXT NOT NULL, stage TEXT NOT NULL, message TEXT, exceptionClass TEXT, exceptionMessage TEXT)")
        db.execSQL("INSERT INTO pipeline_diagnostic_events(id, pipeline, stage, message, exceptionClass, exceptionMessage) VALUES (1, 'NOTIFICATION', 'PARSE', 'secret-456 message', 'IllegalStateException', 'secret-456 exception')")
        db.execSQL("INSERT INTO pipeline_diagnostic_events(id, pipeline, stage, message, exceptionClass, exceptionMessage) VALUES (2, 'NOTIFICATION', 'SAVE', NULL, NULL, NULL)")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT message FROM pipeline_diagnostic_events WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT exceptionMessage FROM pipeline_diagnostic_events WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM pipeline_diagnostic_events WHERE message LIKE '%secret-456%' OR exceptionMessage LIKE '%secret-456%'"), 0.0)
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM pipeline_diagnostic_events"), 0.0)
        assertEquals("IllegalStateException", queryString(file, "SELECT exceptionClass FROM pipeline_diagnostic_events WHERE id=1"))
        assertEquals("PARSE", queryString(file, "SELECT stage FROM pipeline_diagnostic_events WHERE id=1"))
    }

    @Test
    fun sanitizeExport_nulls_operation_run_event_exception_message_and_keeps_rows() {
        val file = tempFolder.newFile("run_events_diag.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE operation_run_events (id INTEGER PRIMARY KEY, stage TEXT NOT NULL, exceptionClass TEXT, exceptionMessage TEXT)")
        db.execSQL("INSERT INTO operation_run_events(id, stage, exceptionClass, exceptionMessage) VALUES (1, 'VERIFY', 'IOException', 'secret-456 path')")
        db.execSQL("INSERT INTO operation_run_events(id, stage, exceptionClass, exceptionMessage) VALUES (2, 'DONE', NULL, NULL)")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT exceptionMessage FROM operation_run_events WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM operation_run_events WHERE exceptionMessage LIKE '%secret-456%'"), 0.0)
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM operation_run_events"), 0.0)
        assertEquals("IOException", queryString(file, "SELECT exceptionClass FROM operation_run_events WHERE id=1"))
    }

    @Test
    fun sanitizeExport_nulls_operation_run_error_summary_and_keeps_rows() {
        val file = tempFolder.newFile("runs_diag.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE operation_runs (id INTEGER PRIMARY KEY, correlationId TEXT NOT NULL, status TEXT NOT NULL, errorSummary TEXT)")
        db.execSQL("INSERT INTO operation_runs(id, correlationId, status, errorSummary) VALUES (1, 'c1', 'FAILED_FINAL', 'secret-456 summary')")
        db.execSQL("INSERT INTO operation_runs(id, correlationId, status, errorSummary) VALUES (2, 'c2', 'SUCCESS', NULL)")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT errorSummary FROM operation_runs WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM operation_runs WHERE errorSummary LIKE '%secret-456%'"), 0.0)
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM operation_runs"), 0.0)
        assertEquals("FAILED_FINAL", queryString(file, "SELECT status FROM operation_runs WHERE id=1"))
    }

    @Test
    fun sanitizeExport_nulls_background_job_run_error_message_and_keeps_rows() {
        val file = tempFolder.newFile("job_runs_diag.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE background_job_runs (id INTEGER PRIMARY KEY, workerName TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, errorClass TEXT)")
        db.execSQL("INSERT INTO background_job_runs(id, workerName, status, errorMessage, errorClass) VALUES (1, 'w', 'FAILED', 'secret-789 path', 'IOException')")
        db.execSQL("INSERT INTO background_job_runs(id, workerName, status, errorMessage, errorClass) VALUES (2, 'w', 'SUCCESS', NULL, NULL)")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT errorMessage FROM background_job_runs WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM background_job_runs WHERE errorMessage LIKE '%secret-789%'"), 0.0)
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM background_job_runs"), 0.0)
        assertEquals("IOException", queryString(file, "SELECT errorClass FROM background_job_runs WHERE id=1"))
        assertEquals("FAILED", queryString(file, "SELECT status FROM background_job_runs WHERE id=1"))
    }

    @Test
    fun sanitizeExport_nulls_receipt_event_error_details_and_keeps_rows() {
        val file = tempFolder.newFile("receipt_events_diag.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE receipt_events (id INTEGER PRIMARY KEY, eventType TEXT NOT NULL, errorDetails TEXT)")
        db.execSQL("INSERT INTO receipt_events(id, eventType, errorDetails) VALUES (1, 'ASSET_DELETE_FAILED', 'secret-789 /data/user/0/file.jpg')")
        db.execSQL("INSERT INTO receipt_events(id, eventType, errorDetails) VALUES (2, 'CREATED', NULL)")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT errorDetails FROM receipt_events WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM receipt_events WHERE errorDetails LIKE '%secret-789%'"), 0.0)
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM receipt_events"), 0.0)
        assertEquals("ASSET_DELETE_FAILED", queryString(file, "SELECT eventType FROM receipt_events WHERE id=1"))
    }

    @Test
    fun sanitizeExport_diagnostic_scrub_tolerates_tables_without_the_columns() {
        val file = tempFolder.newFile("diag_old_schema.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE operation_runs (id INTEGER PRIMARY KEY, status TEXT NOT NULL)")
        db.execSQL("INSERT INTO operation_runs(id, status) VALUES (1, 'SUCCESS')")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals("SUCCESS", queryString(file, "SELECT status FROM operation_runs WHERE id=1"))
    }

    @Test
    fun sanitizeExport_redacts_bank_statement_import_items_merchant() {
        val file = tempFolder.newFile("bank_items.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE bank_statement_import_items (id INTEGER PRIMARY KEY, merchant TEXT, amount REAL, transactionFingerprint TEXT)")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount, transactionFingerprint) VALUES (1, 'SECRET_MERCHANT', 42.5, 'secretmerchantkey')")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount, transactionFingerprint) VALUES (2, NULL, 10.0, 'secretfingerprintonly')")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals("[REDACTED]", queryString(file, "SELECT merchant FROM bank_statement_import_items WHERE id=1"))
        // P-08-4: the legacy plaintext merchant key in transactionFingerprint is cleaned too.
        assertEquals(null, queryString(file, "SELECT transactionFingerprint FROM bank_statement_import_items WHERE id=1"))
        // Row 2 merchant is null: stays null (no [REDACTED] gained), but its fingerprint is cleaned.
        assertEquals(null, queryString(file, "SELECT merchant FROM bank_statement_import_items WHERE id=2"))
        assertEquals(null, queryString(file, "SELECT transactionFingerprint FROM bank_statement_import_items WHERE id=2"))
        // Structure preserved.
        assertEquals(42.5, queryDouble(file, "SELECT amount FROM bank_statement_import_items WHERE id=1"), 0.0)
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM bank_statement_import_items"), 0.0)
    }

    @Test
    fun sanitizeExport_bank_items_null_merchant_with_fingerprint_is_counted_and_never_gains_redacted_marker() {
        val file = tempFolder.newFile("bank_items_fp_only.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE bank_statement_import_items (id INTEGER PRIMARY KEY, merchant TEXT, amount REAL, transactionFingerprint TEXT)")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount, transactionFingerprint) VALUES (1, NULL, 10.0, 'secretfingerprintonly')")
        // Already clean: not counted.
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount, transactionFingerprint) VALUES (2, NULL, 11.0, NULL)")
        db.close()

        val messages = captureTimber { anonymizer.sanitizeExport(file) }

        assertEquals(null, queryString(file, "SELECT transactionFingerprint FROM bank_statement_import_items WHERE id=1"))
        assertEquals(0.0, queryDouble(file, "SELECT COUNT(*) FROM bank_statement_import_items WHERE merchant IS NOT NULL"), 0.0)
        assertTrue(
            "fingerprint-only row must be counted exactly once, got $messages",
            messages.any { it.contains("bankImportItems=1 ") }
        )
    }

    @Test
    fun sanitizeExport_bank_items_without_fingerprint_column_still_redacts_merchant() {
        val file = tempFolder.newFile("bank_items_legacy_no_fp.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE bank_statement_import_items (id INTEGER PRIMARY KEY, merchant TEXT, amount REAL)")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount) VALUES (1, 'SECRET_MERCHANT', 42.5)")
        db.execSQL("INSERT INTO bank_statement_import_items(id, merchant, amount) VALUES (2, NULL, 10.0)")
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals("[REDACTED]", queryString(file, "SELECT merchant FROM bank_statement_import_items WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT merchant FROM bank_statement_import_items WHERE id=2"))
        assertEquals(42.5, queryDouble(file, "SELECT amount FROM bank_statement_import_items WHERE id=1"), 0.0)
    }

    // ── S1: user_corrections and receipt_item_categorizations export scrub ────

    @Test
    fun sanitizeExport_redacts_user_corrections_notification_text_and_keeps_structural_fields() {
        val file = tempFolder.newFile("user_corrections_export.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """CREATE TABLE user_corrections (
                id INTEGER PRIMARY KEY,
                packageName TEXT NOT NULL,
                originalMerchant TEXT NOT NULL,
                correctedMerchant TEXT,
                originalAmount REAL NOT NULL,
                wasApproved INTEGER NOT NULL,
                notificationTitle TEXT,
                notificationText TEXT,
                createdAt INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            "INSERT INTO user_corrections(id, packageName, originalMerchant, correctedMerchant, originalAmount, wasApproved, notificationTitle, notificationText, createdAt) " +
                "VALUES (1, 'com.bank.app', 'KEEP_ORIG_MERCHANT', 'KEEP_CORR_MERCHANT', 15.75, 1, 'SECRET_TITLE', 'SECRET_BODY_TEXT', 1700000000000)"
        )
        db.execSQL(
            "INSERT INTO user_corrections(id, packageName, originalMerchant, correctedMerchant, originalAmount, wasApproved, notificationTitle, notificationText, createdAt) " +
                "VALUES (2, 'com.bank.app', 'KEEP_ORIG_2', NULL, 8.0, 0, NULL, NULL, 1700000000000)"
        )
        db.close()

        anonymizer.sanitizeExport(file)

        assertNull(queryString(file, "SELECT notificationTitle FROM user_corrections WHERE id=1"))
        assertNull(queryString(file, "SELECT notificationText FROM user_corrections WHERE id=1"))
        assertEquals("KEEP_ORIG_MERCHANT", queryString(file, "SELECT originalMerchant FROM user_corrections WHERE id=1"))
        assertEquals("KEEP_CORR_MERCHANT", queryString(file, "SELECT correctedMerchant FROM user_corrections WHERE id=1"))
        assertEquals(15.75, queryDouble(file, "SELECT originalAmount FROM user_corrections WHERE id=1"), 0.0)
        // Row 2 already null — preserved as-is, TIER_1_EXACT row count intact
        assertEquals("KEEP_ORIG_2", queryString(file, "SELECT originalMerchant FROM user_corrections WHERE id=2"))
        assertEquals(2.0, queryDouble(file, "SELECT COUNT(*) FROM user_corrections"), 0.0)
    }

    @Test
    fun sanitizeExport_redacts_receipt_item_categorizations_and_preserves_unrelated_table() {
        val file = tempFolder.newFile("receipt_items_export.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """CREATE TABLE receipt_item_categorizations (
                id INTEGER PRIMARY KEY,
                receiptId INTEGER NOT NULL,
                expenseId INTEGER,
                itemDescription TEXT NOT NULL,
                itemAmount REAL NOT NULL,
                suggestedCategoryId INTEGER,
                suggestedCategoryName TEXT,
                confidence REAL NOT NULL,
                aiRationale TEXT,
                alternativeCategoriesJson TEXT,
                userCorrectedCategoryId INTEGER,
                userCorrectedCategoryName TEXT
            )"""
        )
        db.execSQL(
            "INSERT INTO receipt_item_categorizations(id, receiptId, expenseId, itemDescription, itemAmount, suggestedCategoryId, suggestedCategoryName, confidence, aiRationale, alternativeCategoriesJson, userCorrectedCategoryId, userCorrectedCategoryName) " +
                "VALUES (1, 10, 20, 'SECRET_LINE_ITEM_DESC', 12.99, 5, 'SECRET_SUGGESTED_CAT', 0.91, 'SECRET_AI_RATIONALE', '[{\"id\":1,\"name\":\"SECRET_ALT\"}]', 6, 'SECRET_USER_CAT')"
        )
        // Negative control: an unrelated table with sensitive-looking columns must NOT be touched by the scrub
        db.execSQL(
            """CREATE TABLE categories (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                icon TEXT NOT NULL,
                color TEXT NOT NULL
            )"""
        )
        db.execSQL(
            "INSERT INTO categories(id, name, icon, color) VALUES (5, 'KEEP_CATEGORY_NAME', 'cart', '#FF0000')"
        )
        db.close()

        anonymizer.sanitizeExport(file)

        val redactedDesc = queryString(file, "SELECT itemDescription FROM receipt_item_categorizations WHERE id=1")
        assertNotNull("itemDescription is NOT NULL in schema and must not be nulled", redactedDesc)
        assertTrue(
            "itemDescription must be redacted to '' or '[REDACTED]', got '$redactedDesc'",
            redactedDesc == "" || redactedDesc == "[REDACTED]"
        )
        assertFalse("raw itemDescription must not survive", redactedDesc!!.contains("SECRET_LINE_ITEM_DESC"))
        assertNull(queryString(file, "SELECT aiRationale FROM receipt_item_categorizations WHERE id=1"))
        assertNull(queryString(file, "SELECT suggestedCategoryName FROM receipt_item_categorizations WHERE id=1"))
        assertNull(queryString(file, "SELECT alternativeCategoriesJson FROM receipt_item_categorizations WHERE id=1"))
        // R-RCPT-1: userCorrectedCategoryName is a category label, not raw receipt text: PRESERVED.
        assertEquals(
            "SECRET_USER_CAT",
            queryString(file, "SELECT userCorrectedCategoryName FROM receipt_item_categorizations WHERE id=1")
        )
        assertEquals(12.99, queryDouble(file, "SELECT itemAmount FROM receipt_item_categorizations WHERE id=1"), 0.0)
        assertEquals(1.0, queryDouble(file, "SELECT COUNT(*) FROM receipt_item_categorizations"), 0.0)

        // Negative control assertion: unrelated table is completely untouched
        assertEquals("KEEP_CATEGORY_NAME", queryString(file, "SELECT name FROM categories WHERE id=5"))
        assertEquals("cart", queryString(file, "SELECT icon FROM categories WHERE id=5"))
        assertEquals("#FF0000", queryString(file, "SELECT color FROM categories WHERE id=5"))
    }

    @Test
    fun sanitizeExport_leaves_receipt_item_row_untouched_when_only_userCorrectedCategoryName_is_set() {
        val file = tempFolder.newFile("receipt_items_only_category_export.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """CREATE TABLE receipt_item_categorizations (
                id INTEGER PRIMARY KEY,
                receiptId INTEGER NOT NULL,
                expenseId INTEGER,
                itemDescription TEXT NOT NULL,
                itemAmount REAL NOT NULL,
                suggestedCategoryId INTEGER,
                suggestedCategoryName TEXT,
                confidence REAL NOT NULL,
                aiRationale TEXT,
                alternativeCategoriesJson TEXT,
                userCorrectedCategoryId INTEGER,
                userCorrectedCategoryName TEXT
            )"""
        )
        // Already-scrubbed shape: itemDescription '[REDACTED]', every other sensitive column NULL,
        // only the category label remains.
        db.execSQL(
            "INSERT INTO receipt_item_categorizations(id, receiptId, expenseId, itemDescription, itemAmount, suggestedCategoryId, suggestedCategoryName, confidence, aiRationale, alternativeCategoriesJson, userCorrectedCategoryId, userCorrectedCategoryName) " +
                "VALUES (1, 10, 20, '[REDACTED]', 4.5, 5, NULL, 0.7, NULL, NULL, 6, 'KEEP_USER_CAT')"
        )
        db.close()

        anonymizer.sanitizeExport(file)

        assertEquals("[REDACTED]", queryString(file, "SELECT itemDescription FROM receipt_item_categorizations WHERE id=1"))
        assertNull(queryString(file, "SELECT aiRationale FROM receipt_item_categorizations WHERE id=1"))
        assertNull(queryString(file, "SELECT suggestedCategoryName FROM receipt_item_categorizations WHERE id=1"))
        assertNull(queryString(file, "SELECT alternativeCategoriesJson FROM receipt_item_categorizations WHERE id=1"))
        assertEquals(
            "KEEP_USER_CAT",
            queryString(file, "SELECT userCorrectedCategoryName FROM receipt_item_categorizations WHERE id=1")
        )
        assertEquals(4.5, queryDouble(file, "SELECT itemAmount FROM receipt_item_categorizations WHERE id=1"), 0.0)
        assertEquals(1.0, queryDouble(file, "SELECT COUNT(*) FROM receipt_item_categorizations"), 0.0)
    }
}
