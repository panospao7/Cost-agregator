package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.migrationTestInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * INV-30 / AE-12 (loan installments, schema 152): the five semantic orphan checks of domain-spec
 * 11.3 report fixed, count-only messages (INV-31: no names, amounts or dates).
 *
 * Fixture (AE-12): P1 is purchase-attached (12 entries, 3 PAID) and P2 is standalone (240 entries,
 * 10 PAID). Plan display names are distinctive so a leak into an error message is detectable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierInstallmentOrphanTest {

    @get:Rule
    val helper = MigrationTestHelper(
        migrationTestInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val p1Name = "SECRET_PLAN_P1_NAME"
    private val p2Name = "SECRET_PLAN_P2_NAME"

    private val orphanEntriesToPlans =
        "Semantic integrity: Orphan installment_entries referencing non-existent plans"
    private val orphanEntriesToExpenses =
        "Semantic integrity: Orphan installment_entries linked to non-existent expenses"
    private val orphanPlansToPurchases =
        "Semantic integrity: Orphan installment_plans referencing non-existent purchase expenses"
    private val unreferencedPurchaseFlag =
        "Semantic integrity: Expenses flagged as installment purchase that no plan references"
    private val unreferencedReversedFlag =
        "Semantic integrity: Expenses flagged as installment reversed that no reversed entry references"

    private val semanticPrefixes = listOf(
        orphanEntriesToPlans,
        orphanEntriesToExpenses,
        orphanPlansToPurchases,
        unreferencedPurchaseFlag,
        unreferencedReversedFlag
    )

    @Test
    fun AE12_consistent_backup_with_two_plans_passes_and_all_five_checks_report_zero() {
        val file = createAe12Database("orphan-clean")
        val counts = manifestCounts(file)
        assertEquals(2, counts["installment_plans"])
        assertEquals(252, counts["installment_entries"])

        val summary = BackupVerifier.verify(file, counts)

        assertTrue(summary.errors.toString(), summary.passed)
        assertEquals(0, installmentSemanticErrors(summary).size)
    }

    @Test
    fun AE12_p2_plan_row_removed_fails_with_fixed_message_and_count_240_without_names_amounts_or_dates() {
        val file = createAe12Database("orphan-p2-removed")
        mutate(file) { it.execSQL("DELETE FROM installment_plans WHERE id = 2") }
        val counts = manifestCounts(file)
        assertEquals(1, counts["installment_plans"])
        assertEquals(252, counts["installment_entries"])

        val summary = BackupVerifier.verify(file, counts)

        assertFalse(summary.passed)
        val expected = "$orphanEntriesToPlans (240 orphan rows)"
        assertTrue(summary.errors.toString(), expected in summary.errors)
        val installmentErrors = installmentSemanticErrors(summary)
        assertEquals(listOf(expected), installmentErrors)
        // Fixed message: letters, spaces, underscores, hyphens and exactly one number (the count).
        assertTrue(Regex("^Semantic integrity: [A-Za-z_ -]+ \\(240 orphan rows\\)$").matches(expected))
        val everything = summary.errors.joinToString("\n")
        assertFalse(everything.contains(p1Name))
        assertFalse(everything.contains(p2Name))
        assertFalse(everything.contains("50.0"))
        assertFalse(everything.contains("20000"))
        assertFalse(everything.contains("1700000000000"))
    }

    @Test
    fun entry_linked_to_a_missing_expense_is_reported_with_its_count_only() {
        val file = createAe12Database("orphan-entry-expense")
        mutate(file) { it.execSQL("DELETE FROM expenses WHERE id = 2") }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$orphanEntriesToExpenses (1 orphan rows)"), installmentSemanticErrors(summary))
    }

    @Test
    fun plan_whose_purchase_expense_is_missing_is_reported_with_its_count_only() {
        val file = createAe12Database("orphan-plan-purchase")
        mutate(file) { it.execSQL("DELETE FROM expenses WHERE id = 1") }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$orphanPlansToPurchases (1 orphan rows)"), installmentSemanticErrors(summary))
    }

    @Test
    fun expense_flagged_as_purchase_that_no_plan_references_is_reported() {
        val file = createAe12Database("orphan-purchase-flag")
        mutate(file) {
            it.execSQL("UPDATE expenses SET spendingExclusionReason = 'INSTALLMENT_PURCHASE' WHERE id = 20")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$unreferencedPurchaseFlag (1 orphan rows)"), installmentSemanticErrors(summary))
    }

    @Test
    fun expense_flagged_as_reversed_without_a_reversed_entry_is_reported() {
        val file = createAe12Database("orphan-reversed-flag")
        // Expense 2 is the PAID entry's expense: flagging it while the entry is PAID is an orphan flag.
        mutate(file) {
            it.execSQL("UPDATE expenses SET spendingExclusionReason = 'INSTALLMENT_REVERSED' WHERE id = 2")
        }
        val flagged = BackupVerifier.verify(file, manifestCounts(file))
        assertFalse(flagged.passed)
        assertEquals(listOf("$unreferencedReversedFlag (1 orphan rows)"), installmentSemanticErrors(flagged))
    }

    @Test
    fun a_reversed_entry_satisfies_the_reversed_expense_flag() {
        // A fresh database: both mutations are applied before one verify (the file is not reopened after verify).
        val file = createAe12Database("orphan-reversed-satisfied")
        mutate(file) {
            it.execSQL("UPDATE expenses SET spendingExclusionReason = 'INSTALLMENT_REVERSED' WHERE id = 2")
            // The entry is REVERSED: the flag is referenced and the check reports 0.
            it.execSQL("UPDATE installment_entries SET status = 'REVERSED' WHERE linkedExpenseId = 2")
        }
        val reversed = BackupVerifier.verify(file, manifestCounts(file))
        assertEquals(emptyList<String>(), installmentSemanticErrors(reversed))
        assertTrue(reversed.errors.toString(), reversed.passed)
    }

    @Test
    fun purchase_flag_referenced_by_a_plan_is_not_reported() {
        val file = createAe12Database("orphan-purchase-referenced")
        // Expense 1 is flagged INSTALLMENT_PURCHASE and referenced by P1.purchaseExpenseId in the fixture.
        val flagCount = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM expenses WHERE spendingExclusionReason = 'INSTALLMENT_PURCHASE'",
                null
            ).use { c -> c.moveToFirst(); c.getInt(0) }
        }
        assertEquals(1, flagCount)
        assertEquals(emptyList<String>(), installmentSemanticErrors(BackupVerifier.verify(file, manifestCounts(file))))
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun installmentSemanticErrors(summary: BackupVerifier.VerificationSummary): List<String> =
        summary.errors.filter { error -> semanticPrefixes.any { error.startsWith(it) } }

    private fun databaseFile(name: String): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)

    private fun mutate(file: File, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Foreign keys are off by default on a raw handle: this models a restored copy whose
            // parent row is gone without a cascade having run.
            block(db)
        }
    }

    private fun manifestCounts(file: File): Map<String, Int> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            BackupVerifier.requiredManifestTables().associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }
        }

    private fun createAe12Database(name: String): File {
        val db = helper.createDatabase(name, 152)
        seedAe12(db)
        db.close()
        return databaseFile(name)
    }

    private fun seedAe12(db: SupportSQLiteDatabase) {
        // Expenses: 1 = P1 purchase (flagged), 2..4 = P1 paid, 5..14 = P2 paid, 20 = unrelated.
        for (id in listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 20L)) {
            val flag = if (id == 1L) "'INSTALLMENT_PURCHASE'" else "NULL"
            db.execSQL(
                "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                    "spendingExclusionReason) VALUES ($id, 50.0, 'EUR', 'M$id', 20000, 'PURCHASE', 0, $flag)"
            )
        }
        insertPlan(db, id = 1, uuid = "plan-uuid-1", kind = "PURCHASE_ATTACHED", purchaseExpenseId = "1", name = p1Name, count = 12)
        insertPlan(db, id = 2, uuid = "plan-uuid-2", kind = "STANDALONE", purchaseExpenseId = "NULL", name = p2Name, count = 240)
        // P1: 12 entries, entries 1..3 PAID and linked to expenses 2..4.
        for (seq in 1..12) {
            val paid = seq <= 3
            insertEntry(db, planId = 1, seq = seq, status = if (paid) "PAID" else "SCHEDULED", linked = if (paid) "${seq + 1}" else "NULL")
        }
        // P2: 240 entries, entries 1..10 PAID and linked to expenses 5..14.
        for (seq in 1..240) {
            val paid = seq <= 10
            insertEntry(db, planId = 2, seq = seq, status = if (paid) "PAID" else "SCHEDULED", linked = if (paid) "${seq + 4}" else "NULL")
        }
    }

    private fun insertPlan(
        db: SupportSQLiteDatabase,
        id: Long,
        uuid: String,
        kind: String,
        purchaseExpenseId: String,
        name: String,
        count: Int
    ) {
        db.execSQL(
            "INSERT INTO installment_plans " +
                "(id, planUuid, kind, purchaseExpenseId, displayName, currency, splitMethod, installmentCount, " +
                "frequency, firstDueDate, anchorDayOfMonth, residualPlacement, paymentMethod, fulfilmentMode, " +
                "declaredOnDate, status, createdAt, updatedAt) " +
                "VALUES ($id, '$uuid', '$kind', $purchaseExpenseId, '$name', 'EUR', 'EQUAL_SPLIT', $count, " +
                "'MONTHLY', 20000, 15, 'LAST', 'UNKNOWN', 'CONFIRM', 19999, 'ACTIVE', 1700000000000, 1700000000000)"
        )
    }

    private fun insertEntry(db: SupportSQLiteDatabase, planId: Long, seq: Int, status: String, linked: String) {
        db.execSQL(
            "INSERT INTO installment_entries " +
                "(planId, seq, kind, dueDate, scheduledAmount, status, linkedExpenseId, autoRecordConsumed, " +
                "recordGeneration, statusChangedAt) " +
                "VALUES ($planId, $seq, 'REGULAR', ${20000 + seq * 30}, 50.0, '$status', $linked, 0, 0, 1700000000000)"
        )
    }
}
