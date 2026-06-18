package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.testfixtures.dateMs
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.eur
import com.yourname.expensetracker.testfixtures.gbp
import com.yourname.expensetracker.testfixtures.money
import com.yourname.expensetracker.testfixtures.scenario.CategorySeed
import com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioAssertions
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import com.yourname.expensetracker.testfixtures.usd
import com.yourname.expensetracker.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for multi-currency behaviour, focusing on:
 * 1. Domain-level cross-currency arithmetic safety
 * 2. Multi-currency expense seeding via [ScenarioSeeder]
 * 3. Raw dashboard totals (no conversion — seedState stores as-is)
 * 4. [MoneyAmount] helpers ([isZero], [isPositive], [money], extension properties)
 *
 * These tests verify that the domain model enforces currency isolation and that
 * the database layer stores multi-currency expenses without implicit conversion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MulticurrencyPartialRateScenarioTest {

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
    // Test 1: Cross-currency addition throws IllegalArgumentException
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cross currency addition throws exception`() {
        // GIVEN: MoneyAmount(45.50, EUR) and MoneyAmount(30.00, USD)
        val eurAmount = MoneyAmount(45.50, CurrencyCode.EUR)
        val usdAmount = MoneyAmount(30.00, CurrencyCode.USD)

        // WHEN: adding them directly, THEN: should throw IllegalArgumentException
        val exception = assertThrows(IllegalArgumentException::class.java) {
            eurAmount + usdAmount
        }
        assertTrue(
            "Exception message should mention different currencies",
            exception.message!!.contains("different currencies")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Same-currency addition works
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `same currency addition works`() {
        // GIVEN: MoneyAmount(45.50, EUR) and MoneyAmount(30.00, EUR)
        val a = MoneyAmount(45.50, CurrencyCode.EUR)
        val b = MoneyAmount(30.00, CurrencyCode.EUR)

        // WHEN: adding them
        val result = a + b

        // THEN: result = MoneyAmount(75.50, EUR)
        assertEquals("Sum amount should be 75.50", 75.50, result.amount, 0.001)
        assertEquals("Currency should remain EUR", CurrencyCode.EUR, result.currency)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Seed multi-currency expenses and verify source currencies
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `seed multi-currency expenses and verify source currencies`() = runTest {
        // GIVEN: categories "Food & Dining", "Shopping", "Transportation"
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Shopping"),
                CategorySeed("Transportation")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50,
                    currency = "EUR",
                    merchant = "SKLAVENITIS",
                    categoryName = "Food & Dining",
                    date = currentTime
                ),
                ExpenseSeed(
                    amount = 30.00,
                    currency = "USD",
                    merchant = "Amazon",
                    categoryName = "Shopping",
                    date = currentTime
                ),
                ExpenseSeed(
                    amount = 20.00,
                    currency = "GBP",
                    merchant = "Tesco",
                    categoryName = "Food & Dining",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Three expenses across three currencies (EUR, USD, GBP)"
        )

        // WHEN: seed all expenses
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // THEN:
        //   - expense count = 3
        //   - all 3 merchants exist
        //   - expenses have correct currencies stored
        with(ScenarioAssertions) {
            db.assertExpenseCount(3)
            db.assertExpenseExists("SKLAVENITIS", 45.50)
            db.assertExpenseExists("Amazon", 30.00)
            db.assertExpenseExists("Tesco", 20.00)
        }

        // Verify currencies stored in DB match expectations
        val allExpenses = db.expenseDao().getAll()
        assertEquals("Should have exactly 3 expenses", 3, allExpenses.size)

        val sklavenitis = allExpenses.single { it.merchant == "SKLAVENITIS" }
        assertEquals("SKLAVENITIS should be in EUR", "EUR", sklavenitis.currency)

        val amazon = allExpenses.single { it.merchant == "Amazon" }
        assertEquals("Amazon should be in USD", "USD", amazon.currency)

        val tesco = allExpenses.single { it.merchant == "Tesco" }
        assertEquals("Tesco should be in GBP", "GBP", tesco.currency)

        // Verify seed result metadata
        assertEquals("Expected exactly 3 expense IDs", 3, result.expenseIds.size)
        assertEquals("Expected exactly 3 category IDs", 3, result.categoryIds.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Dashboard total is sum of raw amounts (no conversion in seedState)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `dashboard total is sum of raw amounts (no conversion in seedState)`() = runTest {
        // GIVEN: same 3 expenses as above (€45.50 + $30.00 + £20.00)
        val currentTime = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Shopping"),
                CategorySeed("Transportation")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50,
                    currency = "EUR",
                    merchant = "SKLAVENITIS",
                    categoryName = "Food & Dining",
                    date = currentTime
                ),
                ExpenseSeed(
                    amount = 30.00,
                    currency = "USD",
                    merchant = "Amazon",
                    categoryName = "Shopping",
                    date = currentTime
                ),
                ExpenseSeed(
                    amount = 20.00,
                    currency = "GBP",
                    merchant = "Tesco",
                    categoryName = "Food & Dining",
                    date = currentTime
                )
            ),
            fixedNowMs = currentTime,
            description = "Dashboard total should be raw sum of mixed-currency amounts"
        )

        // WHEN: seeding the expenses
        val seeder = ScenarioSeeder(db)
        seeder.seedState(seed)

        // THEN: total should be 95.50 (raw sum, no conversion — seedState doesn't convert)
        with(ScenarioAssertions) {
            db.assertDashboardTotal(95.50)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: Money helper creates correct MoneyAmount instances
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `money helper creates correct MoneyAmount instances`() {
        // WHEN: creating money(45.50, "EUR"), 30.0.eur, 25.0.usd, 15.0.gbp
        val fromHelper = money(45.50, "EUR")
        val fromEurExtension = 30.0.eur
        val fromUsdExtension = 25.0.usd
        val fromGbpExtension = 15.0.gbp

        // THEN:
        //   - EUR amount.currency = CurrencyCode.EUR
        assertEquals("money() helper should create EUR amount",
            CurrencyCode.EUR, fromHelper.currency)
        assertEquals("money() helper should preserve value",
            45.50, fromHelper.amount, 0.001)

        //   - USD amount.currency = CurrencyCode("USD")
        assertEquals(".eur extension should create EUR amount",
            CurrencyCode.EUR, fromEurExtension.currency)
        assertEquals(".eur extension should preserve value",
            30.0, fromEurExtension.amount, 0.001)

        assertEquals(".usd extension should create USD amount",
            CurrencyCode("USD"), fromUsdExtension.currency)
        assertEquals(".usd extension should preserve value",
            25.0, fromUsdExtension.amount, 0.001)

        assertEquals(".gbp extension should create GBP amount",
            CurrencyCode("GBP"), fromGbpExtension.currency)
        assertEquals(".gbp extension should preserve value",
            15.0, fromGbpExtension.amount, 0.001)

        //   - 45.50.eur + 30.00.eur = 75.50.eur (same currency)
        val sum = 45.50.eur + 30.00.eur
        assertEquals("EUR + EUR should sum correctly", 75.50, sum.amount, 0.001)
        assertEquals("EUR + EUR should keep EUR", CurrencyCode.EUR, sum.currency)

        //   - 45.50.eur + 30.00.usd throws (different currency)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            45.50.eur + 30.00.usd
        }
        assertTrue(
            "EUR + USD should throw IllegalArgumentException",
            exception.message!!.contains("different currencies")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: isZero and isPositive work correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isZero and isPositive work correctly`() {
        // WHEN: creating MoneyAmount(0.0, EUR), MoneyAmount(45.50, EUR), MoneyAmount(-10.0, EUR)
        val zero = MoneyAmount(0.0, CurrencyCode.EUR)
        val positive = MoneyAmount(45.50, CurrencyCode.EUR)
        val negative = MoneyAmount(-10.0, CurrencyCode.EUR)

        // THEN:
        //   - 0.0.eur.isZero() = true
        assertTrue("Zero amount should be isZero", 0.0.eur.isZero())

        //   - 45.50.eur.isZero() = false
        assertFalse("Positive amount should not be isZero", positive.isZero())

        //   - 45.50.eur.isPositive() = true
        assertTrue("Positive amount should be isPositive", positive.isPositive())

        //   - (-10.0).eur.isPositive() = false
        assertFalse("Negative amount should not be isPositive", negative.isPositive())

        //   - zero.isPositive() = false
        assertFalse("Zero amount should not be isPositive", zero.isPositive())
    }
}
