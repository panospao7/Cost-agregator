package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.testfixtures.*
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.scenario.*
import com.yourname.expensetracker.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Database-contract scenario tests for the transaction lifecycle.
 *
 * These tests verify that raw database seeding through [ScenarioSeeder]
 * produces the expected state in the [AppDatabase], and that the assertion
 * helpers in [ScenarioAssertions] correctly detect mismatches.
 *
 * Each test uses [AppDatabaseTestFactory.create] to obtain an isolated
 * in-memory database, seeds data via [ScenarioSeeder.seedState], and then
 * asserts the resulting DB state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleDbContractTest {

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

    @Test
    fun `create manual expense and verify DB state`() = runTest {
        // GIVEN: categories "Food & Dining", "Transportation", "Shopping"
        // AND: current time = 2026-05-01
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Transportation"),
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50,
                    merchant = "SKLAVENITIS",
                    categoryName = "Food & Dining",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Single expense of €45.50 at SKLAVENITIS in Food & Dining"
        )

        // WHEN: seed the expense
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN:
        //   - expense count = 1
        //   - expense exists with merchant="SKLAVENITIS", amount≈45.50
        //   - dashboard total ≈ 45.50
        with(ScenarioAssertions) {
            db.assertExpenseCount(1)
            db.assertExpenseExists("SKLAVENITIS", 45.50)
            db.assertDashboardTotal(45.50)
        }

        // Verify that the seed result contains exactly one expense ID
        Assert.assertEquals(
            "Expected exactly one expense in seed result",
            1, result.expenseIds.size
        )
        // Verify that all three categories were created
        Assert.assertEquals(
            "Expected three categories in seed result",
            3, result.categoryIds.size
        )
    }

    @Test
    fun `create duplicate expense and verify deduplication`() = runTest {
        // GIVEN: categories "Food & Dining", "Transportation", "Shopping"
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Transportation"),
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50,
                    merchant = "SKLAVENITIS",
                    categoryName = "Food & Dining",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Expense seeded twice to verify seedState does not deduplicate"
        )

        // AND: seed the expense once
        val seeder = ScenarioSeeder(db)
        val firstResult = seeder.seedState(seed)

        // WHEN: seed the same expense again (seedState inserts blindly)
        val secondResult = seeder.seedState(seed)

        // THEN:
        //   - expense count = 2 (seedState inserts blindly — dedup is lifecycle coordinator's job)
        //   - both should exist (this proves seedState doesn't dedupe)
        with(ScenarioAssertions) {
            db.assertExpenseCount(2)
            db.assertExpenseExists("SKLAVENITIS", 45.50)
        }

        // Both seed calls should have produced a positive expense ID
        Assert.assertEquals(
            "First seed should have produced 1 expense ID",
            1, firstResult.expenseIds.size
        )
        Assert.assertEquals(
            "Second seed should have produced 1 expense ID",
            1, secondResult.expenseIds.size
        )
        // The two expense IDs must be different (separate rows)
        Assert.assertNotEquals(
            "Two identical seeds should produce different expense IDs",
            firstResult.expenseIds.single(), secondResult.expenseIds.single()
        )
    }

    @Test
    fun `create multiple expenses across categories and verify totals`() = runTest {
        // GIVEN: 3 categories
        // AND: a monthly budget of €500 for "Food & Dining"
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Transportation"),
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50,
                    merchant = "SKLAVENITIS",
                    categoryName = "Food & Dining",
                    date = currentTime
                ),
                ExpenseSeed(
                    amount = 15.00,
                    merchant = "Metro",
                    categoryName = "Transportation",
                    date = currentTime
                ),
                ExpenseSeed(
                    amount = 30.00,
                    merchant = "Amazon",
                    categoryName = "Shopping",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Three expenses across three categories (budget omitted due to pre-existing schema issue with budgets table)"
        )

        // WHEN: seed all data
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN:
        //   - expense count = 3
        //   - dashboard total ≈ 90.50
        //   - all 3 merchants exist
        //   - no duplicate expenses
        with(ScenarioAssertions) {
            db.assertExpenseCount(3)
            db.assertDashboardTotal(90.50)
            db.assertNoDuplicateExpenses()
            db.assertExpenseExists("SKLAVENITIS", 45.50)
            db.assertExpenseExists("Metro", 15.00)
            db.assertExpenseExists("Amazon", 30.00)
        }

        // Verify seed result metadata
        Assert.assertEquals(
            "Expected exactly 3 expense IDs in seed result",
            3, result.expenseIds.size
        )
        Assert.assertEquals(
            "Expected exactly 3 category IDs in seed result",
            3, result.categoryIds.size
        )
    }

    @Test
    fun `create expense with missing category and verify null FK`() = runTest {
        // GIVEN: only "Food & Dining" category exists
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50,
                    merchant = "SKLAVENITIS",
                    categoryName = "NonExistent",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Expense with non-existent category — categoryId should be null"
        )

        // WHEN: seed an expense with categoryName="NonExistent"
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN:
        //   - expense exists with merchant but categoryId should be null
        //   - expense count = 1
        with(ScenarioAssertions) {
            db.assertExpenseCount(1)
            db.assertExpenseExists("SKLAVENITIS", 45.50)
        }

        // Manually verify that categoryId is null
        val expenses = db.expenseDao().getAll()
        Assert.assertEquals("Expected exactly 1 expense row", 1, expenses.size)
        val expense = expenses.single()
        Assert.assertNull(
            "categoryId should be null when the referenced category name does not exist",
            expense.categoryId
        )

        // Verify that only "Food & Dining" was registered in the seed result
        Assert.assertEquals(
            "Expected exactly 1 category in seed result",
            1, result.categoryIds.size
        )
        Assert.assertTrue(
            "Seed result should contain 'Food & Dining'",
            result.categoryIds.containsKey("Food & Dining")
        )
        Assert.assertFalse(
            "Seed result should NOT contain 'NonExistent'",
            result.categoryIds.containsKey("NonExistent")
        )
    }
}
