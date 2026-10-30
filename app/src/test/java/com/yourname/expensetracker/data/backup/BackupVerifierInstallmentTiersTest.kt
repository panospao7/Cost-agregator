package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.DatabaseSchemaPolicy
import com.yourname.expensetracker.data.database.migrationTestInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * INV-30 (loan installments, schema 152): `installment_plans` and `installment_entries`
 * are TIER_1_EXACT backup tables, required in a manifest only for databaseVersion null
 * or >= 152, and expected to be empty (count 0) after migrating a pre-152 backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierInstallmentTiersTest {

    @get:Rule
    val helper = MigrationTestHelper(
        migrationTestInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val installmentTables = setOf("installment_plans", "installment_entries")

    // ── tier assignment ──────────────────────────────────────────

    @Test
    fun INV30_both_installment_tables_are_tier_1_exact() {
        installmentTables.forEach { table ->
            assertEquals(
                "$table must be TIER_1_EXACT",
                BackupVerifier.VerificationTier.TIER_1_EXACT,
                BackupVerifier.tableTier(table)
            )
            assertTrue("$table must be a tracked table", BackupVerifier.allTableNames().contains(table))
        }
    }

    // ── manifest requirement gated by databaseVersion ────────────

    @Test
    fun INV30_requiredManifestTables_include_the_installment_tables_for_null_and_current_versions() {
        assertTrue(BackupVerifier.requiredManifestTables().containsAll(installmentTables))
        assertTrue(BackupVerifier.requiredManifestTables(databaseVersion = null).containsAll(installmentTables))
        assertTrue(BackupVerifier.requiredManifestTables(databaseVersion = 152).containsAll(installmentTables))
        assertTrue(BackupVerifier.requiredManifestTables(databaseVersion = 153).containsAll(installmentTables))
    }

    @Test
    fun INV30_requiredManifestTables_exclude_the_installment_tables_below_152() {
        listOf(149, 150, 151).forEach { version ->
            val required = BackupVerifier.requiredManifestTables(databaseVersion = version)
            assertFalse("v$version must not require installment_plans", required.contains("installment_plans"))
            assertFalse("v$version must not require installment_entries", required.contains("installment_entries"))
        }
    }

    @Test
    fun INV30_requiredManifestTables_below_152_differ_from_152_by_exactly_the_installment_tables() {
        val at151 = BackupVerifier.requiredManifestTables(databaseVersion = 151)
        val at152 = BackupVerifier.requiredManifestTables(databaseVersion = 152)

        assertEquals(installmentTables, at152 - at151)
        assertTrue("152 must only add tables over 151", at152.containsAll(at151))
    }

    @Test
    fun INV30_manifest_at_152_missing_the_installment_counts_is_rejected_and_names_them() {
        val withoutInstallments = BackupVerifier.requiredManifestTables(databaseVersion = 152)
            .filter { it !in installmentTables }
            .associateWith { 0 }

        val thrown = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(withoutInstallments, databaseVersion = 152)
        }
        assertEquals(installmentTables, thrown.missingTables.toSet())

        assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(withoutInstallments, databaseVersion = null)
        }
    }

    @Test
    fun INV30_manifest_at_151_without_the_installment_counts_is_accepted() {
        val v151Manifest = BackupVerifier.requiredManifestTables(databaseVersion = 151).associateWith { 0 }
        assertFalse(v151Manifest.keys.any { it in installmentTables })

        // Must not throw: a pre-152 backup legitimately has no installment counts.
        BackupVerifier.validateManifestCompleteness(v151Manifest, databaseVersion = 151)
    }

    @Test
    fun INV30_manifest_at_152_with_the_installment_counts_is_accepted() {
        val complete = BackupVerifier.requiredManifestTables(databaseVersion = 152).associateWith { 0 }

        BackupVerifier.validateManifestCompleteness(complete, databaseVersion = 152)
    }

    // ── expected counts after migration ──────────────────────────

    @Test
    fun INV30_expectedCountsAfterMigration_from_151_adds_both_installment_tables_at_zero() {
        val result = BackupVerifier.expectedCountsAfterMigration(mapOf("expenses" to 4), 151)

        assertEquals(0, result["installment_plans"])
        assertEquals(0, result["installment_entries"])
        assertEquals(4, result["expenses"])
    }

    @Test
    fun INV30_expectedCountsAfterMigration_from_152_and_from_unknown_do_not_add_installment_counts() {
        val counts = mapOf("expenses" to 4)

        assertEquals(counts, BackupVerifier.expectedCountsAfterMigration(counts, 152))
        assertEquals(counts, BackupVerifier.expectedCountsAfterMigration(counts, 153))
        assertEquals(counts, BackupVerifier.expectedCountsAfterMigration(counts, null))
    }

    @Test
    fun INV30_expectedCountsAfterMigration_keeps_a_count_already_present_in_the_manifest() {
        val counts = mapOf("installment_plans" to 2, "installment_entries" to 6)

        assertEquals(counts, BackupVerifier.expectedCountsAfterMigration(counts, 151))
    }

    // ── real databases ───────────────────────────────────────────

    @Test
    fun INV30_current_database_with_plans_and_entries_verifies_exactly_and_rejects_wrong_counts() {
        val name = "backup-installments-152"
        val db = helper.createDatabase(name, 152)
        seedPlanWithEntries(db)
        db.close()
        val file = databaseFile(name)
        val counts = manifestCounts(file, 152)
        assertEquals(1, counts["installment_plans"])
        assertEquals(2, counts["installment_entries"])

        val ok = BackupVerifier.verify(file, counts)
        assertTrue(ok.errors.toString(), ok.passed)
        assertEquals(1, ok.tableResults.single { it.tableName == "installment_plans" }.actualCount)
        assertEquals(2, ok.tableResults.single { it.tableName == "installment_entries" }.actualCount)

        assertFalse(BackupVerifier.verify(file, counts + ("installment_plans" to 0)).passed)
        assertFalse(BackupVerifier.verify(file, counts + ("installment_plans" to 2)).passed)
        assertFalse(BackupVerifier.verify(file, counts + ("installment_entries" to 1)).passed)
        assertFalse(BackupVerifier.verify(file, counts + ("installment_entries" to 3)).passed)
        assertFalse(BackupVerifier.verify(file, counts - "installment_entries").passed)
    }

    @Test
    fun INV30_v151_backup_migrated_to_152_verifies_the_new_tables_as_exactly_empty() {
        val name = "backup-installments-151"
        val legacy = helper.createDatabase(name, 151)
        legacy.close()
        val file = databaseFile(name)
        val v151Counts = manifestCounts(file, 151)
        assertFalse(v151Counts.keys.any { it in installmentTables })
        BackupVerifier.validateManifestCompleteness(v151Counts, databaseVersion = 151)
        BackupVerifier.verifyQuick(file, v151Counts)

        helper.runMigrationsAndValidate(
            name, DatabaseSchemaPolicy.CURRENT_VERSION, true, *DatabaseSchemaPolicy.ALL_MIGRATIONS
        ).close()
        val migratedCounts = BackupVerifier.expectedCountsAfterMigration(v151Counts, 151)
        BackupVerifier.verifyQuick(file, migratedCounts)
        val result = BackupVerifier.verify(file, migratedCounts)

        assertTrue(result.errors.toString(), result.passed)
        assertEquals(0, result.tableResults.single { it.tableName == "installment_plans" }.actualCount)
        assertEquals(0, result.tableResults.single { it.tableName == "installment_entries" }.actualCount)
        // Without the post-migration normalization the exact tier must refuse the migrated file.
        assertFalse(BackupVerifier.verify(file, v151Counts).passed)
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun databaseFile(name: String): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)

    private fun manifestCounts(file: File, version: Int): Map<String, Int> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            BackupVerifier.requiredManifestTables(databaseVersion = version).associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }
        }

    private fun seedPlanWithEntries(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO installment_plans " +
                "(id, planUuid, kind, purchaseExpenseId, displayName, currency, splitMethod, installmentCount, " +
                "frequency, firstDueDate, anchorDayOfMonth, residualPlacement, paymentMethod, fulfilmentMode, " +
                "declaredOnDate, status, createdAt, updatedAt) " +
                "VALUES (1, 'plan-uuid-1', 'STANDALONE', NULL, 'Synthetic plan', 'EUR', 'EQUAL_SPLIT', 2, " +
                "'MONTHLY', 20000, 15, 'LAST', 'UNKNOWN', 'CONFIRM', 19999, 'ACTIVE', 1700000000000, 1700000000000)"
        )
        for (seq in 1..2) {
            db.execSQL(
                "INSERT INTO installment_entries " +
                    "(planId, seq, kind, dueDate, scheduledAmount, status, autoRecordConsumed, recordGeneration, " +
                    "statusChangedAt) " +
                    "VALUES (1, $seq, 'REGULAR', ${20000 + seq * 30}, 50.0, 'SCHEDULED', 0, 0, 1700000000000)"
            )
        }
    }
}
