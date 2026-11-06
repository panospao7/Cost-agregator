package com.yourname.expensetracker.data.privacy

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File

/**
 * P-01-4 review fix: the encrypted transient notification payload
 * (`transientPayloadCiphertext` / `Nonce` / `Version`) must not survive an
 * anonymized export, even when no plaintext column is set on the row, and older
 * schemas without those columns must still be sanitised without error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportAnonymizerTransientPayloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val anonymizer = ExportAnonymizer()
    private val loggedLines = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            loggedLines += message
        }
    }

    @Before
    fun plantTree() {
        Timber.plant(tree)
    }

    @After
    fun uprootTree() {
        Timber.uproot(tree)
    }

    private fun createDb(name: String, ddl: String, vararg inserts: String): File {
        val file = tempFolder.newFile(name)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(ddl)
            inserts.forEach { db.execSQL(it) }
        } finally {
            db.close()
        }
        return file
    }

    private fun withTransientColumns(name: String, vararg inserts: String): File = createDb(
        name,
        "CREATE TABLE notification_intake (id INTEGER PRIMARY KEY, title TEXT, text TEXT, bigText TEXT, " +
            "subText TEXT, extrasJson TEXT, dedupeFingerprint TEXT, contentHash TEXT, " +
            "transientPayloadCiphertext BLOB, transientPayloadNonce BLOB, transientPayloadVersion INTEGER)",
        *inserts
    )

    private fun queryLong(file: File, sql: String): Long {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
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

    private fun nonNullTransientCount(file: File): Long = queryLong(
        file,
        "SELECT COUNT(*) FROM notification_intake WHERE transientPayloadCiphertext IS NOT NULL " +
            "OR transientPayloadNonce IS NOT NULL OR transientPayloadVersion IS NOT NULL"
    )

    private fun reportedIntakeCount(): String {
        val line = loggedLines.last { it.contains("notificationIntake=") }
        return line.substringAfter("notificationIntake=").substringBefore(' ')
    }

    @Test
    fun `row with only transient payload columns set has all three nulled`() {
        val file = withTransientColumns(
            "transient_only.db",
            "INSERT INTO notification_intake(id, dedupeFingerprint, contentHash, transientPayloadCiphertext, " +
                "transientPayloadNonce, transientPayloadVersion) " +
                "VALUES (1, 'KEEP_FP', 'KEEP_HASH', x'DEADBEEF', x'0102030405060708090A0B0C', 1)"
        )

        anonymizer.sanitizeExport(file)

        assertEquals(0L, queryLong(file, "SELECT COUNT(*) FROM notification_intake WHERE transientPayloadCiphertext IS NOT NULL"))
        assertEquals(0L, queryLong(file, "SELECT COUNT(*) FROM notification_intake WHERE transientPayloadNonce IS NOT NULL"))
        assertEquals(0L, queryLong(file, "SELECT COUNT(*) FROM notification_intake WHERE transientPayloadVersion IS NOT NULL"))
        // Row retained; dedup identity preserved.
        assertEquals(1L, queryLong(file, "SELECT COUNT(*) FROM notification_intake"))
        assertEquals("KEEP_FP", queryString(file, "SELECT dedupeFingerprint FROM notification_intake WHERE id=1"))
        assertEquals("KEEP_HASH", queryString(file, "SELECT contentHash FROM notification_intake WHERE id=1"))
    }

    @Test
    fun `row with only the version column set still has it nulled`() {
        val file = withTransientColumns(
            "version_only.db",
            "INSERT INTO notification_intake(id, transientPayloadVersion) VALUES (1, 2)"
        )

        anonymizer.sanitizeExport(file)

        assertEquals(0L, nonNullTransientCount(file))
    }

    @Test
    fun `row with plaintext and transient columns has both cleared`() {
        val file = withTransientColumns(
            "plaintext_and_transient.db",
            "INSERT INTO notification_intake(id, title, text, bigText, subText, extrasJson, dedupeFingerprint, " +
                "contentHash, transientPayloadCiphertext, transientPayloadNonce, transientPayloadVersion) " +
                "VALUES (1, 'SECRET_TITLE', 'SECRET_TEXT', 'SECRET_BIG', 'SECRET_SUB', 'SECRET_EXTRAS', " +
                "'KEEP_FP', 'KEEP_HASH', x'DEADBEEF', x'0102030405060708090A0B0C', 1)"
        )

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT title FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT text FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT bigText FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT subText FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT extrasJson FROM notification_intake WHERE id=1"))
        assertEquals(0L, nonNullTransientCount(file))
        assertEquals("KEEP_FP", queryString(file, "SELECT dedupeFingerprint FROM notification_intake WHERE id=1"))
    }

    @Test
    fun `row with no plaintext and no transient data is left unchanged`() {
        val file = withTransientColumns(
            "clean_row.db",
            "INSERT INTO notification_intake(id, dedupeFingerprint, contentHash) VALUES (1, 'KEEP_FP', 'KEEP_HASH')"
        )

        anonymizer.sanitizeExport(file)

        assertEquals(1L, queryLong(file, "SELECT COUNT(*) FROM notification_intake"))
        assertEquals("KEEP_FP", queryString(file, "SELECT dedupeFingerprint FROM notification_intake WHERE id=1"))
        assertEquals("KEEP_HASH", queryString(file, "SELECT contentHash FROM notification_intake WHERE id=1"))
        assertEquals(0L, nonNullTransientCount(file))
    }

    @Test
    fun `older schema without transient columns does not throw and still clears plaintext`() {
        val file = createDb(
            "legacy_schema.db",
            "CREATE TABLE notification_intake (id INTEGER PRIMARY KEY, title TEXT, text TEXT, bigText TEXT, " +
                "subText TEXT, extrasJson TEXT, dedupeFingerprint TEXT, contentHash TEXT)",
            "INSERT INTO notification_intake(id, title, text, bigText, subText, extrasJson, dedupeFingerprint, contentHash) " +
                "VALUES (1, 'SECRET_TITLE', 'SECRET_TEXT', 'SECRET_BIG', 'SECRET_SUB', 'SECRET_EXTRAS', 'KEEP_FP', 'KEEP_HASH')"
        )

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT title FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT text FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT bigText FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT subText FROM notification_intake WHERE id=1"))
        assertEquals(null, queryString(file, "SELECT extrasJson FROM notification_intake WHERE id=1"))
        assertEquals("KEEP_FP", queryString(file, "SELECT dedupeFingerprint FROM notification_intake WHERE id=1"))
    }

    @Test
    fun `partial schema with only the ciphertext column nulls it without error`() {
        val file = createDb(
            "partial_schema.db",
            "CREATE TABLE notification_intake (id INTEGER PRIMARY KEY, title TEXT, transientPayloadCiphertext BLOB)",
            "INSERT INTO notification_intake(id, title, transientPayloadCiphertext) VALUES (1, 'SECRET_TITLE', x'DEADBEEF')"
        )

        anonymizer.sanitizeExport(file)

        assertEquals(null, queryString(file, "SELECT title FROM notification_intake WHERE id=1"))
        assertEquals(
            0L,
            queryLong(file, "SELECT COUNT(*) FROM notification_intake WHERE transientPayloadCiphertext IS NOT NULL")
        )
    }

    @Test
    fun `reported intake count covers transient-only rows`() {
        val file = withTransientColumns(
            "count_transient_only.db",
            "INSERT INTO notification_intake(id, transientPayloadCiphertext, transientPayloadNonce, transientPayloadVersion) " +
                "VALUES (1, x'AA', x'BB', 1)",
            "INSERT INTO notification_intake(id, transientPayloadCiphertext, transientPayloadNonce, transientPayloadVersion) " +
                "VALUES (2, x'CC', x'DD', 1)"
        )

        anonymizer.sanitizeExport(file)

        // Two rows carry only transient data: plaintext count is 0, transient count is 2, maxOf = 2.
        assertEquals("2", reportedIntakeCount())
    }

    @Test
    fun `reported intake count is the larger of plaintext rows and transient rows`() {
        val file = withTransientColumns(
            "count_mixed.db",
            // Three rows with plaintext, only one of which also has a transient payload.
            "INSERT INTO notification_intake(id, title) VALUES (1, 'SECRET_A')",
            "INSERT INTO notification_intake(id, text) VALUES (2, 'SECRET_B')",
            "INSERT INTO notification_intake(id, bigText, transientPayloadCiphertext) VALUES (3, 'SECRET_C', x'AA')"
        )

        anonymizer.sanitizeExport(file)

        assertEquals("3", reportedIntakeCount())
        assertEquals(0L, nonNullTransientCount(file))
    }

    @Test
    fun `reported intake count is zero for a clean table`() {
        val file = withTransientColumns(
            "count_clean.db",
            "INSERT INTO notification_intake(id, dedupeFingerprint) VALUES (1, 'KEEP_FP')"
        )

        anonymizer.sanitizeExport(file)

        assertEquals("0", reportedIntakeCount())
    }
}
