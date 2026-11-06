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
 * INV-20 / AE-24 (refund netting, schema 153): the two refund semantic checks of `BackupVerifier`
 * are fail-closed and report fixed, count-only messages (no ids, amounts, currencies or merchants).
 *
 * Fixture (synthetic): P1 (id 1) PURCHASE EUR 100.0, D1 (id 2) DEPOSIT EUR 40.0, one link of 40.0
 * between them, both rows carry `refundNettedAmount` 40.0. Expense 3 is unrelated and unlinked.
 * Mutations go through a raw handle (foreign keys off), which models a damaged restored copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierRefundLinkTest {

    @get:Rule
    val helper = MigrationTestHelper(
        migrationTestInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val orphanPrefix = "Semantic integrity: Orphan expense_refund_links referencing non-existent expenses"
    private val inconsistentPrefix =
        "Semantic integrity: Expenses whose refundNettedAmount differs from the sum of their refund links"

    @Test
    fun INV20_consistent_backup_passes_and_both_refund_checks_report_zero() {
        val file = createFixture("refund-verify-clean")
        val counts = manifestCounts(file)
        assertEquals(1, counts["expense_refund_links"])
        assertEquals(3, counts["expenses"])

        val summary = BackupVerifier.verify(file, counts)

        assertTrue(summary.errors.toString(), summary.passed)
        assertEquals(emptyList<String>(), refundErrors(summary))
    }

    @Test
    fun INV20_empty_link_table_with_unnetted_expenses_passes() {
        val file = createFixture("refund-verify-no-links") {
            it.execSQL("DELETE FROM expense_refund_links")
            it.execSQL("UPDATE expenses SET refundNettedAmount = 0.0")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertTrue(summary.errors.toString(), summary.passed)
        assertEquals(emptyList<String>(), refundErrors(summary))
    }

    @Test
    fun AE24_purchase_netted_amount_set_to_50_while_the_link_applies_40_fails_with_one_inconsistent_row() {
        val file = createFixture("refund-verify-ae24") {
            it.execSQL("UPDATE expenses SET refundNettedAmount = 50.0 WHERE id = 1")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$inconsistentPrefix (1 orphan rows)"), refundErrors(summary))
    }

    @Test
    fun INV20_refund_side_netted_amount_not_matching_its_link_fails() {
        val file = createFixture("refund-verify-refund-side") {
            it.execSQL("UPDATE expenses SET refundNettedAmount = 0.0 WHERE id = 2")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$inconsistentPrefix (1 orphan rows)"), refundErrors(summary))
    }

    @Test
    fun INV20_netted_amount_without_any_link_fails() {
        val file = createFixture("refund-verify-stray-netted") {
            it.execSQL("UPDATE expenses SET refundNettedAmount = 5.0 WHERE id = 3")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$inconsistentPrefix (1 orphan rows)"), refundErrors(summary))
    }

    @Test
    fun INV20_link_whose_purchase_side_is_missing_is_reported_with_its_count_only() {
        val file = createFixture("refund-verify-orphan-purchase") {
            it.execSQL("DELETE FROM expenses WHERE id = 1")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$orphanPrefix (1 orphan rows)"), refundErrors(summary))
    }

    @Test
    fun INV20_link_whose_refund_side_is_missing_is_reported_with_its_count_only() {
        val file = createFixture("refund-verify-orphan-refund") {
            it.execSQL("DELETE FROM expenses WHERE id = 2")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$orphanPrefix (1 orphan rows)"), refundErrors(summary))
    }

    @Test
    fun INV20_link_with_both_sides_missing_counts_once() {
        val file = createFixture("refund-verify-orphan-both") {
            it.execSQL("DELETE FROM expenses WHERE id IN (1, 2)")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        assertEquals(listOf("$orphanPrefix (1 orphan rows)"), refundErrors(summary))
    }

    @Test
    fun INV20_one_purchase_with_two_refunds_sums_both_links_and_passes() {
        // 30.1 + 20.2 is not exactly 50.3 in binary floating point; the 1e-6 tolerance absorbs it.
        val file = createFixture("refund-verify-two-refunds") {
            it.execSQL(
                "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                    "refundNettedAmount) VALUES (4, 30.1, 'EUR', 'SECRET_MERCHANT_D2', 20000, 'DEPOSIT', 0, 30.1)"
            )
            it.execSQL(
                "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                    "refundNettedAmount) VALUES (5, 20.2, 'EUR', 'SECRET_MERCHANT_D3', 20000, 'DEPOSIT', 0, 20.2)"
            )
            it.execSQL("DELETE FROM expense_refund_links")
            it.execSQL(linkInsert(id = 1, refund = 4, purchase = 1, applied = "30.1"))
            it.execSQL(linkInsert(id = 2, refund = 5, purchase = 1, applied = "20.2"))
            it.execSQL("UPDATE expenses SET refundNettedAmount = 50.3 WHERE id = 1")
            it.execSQL("UPDATE expenses SET refundNettedAmount = 0.0 WHERE id = 2")
        }
        val counts = manifestCounts(file)
        assertEquals(2, counts["expense_refund_links"])

        val control = BackupVerifier.verify(file, counts)
        assertTrue(control.errors.toString(), control.passed)
        assertEquals(emptyList<String>(), refundErrors(control))

        // Boundary: dropping one link's contribution from the purchase total is detected.
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE expenses SET refundNettedAmount = 30.1 WHERE id = 1")
        }
        val broken = BackupVerifier.verify(file, counts)
        assertFalse(broken.passed)
        assertEquals(listOf("$inconsistentPrefix (1 orphan rows)"), refundErrors(broken))
    }

    @Test
    fun INV20_tolerance_is_1e_minus_6_inside_passes_outside_fails() {
        val inside = createFixture("refund-verify-tol-inside") {
            // Difference 5e-7 is within the 1e-6 tolerance for both sides of the link.
            it.execSQL("UPDATE expenses SET refundNettedAmount = 40.0000005 WHERE id IN (1, 2)")
        }
        val insideSummary = BackupVerifier.verify(inside, manifestCounts(inside))
        assertTrue(insideSummary.errors.toString(), insideSummary.passed)
        assertEquals(emptyList<String>(), refundErrors(insideSummary))

        val outside = createFixture("refund-verify-tol-outside") {
            // Difference 1e-5 is beyond the tolerance.
            it.execSQL("UPDATE expenses SET refundNettedAmount = 40.00001 WHERE id = 1")
        }
        val outsideSummary = BackupVerifier.verify(outside, manifestCounts(outside))
        assertFalse(outsideSummary.passed)
        assertEquals(listOf("$inconsistentPrefix (1 orphan rows)"), refundErrors(outsideSummary))
    }

    @Test
    fun INV20_both_failures_are_reported_together_with_fixed_count_only_messages() {
        val file = createFixture("refund-verify-both") {
            it.execSQL("DELETE FROM expenses WHERE id = 1")
            it.execSQL("UPDATE expenses SET refundNettedAmount = 0.0 WHERE id = 2")
        }

        val summary = BackupVerifier.verify(file, manifestCounts(file))

        assertFalse(summary.passed)
        val errors = refundErrors(summary)
        assertEquals(
            listOf("$orphanPrefix (1 orphan rows)", "$inconsistentPrefix (1 orphan rows)"),
            errors
        )
        errors.forEach { error ->
            assertTrue(error, Regex("^Semantic integrity: [A-Za-z_ -]+ \\(\\d+ orphan rows\\)$").matches(error))
        }
        val everything = summary.errors.joinToString("\n")
        listOf("SECRET_MERCHANT", "40.0", "100.0", "EUR", "USER_CONFIRMED", "1700000000000").forEach {
            assertFalse("error text must not contain $it", everything.contains(it))
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun refundErrors(summary: BackupVerifier.VerificationSummary): List<String> =
        summary.errors.filter { it.startsWith(orphanPrefix) || it.startsWith(inconsistentPrefix) }

    private fun databaseFile(name: String): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)

    private fun manifestCounts(file: File): Map<String, Int> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            BackupVerifier.requiredManifestTables().associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }
        }

    /** Creates a real schema-153 database holding the consistent fixture, then applies [damage] on a raw handle. */
    private fun createFixture(name: String, damage: ((SQLiteDatabase) -> Unit)? = null): File {
        val db = helper.createDatabase(name, 153)
        seed(db)
        db.close()
        val file = databaseFile(name)
        if (damage != null) {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(damage)
        }
        return file
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                "refundNettedAmount) VALUES (1, 100.0, 'EUR', 'SECRET_MERCHANT_P1', 20000, 'PURCHASE', 0, 40.0)"
        )
        db.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                "refundNettedAmount) VALUES (2, 40.0, 'EUR', 'SECRET_MERCHANT_D1', 20000, 'DEPOSIT', 0, 40.0)"
        )
        db.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine) " +
                "VALUES (3, 10.0, 'EUR', 'SECRET_MERCHANT_X', 20000, 'PURCHASE', 0)"
        )
        db.execSQL(linkInsert(id = 1, refund = 2, purchase = 1, applied = "40.0"))
    }

    private fun linkInsert(id: Long, refund: Long, purchase: Long, applied: String): String =
        "INSERT INTO expense_refund_links (id, refundExpenseId, purchaseExpenseId, appliedAmount, currency, " +
            "createdAt, source) VALUES ($id, $refund, $purchase, $applied, 'EUR', 1700000000000, 'USER_CONFIRMED')"
}
