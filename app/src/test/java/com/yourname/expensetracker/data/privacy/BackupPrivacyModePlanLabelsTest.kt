package com.yourname.expensetracker.data.privacy

import android.database.sqlite.SQLiteDatabase
import com.yourname.expensetracker.domain.backup.BackupPrivacyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * IM-9 (owner-approved 2026-10-23, loan installments INV-31): every BackupPrivacyMode with
 * redactsRawText = true, and ANONYMIZED_EXPORT, blanks the plan displayName and lenderLabel.
 *
 * DatabaseBackupRepositoryImpl calls ExportAnonymizer.sanitizeExport exactly when the resolved
 * mode redacts raw text, so each mode is modelled by that same decision. The modes are enumerated
 * from the enum, not hardcoded, so a newly added redacting mode is covered automatically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupPrivacyModePlanLabelsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val anonymizer = ExportAnonymizer()

    private fun seedPlans(file: File) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                "CREATE TABLE installment_plans (id INTEGER PRIMARY KEY, planUuid TEXT NOT NULL, " +
                    "displayName TEXT NOT NULL, lenderLabel TEXT, currency TEXT NOT NULL, " +
                    "installmentCount INTEGER NOT NULL, firstDueDate INTEGER NOT NULL, status TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO installment_plans VALUES (1, 'uuid-1', 'SECRET_PLAN_NAME', 'SECRET_LENDER', " +
                    "'EUR', 12, 20000, 'ACTIVE')"
            )
            db.execSQL(
                "INSERT INTO installment_plans VALUES (2, 'uuid-2', 'SECRET_PLAN_TWO', NULL, " +
                    "'USD', 240, 21000, 'COMPLETED')"
            )
        } finally {
            db.close()
        }
    }

    private fun <T> query(file: File, sql: String, read: (android.database.Cursor) -> T): T {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(sql, null).use { c ->
                check(c.moveToFirst())
                read(c)
            }
        } finally {
            db.close()
        }
    }

    /** Mirrors DatabaseBackupRepositoryImpl: sanitise only when the mode redacts raw text. */
    private fun exportWith(mode: BackupPrivacyMode, file: File) {
        if (mode.redactsRawText) anonymizer.sanitizeExport(file)
    }

    private fun assertLabelsBlanked(file: File, context: String) {
        assertEquals(context, 2, query(file, "SELECT COUNT(*) FROM installment_plans") { it.getInt(0) })
        assertEquals(
            context,
            0,
            query(file, "SELECT COUNT(*) FROM installment_plans WHERE displayName <> ''") { it.getInt(0) }
        )
        assertEquals(
            context,
            0,
            query(file, "SELECT COUNT(*) FROM installment_plans WHERE lenderLabel IS NOT NULL") { it.getInt(0) }
        )
    }

    private fun assertStructureKept(file: File) {
        assertEquals("uuid-1", query(file, "SELECT planUuid FROM installment_plans WHERE id = 1") { it.getString(0) })
        assertEquals(12, query(file, "SELECT installmentCount FROM installment_plans WHERE id = 1") { it.getInt(0) })
        assertEquals("EUR", query(file, "SELECT currency FROM installment_plans WHERE id = 1") { it.getString(0) })
        assertEquals(20000L, query(file, "SELECT firstDueDate FROM installment_plans WHERE id = 1") { it.getLong(0) })
        assertEquals("ACTIVE", query(file, "SELECT status FROM installment_plans WHERE id = 1") { it.getString(0) })
        assertEquals(240, query(file, "SELECT installmentCount FROM installment_plans WHERE id = 2") { it.getInt(0) })
    }

    @Test
    fun anonymized_export_is_a_redacting_mode_and_blanks_plan_labels() {
        assertTrue(BackupPrivacyMode.ANONYMIZED_EXPORT.redactsRawText)
        val file = File(tempFolder.root, "anonymized.db")
        seedPlans(file)

        exportWith(BackupPrivacyMode.ANONYMIZED_EXPORT, file)

        assertLabelsBlanked(file, "ANONYMIZED_EXPORT")
        assertStructureKept(file)
    }

    @Test
    fun every_redacts_raw_text_mode_blanks_plan_labels() {
        val redactingModes = BackupPrivacyMode.values().filter { it.redactsRawText }
        assertTrue("at least ANONYMIZED_EXPORT must redact", redactingModes.isNotEmpty())
        assertTrue(redactingModes.contains(BackupPrivacyMode.ANONYMIZED_EXPORT))

        for (mode in redactingModes) {
            val file = File(tempFolder.root, "mode-${mode.name}.db")
            seedPlans(file)

            exportWith(mode, file)

            assertLabelsBlanked(file, mode.name)
            assertStructureKept(file)
        }
    }

    @Test
    fun non_redacting_modes_keep_plan_labels() {
        val keepingModes = BackupPrivacyMode.values().filter { !it.redactsRawText }
        assertTrue(keepingModes.contains(BackupPrivacyMode.FULL_ENCRYPTED))

        for (mode in keepingModes) {
            val file = File(tempFolder.root, "keep-${mode.name}.db")
            seedPlans(file)

            exportWith(mode, file)

            assertEquals(
                mode.name,
                "SECRET_PLAN_NAME",
                query(file, "SELECT displayName FROM installment_plans WHERE id = 1") { it.getString(0) }
            )
            assertEquals(
                mode.name,
                "SECRET_LENDER",
                query(file, "SELECT lenderLabel FROM installment_plans WHERE id = 1") { it.getString(0) }
            )
        }
    }

    @Test
    fun lender_label_is_cleared_to_null_and_display_name_to_empty_string() {
        val file = File(tempFolder.root, "shape.db")
        seedPlans(file)

        anonymizer.sanitizeExport(file)

        assertEquals("", query(file, "SELECT displayName FROM installment_plans WHERE id = 1") { it.getString(0) })
        assertTrue(query(file, "SELECT lenderLabel FROM installment_plans WHERE id = 1") { it.isNull(0) })
        assertNull(query(file, "SELECT lenderLabel FROM installment_plans WHERE id = 2") { if (it.isNull(0)) null else it.getString(0) })
    }

    @Test
    fun sanitising_twice_is_idempotent_and_a_database_without_the_plan_table_is_untouched() {
        val file = File(tempFolder.root, "twice.db")
        seedPlans(file)
        anonymizer.sanitizeExport(file)
        anonymizer.sanitizeExport(file)
        assertLabelsBlanked(file, "second pass")
        assertStructureKept(file)

        val older = File(tempFolder.root, "older.db")
        older.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(older, null).use { it.execSQL("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)") }
        anonymizer.sanitizeExport(older)
        assertEquals(0, query(older, "SELECT COUNT(*) FROM unrelated") { it.getInt(0) })
    }

    @Test
    fun repository_sanitises_exactly_when_the_resolved_mode_redacts_raw_text() {
        val source = listOf(
            File("src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt"),
            File("app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt")
        ).first { it.exists() }.readText()

        assertTrue(source.contains("privacyMode?.redactsRawText ?: redacted"))
        assertTrue(Regex("if \\(resolvedRedacted\\) \\{\\s*exportAnonymizer\\.sanitizeExport\\(tempDb\\)").containsMatchIn(source))
        assertFalse(
            "ANONYMIZED_EXPORT must not be special-cased away from the redacting path",
            source.contains("ANONYMIZED_EXPORT -> false")
        )
    }
}
