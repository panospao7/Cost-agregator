package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import com.yourname.expensetracker.testfixtures.scenario.CategorySeed
import com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for core financial calculations with mixed currencies.
 *
 * Validates that [MoneyAggregate] correctly reflects partial conversion state,
 * that [ConversionFailure] with [FailureReason.RATE_STALE] produces proper
 * descriptions, and that single-currency aggregates are clean.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MixedCurrencyCoreFinancialScenarioTest {

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
    // Test 1: Multi-currency expenses produce correct MoneyAggregate with
    //         partial state when some rates are missing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multi-currency expenses produce correct MoneyAggregate with partial state`() = runTest {
        // GIVEN: expenses in EUR (100), USD (50), GBP (30) — no exchange rates seeded
        val now = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Shopping"),
                CategorySeed("Transportation")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 100.0, currency = "EUR",
                    merchant = "SKLAVENITIS", categoryName = "Food & Dining",
                    date = now
                ),
                ExpenseSeed(
                    amount = 50.0, currency = "USD",
                    merchant = "Amazon", categoryName = "Shopping",
                    date = now
                ),
                ExpenseSeed(
                    amount = 30.0, currency = "GBP",
                    merchant = "Tesco", categoryName = "Food & Dining",
                    date = now
                )
            ),
            fixedNowMs = now,
            description = "Three expenses across EUR, USD, GBP with no exchange rates"
        )
        val seeder = ScenarioSeeder(db)
        seeder.seedState(seed)

        // WHEN: building a MoneyAggregate from buckets (simulating missing rates)
        val aggregate = MoneyAggregate.partial(
            displayAmount = 100.0, // Only EUR converted; USD and GBP failed
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.EUR, 100.0, 1),
                MoneyBucket(CurrencyCode.USD, 50.0, 1),
                MoneyBucket(CurrencyCode.GBP, 30.0, 1)
            ),
            failures = listOf(
                ConversionFailure(
                    originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                ),
                ConversionFailure(
                    originalAmount = MoneyAmount(30.0, CurrencyCode.GBP),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                )
            )
        )

        // THEN: isPartial = true (some currencies could not be converted)
        assertTrue("Aggregate should be partial when some rates are missing", aggregate.isPartial)

        // AND: 3 source buckets (EUR, USD, GBP)
        assertEquals("Should have 3 source buckets", 3, aggregate.sourceBuckets.size)

        // AND: display amount is 100.0 (EUR only)
        assertEquals("Display amount should be 100.0", 100.0, aggregate.displayAmount, 0.001)

        // AND: display currency is EUR
        assertEquals("Display currency should be EUR", CurrencyCode.EUR, aggregate.displayCurrency)

        // AND: EUR bucket is present with correct values
        val eurBucket = aggregate.sourceBuckets.single { it.currency.code == "EUR" }
        assertEquals("EUR bucket amount should be 100.0", 100.0, eurBucket.amount, 0.001)
        assertEquals("EUR bucket transaction count should be 1", 1, eurBucket.transactionCount)

        // AND: USD bucket is present with correct values
        val usdBucket = aggregate.sourceBuckets.single { it.currency.code == "USD" }
        assertEquals("USD bucket amount should be 50.0", 50.0, usdBucket.amount, 0.001)

        // AND: GBP bucket is present with correct values
        val gbpBucket = aggregate.sourceBuckets.single { it.currency.code == "GBP" }
        assertEquals("GBP bucket amount should be 30.0", 30.0, gbpBucket.amount, 0.001)

        // AND: failedTransactionCount = 2 (USD and GBP)
        assertEquals("Should have 2 failed transactions", 2, aggregate.failedTransactionCount)

        // AND: isSingleCurrency = false
        assertFalse("Multi-currency aggregate should not be single currency", aggregate.isSingleCurrency)

        // AND: warning message mentions the failures
        assertTrue(
            "Warning message should be present",
            aggregate.warningMessage != null && aggregate.warningMessage!!.isNotBlank()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Conversion with stale rate produces RATE_STALE failure reason
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `conversion with stale rate produces RATE_STALE failure reason`() {
        // GIVEN: a ConversionFailure with reason = RATE_STALE
        val failure = ConversionFailure(
            originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
            targetCurrency = CurrencyCode.EUR,
            reason = FailureReason.RATE_STALE
        )

        // WHEN: reading the description
        val description = failure.description

        // THEN: description contains "too old"
        assertTrue(
            "Stale rate description should contain 'too old'",
            description.contains("too old")
        )

        // AND: description mentions both currencies
        assertTrue("Description should mention source currency USD", description.contains("USD"))
        assertTrue("Description should mention target currency EUR", description.contains("EUR"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Single currency produces clean MoneyAggregate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `single currency produces clean MoneyAggregate`() = runTest {
        // GIVEN: all expenses in EUR only
        val now = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining"),
                CategorySeed("Shopping")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 45.50, currency = "EUR",
                    merchant = "SKLAVENITIS", categoryName = "Food & Dining",
                    date = now
                ),
                ExpenseSeed(
                    amount = 30.00, currency = "EUR",
                    merchant = "Amazon", categoryName = "Shopping",
                    date = now
                )
            ),
            fixedNowMs = now,
            description = "All expenses in EUR — no conversion needed"
        )
        val seeder = ScenarioSeeder(db)
        seeder.seedState(seed)

        // WHEN: building a single-currency MoneyAggregate
        val totalAmount = 45.50 + 30.00
        val aggregate = MoneyAggregate.singleCurrency(
            amount = totalAmount,
            currency = CurrencyCode.EUR,
            transactionCount = 2
        )

        // THEN: isPartial = false
        assertFalse("Single-currency aggregate should not be partial", aggregate.isPartial)

        // AND: isSingleCurrency = true
        assertTrue("Single-currency aggregate should be single currency", aggregate.isSingleCurrency)

        // AND: displayAmount is the sum
        assertEquals("Display amount should be total", totalAmount, aggregate.displayAmount, 0.001)

        // AND: displayCurrency is EUR
        assertEquals("Display currency should be EUR", CurrencyCode.EUR, aggregate.displayCurrency)

        // AND: exactly one source bucket
        assertEquals("Should have exactly 1 source bucket", 1, aggregate.sourceBuckets.size)

        val bucket = aggregate.sourceBuckets.first()
        assertEquals("Bucket currency should be EUR", CurrencyCode.EUR, bucket.currency)
        assertEquals("Bucket amount should be total", totalAmount, bucket.amount, 0.001)
        assertEquals("Bucket transaction count should be 2", 2, bucket.transactionCount)

        // AND: no conversion failures
        assertTrue("Should have no conversion failures", aggregate.conversionFailures.isEmpty())

        // AND: failedTransactionCount = 0
        assertEquals("Failed transaction count should be 0", 0, aggregate.failedTransactionCount)
    }
}
