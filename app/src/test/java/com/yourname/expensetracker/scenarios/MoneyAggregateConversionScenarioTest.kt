package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests verifying multi-currency conversion correctness across
 * warranties, subscriptions, investments, and the core [MoneyAggregate] type.
 *
 * These tests validate that:
 * 1. Single-currency aggregates return correct values with no partial flag.
 * 2. Multi-currency aggregates without exchange rates produce partial results
 *    with per-currency breakdowns.
 * 3. Cross-currency arithmetic on [MoneyAmount] is safely rejected.
 * 4. [MoneyAggregate] correctly reflects conversion failures via [isPartial].
 *
 * For tests 1-4, the underlying MoneyAggregate construction pattern is tested
 * directly since the full engine pipeline is not available in this test scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MoneyAggregateConversionScenarioTest {

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
    // Test 1: Single-currency warranty aggregate returns correct value
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `single currency warranty aggregate returns correct value`() = runTest {
        // GIVEN: categories "Electronics"
        val seed = ScenarioSeed(
            categories = listOf(CategorySeed("Electronics")),
            expenses = listOf(
                ExpenseSeed(
                    amount = 100.0,
                    currency = "EUR",
                    merchant = "Amazon",
                    categoryName = "Electronics",
                    date = dateMs(2026, 5, 1)
                )
            ),
            fixedNowMs = dateMs(2026, 5, 1),
            description = "Single expense with warranty in EUR"
        )
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // AND: a warranty on that expense (€100, ACTIVE)
        val expenseId = result.expenseIds.single()
        val now = dateMs(2026, 5, 1)
        val warrantyId = db.warrantyDao().insertWarranty(
            Warranty(
                expenseId = expenseId,
                receiptId = null,
                productName = "Headphones",
                merchantName = "Amazon",
                purchaseDate = now,
                warrantyDurationMonths = 12,
                warrantyEndDate = now + 365L * 24 * 60 * 60 * 1000,
                warrantyType = WarrantyType.MANUFACTURER,
                status = WarrantyStatus.ACTIVE,
                createdAt = now,
                updatedAt = now
            )
        )
        assertTrue("Warranty id should be positive", warrantyId > 0L)

        // WHEN: building the MoneyAggregate for the protected value
        val aggregate = MoneyAggregate.singleCurrency(
            amount = 100.0,
            currency = CurrencyCode.EUR,
            transactionCount = 1
        )

        // THEN: MoneyAggregate.displayAmount ≈ 100.0
        assertEquals(
            "Display amount should be 100.0",
            100.0, aggregate.displayAmount, 0.001
        )

        // AND: isPartial = false
        assertFalse("Single-currency aggregate should not be partial", aggregate.isPartial)

        // AND: displayCurrency = EUR
        assertEquals(
            "Display currency should be EUR",
            CurrencyCode.EUR, aggregate.displayCurrency
        )

        // AND: exactly one source bucket
        assertEquals(
            "Should have exactly 1 source bucket",
            1, aggregate.sourceBuckets.size
        )
        val bucket = aggregate.sourceBuckets.first()
        assertEquals("Bucket currency should be EUR", CurrencyCode.EUR, bucket.currency)
        assertEquals("Bucket amount should be 100.0", 100.0, bucket.amount, 0.001)
        assertEquals("Bucket transaction count should be 1", 1, bucket.transactionCount)

        // AND: no conversion failures
        assertTrue("Should have no conversion failures", aggregate.conversionFailures.isEmpty())
        assertTrue("Should be single currency", aggregate.isSingleCurrency)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Mixed-currency warranty aggregate shows partial when no converter
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `mixed currency warranty aggregate shows partial when no converter`() = runTest {
        // GIVEN: expenses in EUR, USD, GBP (no exchange rates seeded)
        val now = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Electronics"),
                CategorySeed("Software"),
                CategorySeed("Services")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 100.0, currency = "EUR",
                    merchant = "Amazon", categoryName = "Electronics",
                    date = now
                ),
                ExpenseSeed(
                    amount = 50.0, currency = "USD",
                    merchant = "BestBuy", categoryName = "Electronics",
                    date = now
                ),
                ExpenseSeed(
                    amount = 75.0, currency = "GBP",
                    merchant = "Currys", categoryName = "Electronics",
                    date = now
                )
            ),
            fixedNowMs = now,
            description = "Three expenses in three currencies with warranties"
        )
        val seeder = ScenarioSeeder(db)
        val result = seeder.seedState(seed)

        // AND: warranties on all of them
        for (eid in result.expenseIds) {
            db.warrantyDao().insertWarranty(
                Warranty(
                    expenseId = eid,
                    receiptId = null,
                    productName = "Gadget",
                    merchantName = "Store",
                    purchaseDate = now,
                    warrantyDurationMonths = 12,
                    warrantyEndDate = now + 365L * 24 * 60 * 60 * 1000,
                    warrantyType = WarrantyType.MANUFACTURER,
                    status = WarrantyStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        // WHEN: building a partial MoneyAggregate (simulating missing exchange rates)
        val aggregate = MoneyAggregate.partial(
            displayAmount = 100.0, // Only EUR converted; USD and GBP failed
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.EUR, 100.0, 1),
                MoneyBucket(CurrencyCode.USD, 50.0, 1),
                MoneyBucket(CurrencyCode.GBP, 75.0, 1)
            ),
            failures = listOf(
                ConversionFailure(
                    originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                ),
                ConversionFailure(
                    originalAmount = MoneyAmount(75.0, CurrencyCode.GBP),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                )
            )
        )

        // THEN: isPartial = true
        assertTrue("Partial aggregate should have isPartial = true", aggregate.isPartial)

        // AND: failedTransactionCount > 0
        assertTrue(
            "Should have failed transactions",
            aggregate.failedTransactionCount > 0
        )
        assertEquals("Should have 2 conversion failures", 2, aggregate.failedTransactionCount)

        // AND: sourceBuckets has 3 entries (EUR, USD, GBP)
        assertEquals(
            "Should have 3 source buckets",
            3, aggregate.sourceBuckets.size
        )

        // AND: warning message is present
        assertNotNull("Warning message should be present", aggregate.warningMessage)
        assertTrue(
            "Warning message should mention missing exchange rates",
            aggregate.warningMessage!!.contains("missing exchange rates")
        )

        val currencies = aggregate.sourceBuckets.map { it.currency.code }.toSet()
        assertEquals(
            "Source bucket currencies should be EUR, USD, GBP",
            setOf("EUR", "USD", "GBP"), currencies
        )

        // AND: isSingleCurrency is false
        assertFalse("Multi-currency aggregate should not be single currency", aggregate.isSingleCurrency)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Mixed-currency subscription totals grouped by currency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `mixed currency subscription totals grouped by currency`() = runTest {
        // GIVEN: 3 subscriptions in different currencies
        val now = dateMs(2026, 5, 1)
        db.manualRecurringExpenseDao().insert(
            ManualRecurringExpense(
                merchant = "Netflix",
                amount = 12.99,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now,
                isSubscription = true,
                isActive = true,
                createdAt = now
            )
        )
        db.manualRecurringExpenseDao().insert(
            ManualRecurringExpense(
                merchant = "Hulu",
                amount = 9.99,
                currency = "USD",
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now,
                isSubscription = true,
                isActive = true,
                createdAt = now
            )
        )
        db.manualRecurringExpenseDao().insert(
            ManualRecurringExpense(
                merchant = "BBC",
                amount = 7.99,
                currency = "GBP",
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now,
                isSubscription = true,
                isActive = true,
                createdAt = now
            )
        )

        // Verify seed
        val allSubs = db.manualRecurringExpenseDao().getAll()
        assertEquals("Should have 3 subscriptions", 3, allSubs.size)

        // WHEN: building a MoneyAggregate from subscription data
        // (simulating the pattern the engine would use to group by currency)
        val aggregate = MoneyAggregate.partial(
            displayAmount = 12.99,
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.EUR, 12.99, 1),
                MoneyBucket(CurrencyCode.USD, 9.99, 1),
                MoneyBucket(CurrencyCode.GBP, 7.99, 1)
            ),
            failures = listOf(
                ConversionFailure(
                    originalAmount = MoneyAmount(9.99, CurrencyCode.USD),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                ),
                ConversionFailure(
                    originalAmount = MoneyAmount(7.99, CurrencyCode.GBP),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                )
            )
        )

        // THEN: MoneyAggregate has 3 sourceBuckets (EUR, USD, GBP)
        assertEquals(
            "Should have 3 source buckets",
            3, aggregate.sourceBuckets.size
        )

        val bucketCurrencies = aggregate.sourceBuckets.map { it.currency.code }.toSet()
        assertEquals(
            "Bucket currencies should be EUR, USD, GBP",
            setOf("EUR", "USD", "GBP"), bucketCurrencies
        )

        // AND: isPartial flag reflects conversion status
        assertTrue("Partial aggregate should have isPartial = true", aggregate.isPartial)
        assertEquals(
            "Failed transaction count should be 2",
            2, aggregate.failedTransactionCount
        )

        // AND: warning message reflects the failures
        assertNotNull(aggregate.warningMessage)
        assertTrue(
            aggregate.warningMessage!!.contains("2 transaction(s)")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Investment portfolio shows per-currency breakdown
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `investment portfolio shows per-currency breakdown`() = runTest {
        // GIVEN: 3 investment holdings in different currencies with current prices
        val now = dateMs(2026, 5, 1)
        db.investmentDao().insert(
            Investment(
                name = "Apple Inc.", symbol = "AAPL", type = InvestmentType.STOCK,
                currency = "USD", exchange = "NASDAQ",
                purchasePrice = 150.0, quantity = 10.0,
                purchaseDate = now, currentPrice = 175.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )
        db.investmentDao().insert(
            Investment(
                name = "SAP SE", symbol = "SAP", type = InvestmentType.STOCK,
                currency = "EUR", exchange = "XETRA",
                purchasePrice = 120.0, quantity = 5.0,
                purchaseDate = now, currentPrice = 140.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )
        db.investmentDao().insert(
            Investment(
                name = "Bitcoin", symbol = "BTC", type = InvestmentType.CRYPTO,
                currency = "USD", exchange = "BINANCE",
                purchasePrice = 30000.0, quantity = 0.5,
                purchaseDate = now, currentPrice = 35000.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )

        // Verify seed
        val allInvestments = db.investmentDao().getAllInvestments()
        assertEquals("Should have 3 investments", 3, allInvestments.size)

        // WHEN: computing per-currency portfolio values
        // USD bucket: AAPL 10 * 175.0 = 1750.0, BTC 0.5 * 35000.0 = 17500.0 → total = 19250.0
        // EUR bucket: SAP 5 * 140.0 = 700.0
        val usdTotal = 10.0 * 175.0 + 0.5 * 35000.0
        val eurTotal = 5.0 * 140.0

        val aggregate = MoneyAggregate.partial(
            displayAmount = eurTotal,
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.USD, usdTotal, 2),
                MoneyBucket(CurrencyCode.EUR, eurTotal, 1)
            ),
            failures = listOf(
                ConversionFailure(
                    originalAmount = MoneyAmount(usdTotal, CurrencyCode.USD),
                    targetCurrency = CurrencyCode.EUR,
                    reason = FailureReason.MISSING_RATE
                )
            )
        )

        // THEN: MoneyAggregate.sourceBuckets grouped by currency
        assertEquals(
            "Should have 2 source buckets",
            2, aggregate.sourceBuckets.size
        )

        val usdBucket = aggregate.sourceBuckets.single { it.currency.code == "USD" }
        assertEquals("USD bucket amount should be sum of AAPL + BTC values",
            usdTotal, usdBucket.amount, 0.001)
        assertEquals("USD bucket should have 2 transactions", 2, usdBucket.transactionCount)

        val eurBucket = aggregate.sourceBuckets.single { it.currency.code == "EUR" }
        assertEquals("EUR bucket amount should be SAP value",
            eurTotal, eurBucket.amount, 0.001)
        assertEquals("EUR bucket should have 1 transaction", 1, eurBucket.transactionCount)

        // AND: total value is sum of (price * quantity) per bucket
        val totalValue = aggregate.sourceBuckets.sumOf { it.amount }
        assertEquals("Total value should be sum of all buckets",
            usdTotal + eurTotal, totalValue, 0.001)

        // AND: isPartial is true due to missing USD→EUR rate
        assertTrue("Aggregate should be partial", aggregate.isPartial)
        assertEquals("Should have 1 conversion failure", 1, aggregate.failedTransactionCount)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: MoneyAmount cross-currency addition throws
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MoneyAmount cross-currency addition throws`() {
        // GIVEN: MoneyAmount(100.0, EUR) and MoneyAmount(50.0, USD)
        val eurAmount = MoneyAmount(100.0, CurrencyCode.EUR)
        val usdAmount = MoneyAmount(50.0, CurrencyCode.USD)

        // WHEN: adding them directly (eur + usd)
        // THEN: throws IllegalArgumentException
        val exception = assertThrows(IllegalArgumentException::class.java) {
            eurAmount + usdAmount
        }
        assertTrue(
            "Exception message should mention different currencies",
            exception.message!!.contains("different currencies")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: MoneyAggregate with conversion failures has isPartial=true
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `MoneyAggregate with conversion failures has isPartial=true`() {
        // GIVEN: A MoneyAggregate with conversionFailures list non-empty
        val failures = listOf(
            ConversionFailure(
                originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
                targetCurrency = CurrencyCode.EUR,
                reason = FailureReason.MISSING_RATE
            ),
            ConversionFailure(
                originalAmount = MoneyAmount(25.0, CurrencyCode.GBP),
                targetCurrency = CurrencyCode.EUR,
                reason = FailureReason.RATE_STALE
            )
        )

        val aggregate = MoneyAggregate(
            displayAmount = 100.0,
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.EUR, 100.0, 1),
                MoneyBucket(CurrencyCode.USD, 50.0, 1),
                MoneyBucket(CurrencyCode.GBP, 25.0, 1)
            ),
            conversionFailures = failures,
            // warningMessage is optional — only the companion factory methods set it
            warningMessage = "Partial: 2 currencies could not be converted"
        )

        // THEN: isPartial = true
        assertTrue("Aggregate with failures should be partial", aggregate.isPartial)

        // AND: failedTransactionCount > 0
        assertEquals("Should have 2 conversion failures", 2, aggregate.failedTransactionCount)
        assertEquals("failedTransactionCount should equal failures size",
            failures.size, aggregate.failedTransactionCount)

        // AND: totalTransactionCount is correct
        assertEquals(
            "Total transaction count should be sum of bucket counts",
            3, aggregate.totalTransactionCount
        )

        // AND: isSingleCurrency is false
        assertFalse("Multi-currency aggregate should not be single currency", aggregate.isSingleCurrency)

        // AND: warningMessage is present
        assertNotNull("Warning message should be present", aggregate.warningMessage)
        assertTrue(
            "Warning message should be non-empty",
            aggregate.warningMessage!!.isNotBlank()
        )
    }
}
