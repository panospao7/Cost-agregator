package com.yourname.expensetracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P7-CURRENT-014 / P7-CURRENT-015 — verifier hardening.
 *
 * - [BackupVerifier.requiredManifestTables] / [BackupVerifier.validateManifestCompleteness]:
 *   a backup manifest missing required Tier 1 counts must be rejected BEFORE the
 *   destructive live-DB swap (P7-CURRENT-014).
 * - [BackupVerifier.collectTableCountsStrict]: a count-query failure on a required
 *   table during backup creation must fail loudly, never silently record 0
 *   (P7-CURRENT-015).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierManifestTest {

    // ── P7-CURRENT-014: required manifest tables ──────────────────

    @Test
    fun `requiredManifestTables are the Tier 1 exact tables`() {
        val required = BackupVerifier.requiredManifestTables()
        assertTrue("expenses must be required", required.contains("expenses"))
        assertTrue("budgets must be required", required.contains("budgets"))
        // Tier 1 exact tables must be required; Tier 3 optional tables must NOT be required.
        assertTrue("privacy_audit_events is now Tier 1 required", required.contains("privacy_audit_events"))
        assertFalse("exchange_rates is optional", required.contains("exchange_rates"))
        assertFalse("background_job_runs is optional", required.contains("background_job_runs"))
        // Every required table is Tier 1 exact.
        required.forEach {
            assertEquals(
                "Required table $it must be TIER_1_EXACT",
                BackupVerifier.VerificationTier.TIER_1_EXACT,
                BackupVerifier.tableTier(it)
            )
        }
    }

    @Test
    fun `validateManifestCompleteness passes when all required counts present`() {
        val complete = BackupVerifier.requiredManifestTables().associateWith { 0 }
        // Should not throw.
        BackupVerifier.validateManifestCompleteness(complete)
    }

    @Test
    fun `validateManifestCompleteness throws when a required count is missing`() {
        val incomplete = BackupVerifier.requiredManifestTables()
            .associateWith { 1 }
            .toMutableMap()
            .apply { remove("expenses") }

        val ex = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(incomplete)
        }
        assertTrue("missing list must name the dropped table", ex.missingTables.contains("expenses"))
    }

    @Test
    fun `validateManifestCompleteness lists all missing required tables`() {
        // Empty manifest → every required table is missing.
        val ex = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(emptyMap())
        }
        assertEquals(
            BackupVerifier.requiredManifestTables().sorted(),
            ex.missingTables.sorted()
        )
    }

    // ── P7-CURRENT-015: strict count collection ───────────────────

    @Test
    fun `collectTableCountsStrict throws on a DB missing required tables`() {
        // An empty in-memory DB has none of the required tables.
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            assertThrows(BackupVerifier.RequiredTableCountException::class.java) {
                BackupVerifier.collectTableCountsStrict(db)
            }
        } finally {
            db.close()
        }
    }

    // ── P7-P1-05: semantic aggregate verification ──────────────────

    @Test
    fun `semantic_aggregates_match_passes_verification`() {
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            // Create required tables
            db.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY, effectiveAmount REAL, transactionType TEXT, isNotMine INTEGER)")
            db.execSQL("CREATE TABLE budgets (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE receipt_expense_links (id INTEGER PRIMARY KEY, receiptId INTEGER, expenseId INTEGER)")

            // Insert data
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (1, 100.0, 'EXPENSE', 0)")
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (2, 50.0, 'INCOME', 0)")
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (3, 200.0, 'EXPENSE', 1)") // isNotMine, excluded
            db.execSQL("INSERT INTO budgets (id, name) VALUES (1, 'Groceries')")
            db.execSQL("INSERT INTO receipt_expense_links (id, receiptId, expenseId) VALUES (1, 1, 1)")

            // Build expected aggregates matching what the DB produces
            val expected = mapOf(
                "SELECT CAST(SUM(effectiveAmount) AS TEXT) FROM expenses WHERE transactionType = 'EXPENSE' AND isNotMine = 0" to "100.0",
                "SELECT CAST(SUM(effectiveAmount) AS TEXT) FROM expenses WHERE transactionType = 'INCOME' AND isNotMine = 0" to "50.0",
                "SELECT CAST(COUNT(*) AS TEXT) FROM expenses" to "3",
                "SELECT CAST(COUNT(*) AS TEXT) FROM budgets" to "1",
                "SELECT CAST(COUNT(*) AS TEXT) FROM receipt_expense_links" to "1"
            )

            val issues = BackupVerifier.verifySemanticAggregates(db, expected)
            assertTrue("All aggregates should match: $issues", issues.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `semantic_aggregates_mismatch_fails_verification`() {
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            // Create tables and insert data
            db.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY, effectiveAmount REAL, transactionType TEXT, isNotMine INTEGER)")
            db.execSQL("CREATE TABLE budgets (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE receipt_expense_links (id INTEGER PRIMARY KEY, receiptId INTEGER, expenseId INTEGER)")
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (1, 100.0, 'EXPENSE', 0)")
            db.execSQL("INSERT INTO budgets (id, name) VALUES (1, 'Groceries')")
            db.execSQL("INSERT INTO receipt_expense_links (id, receiptId, expenseId) VALUES (1, 1, 1)")

            // Build expected aggregates where one value intentionally mismatches
            val expected = mapOf(
                "SELECT CAST(SUM(effectiveAmount) AS TEXT) FROM expenses WHERE transactionType = 'EXPENSE' AND isNotMine = 0" to "999.0", // wrong!
                "SELECT CAST(COUNT(*) AS TEXT) FROM expenses" to "42" // intentional mismatch
            )

            val issues = BackupVerifier.verifySemanticAggregates(db, expected)
            assertEquals("Should have 2 mismatches", 2, issues.size)
            assertTrue("First issue should be SEMANTIC_MISMATCH", issues[0].code == "SEMANTIC_MISMATCH")
            assertTrue("Second issue should be SEMANTIC_MISMATCH", issues[1].code == "SEMANTIC_MISMATCH")
            assertTrue("Message should mention expected=999.0", issues[0].message.contains("expected=999.0"))
        } finally {
            db.close()
        }
    }
}
