package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.diagnostics.DatabaseIntegrityScanner
import com.yourname.expensetracker.domain.diagnostics.Severity
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for database integrity scanning.
 *
 * These tests seed known invariant violations and verify that the
 * [DatabaseIntegrityScanner] detects them correctly. They also verify
 * basic DB integrity guarantees such as no duplicate dedupe keys and
 * referential integrity via foreign keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseIntegrityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scanner: DatabaseIntegrityScanner
    private val now = 1_714_514_400_000L // 2024-05-01T00:00:00Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        scanner = DatabaseIntegrityScanner(db)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Expenses with null dedupeKey are detected
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `expenses with null dedupeKey detected as INFO violation`() = runTest {
        // GIVEN: an expense with null dedupeKey (as happens with legacy data)
        db.expenseDao().insert(
            Expense(
                amount = 45.50,
                currency = "EUR",
                merchant = "SKLAVENITIS",
                transactionType = TransactionType.PURCHASE,
                date = now,
                dedupeKey = null,
                source = "legacy_import",
                createdAt = now
            )
        )

        // WHEN: running the full integrity scan
        val violations = scanner.runFullScan()

        // THEN: a NULL_DEDUPE_KEY violation is detected
        val nullDedupeViolations = violations.filter { it.invariantName == "NULL_DEDUPE_KEY" }
        assertEquals("Should have 1 NULL_DEDUPE_KEY violation", 1, nullDedupeViolations.size)
        val violation = nullDedupeViolations.single()
        assertEquals("Severity should be INFO", Severity.INFO, violation.severity)
        assertTrue("Count should be >= 1", violation.count >= 1)
        assertTrue("Detail should mention NULL dedupeKey",
            violation.detail.contains("NULL dedupeKey"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: No duplicate dedupe keys in expenses
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `no duplicate dedupe keys when inserting expenses`() = runTest {
        // GIVEN: two expenses with distinct dedupe keys
        val dedupeKey1 = Expense.generateDedupeKey(45.50, "SKLAVENITIS", now, "EUR")
        val dedupeKey2 = Expense.generateDedupeKey(15.00, "Metro", now, "EUR")

        db.expenseDao().insert(
            Expense(
                amount = 45.50,
                currency = "EUR",
                merchant = "SKLAVENITIS",
                transactionType = TransactionType.PURCHASE,
                date = now,
                dedupeKey = dedupeKey1,
                source = "manual",
                createdAt = now
            )
        )

        db.expenseDao().insert(
            Expense(
                amount = 15.00,
                currency = "EUR",
                merchant = "Metro",
                transactionType = TransactionType.PURCHASE,
                date = now,
                dedupeKey = dedupeKey2,
                source = "manual",
                createdAt = now
            )
        )

        // WHEN: querying all expenses
        val all = db.expenseDao().getAllUncapped()

        // THEN: both expenses exist with distinct dedupe keys
        assertEquals("Should have 2 expenses", 2, all.size)

        val dedupeKeys = all.mapNotNull { it.dedupeKey }.toSet()
        assertEquals("Should have 2 distinct dedupe keys", 2, dedupeKeys.size)
        assertTrue("dedupeKey1 should be present", dedupeKeys.contains(dedupeKey1))
        assertTrue("dedupeKey2 should be present", dedupeKeys.contains(dedupeKey2))

        // AND: inserting with the same dedupeKey should be IGNORED (unique constraint)
        val duplicateInsertId = db.expenseDao().insert(
            Expense(
                amount = 45.50,
                currency = "EUR",
                merchant = "SKLAVENITIS",
                transactionType = TransactionType.PURCHASE,
                date = now,
                dedupeKey = dedupeKey1,
                source = "duplicate",
                createdAt = now
            )
        )
        assertEquals("Duplicate dedupeKey insert should return -1 (IGNORE)", -1L, duplicateInsertId)

        // AND: still only 2 expenses after the ignored insert
        val afterDuplicate = db.expenseDao().getAllUncapped()
        assertEquals("Should still have 2 expenses", 2, afterDuplicate.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Full scan returns no violations on clean database
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `full scan returns no violations on clean database`() = runTest {
        // GIVEN: a clean database with a single expense with non-null dedupeKey
        val dedupeKey = Expense.generateDedupeKey(25.00, "TestMerchant", now, "EUR")
        db.expenseDao().insert(
            Expense(
                amount = 25.00,
                currency = "EUR",
                merchant = "TestMerchant",
                transactionType = TransactionType.PURCHASE,
                date = now,
                dedupeKey = dedupeKey,
                source = "clean_test",
                createdAt = now
            )
        )

        // WHEN: running critical scans (no duplicate budgets, group members, etc.)
        val critical = scanner.runCriticalScans()

        // THEN: no critical violations
        assertTrue("No critical violations expected on clean DB", critical.isEmpty())

        // WHEN: running full scan
        val full = scanner.runFullScan()

        // THEN: only informational violations (like NULL_DEDUPE_KEY if any)
        // Note: the expense above has a dedupeKey set, so no NULL_DEDUPE_KEY
        val criticalViolations = full.filter { it.severity == Severity.CRITICAL }
        val warningViolations = full.filter { it.severity == Severity.WARNING }
        assertTrue("No critical violations expected", criticalViolations.isEmpty())
        assertTrue("No warning violations expected on clean DB", warningViolations.isEmpty())
    }
}
