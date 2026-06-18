package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.dateMs
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.scenario.CategorySeed
import com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioAssertions
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for CSV export/import roundtrip behaviour.
 *
 * These tests verify that seeded expenses can be queried by date range
 * (as an export would do) and that expense fields survive a roundtrip
 * of insert → query with matching field values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CsvExportImportRoundtripTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Seed expenses and query by date range
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `seed expenses and query by date range`() = runTest {
        // GIVEN: categories and 3 expenses across two months
        val may1 = dateMs(2026, 5, 1)
        val may15 = dateMs(2026, 5, 15)
        val june1 = dateMs(2026, 6, 1)

        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Transportation"),
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(amount = 45.50, merchant = "SKLAVENITIS", categoryName = "Food & Dining", date = may1),
                ExpenseSeed(amount = 15.00, merchant = "Metro", categoryName = "Transportation", date = may15),
                ExpenseSeed(amount = 30.00, merchant = "Amazon", categoryName = "Shopping", date = june1)
            ),
            fixedNowMs = may1,
            description = "Three expenses across May and June 2026"
        )

        // WHEN: seeding all data
        val seeder = ScenarioSeeder(db)
        seeder.seedState(seed)

        // THEN: all 3 expenses exist
        with(ScenarioAssertions) {
            db.assertExpenseCount(3)
            db.assertExpenseExists("SKLAVENITIS", 45.50)
            db.assertExpenseExists("Metro", 15.00)
            db.assertExpenseExists("Amazon", 30.00)
        }

        // WHEN: querying expenses in May (2026-05-01 to 2026-06-01)
        val mayExpenses = db.expenseDao().getExpensesBetween(
            startDate = dateMs(2026, 5, 1),
            endDate = dateMs(2026, 6, 1)
        )

        // THEN: only the 2 May expenses are returned
        assertEquals("May should have 2 expenses", 2, mayExpenses.size)
        val mayMerchants = mayExpenses.map { it.merchant }.toSet()
        assertTrue("May expenses should include SKLAVENITIS", mayMerchants.contains("SKLAVENITIS"))
        assertTrue("May expenses should include Metro", mayMerchants.contains("Metro"))

        // WHEN: querying expenses in June (2026-06-01 to 2026-07-01)
        val juneExpenses = db.expenseDao().getExpensesBetween(
            startDate = dateMs(2026, 6, 1),
            endDate = dateMs(2026, 7, 1)
        )

        // THEN: only the 1 June expense is returned
        assertEquals("June should have 1 expense", 1, juneExpenses.size)
        assertEquals("June expense should be Amazon", "Amazon", juneExpenses[0].merchant)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Expense fields survive roundtrip (insert → query → fields match)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `expense fields survive insert query roundtrip`() = runTest {
        // GIVEN: a seed with a single expense with explicit fields
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 99.99,
                    currency = "USD",
                    merchant = "Amazon.com",
                    transactionType = "PURCHASE",
                    date = currentTime,
                    categoryName = "Shopping",
                    notes = "Roundtrip test expense"
                )
            ),
            fixedNowMs = currentTime,
            description = "Single expense with USD currency for roundtrip verification"
        )

        // WHEN: seeding the expense
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN: seed result has one expense ID
        assertEquals("Should have 1 expense ID", 1, result.expenseIds.size)

        // WHEN: querying the expense back
        val expenseId = result.expenseIds.single()
        val saved = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist in DB", saved)

        // THEN: all fields match the original seed values
        assertEquals("amount should match", 99.99, saved!!.amount, 0.001)
        assertEquals("currency should match", "USD", saved.currency)
        assertEquals("merchant should match", "Amazon.com", saved.merchant)
        assertEquals("date should match", currentTime, saved.date)
        assertEquals("source should be 'scenario_seed'", "scenario_seed", saved.source)
        assertEquals("notes should match", "Roundtrip test expense", saved.notes)
        assertEquals("transactionType should be PURCHASE", TransactionType.PURCHASE, saved.transactionType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Count expenses in date range and verify total
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `count expenses in date range matches expected totals`() = runTest {
        // GIVEN: 4 expenses seeded at different dates
        val day1 = dateMs(2026, 5, 1)
        val day2 = dateMs(2026, 5, 2)
        val day3 = dateMs(2026, 6, 1)
        val day4 = dateMs(2026, 7, 1)

        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining")
            ),
            expenses = listOf(
                ExpenseSeed(amount = 10.00, merchant = "M1", categoryName = "Food & Dining", date = day1),
                ExpenseSeed(amount = 20.00, merchant = "M2", categoryName = "Food & Dining", date = day2),
                ExpenseSeed(amount = 30.00, merchant = "M3", categoryName = "Food & Dining", date = day3),
                ExpenseSeed(amount = 40.00, merchant = "M4", categoryName = "Food & Dining", date = day4)
            ),
            fixedNowMs = day1,
            description = "Four expenses across May, June, July"
        )

        val seeder = ScenarioSeeder(db)
        seeder.seedState(seed)

        // WHEN: counting expenses in May-only range
        val mayCount = db.expenseDao().countExpensesBetween(
            startDate = dateMs(2026, 5, 1),
            endDate = dateMs(2026, 6, 1)
        )
        // THEN: 2 expenses in May
        assertEquals("May should have 2 expenses", 2, mayCount)

        // WHEN: counting expenses in May-June range
        val mayJuneCount = db.expenseDao().countExpensesBetween(
            startDate = dateMs(2026, 5, 1),
            endDate = dateMs(2026, 7, 1)
        )
        // THEN: 3 expenses in May-June
        assertEquals("May-June should have 3 expenses", 3, mayJuneCount)

        // WHEN: counting all expenses
        val totalCount = db.expenseDao().getTotalCount()
        // THEN: all 4 exist
        assertEquals("Total should have 4 expenses", 4, totalCount)
    }
}
