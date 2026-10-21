package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Key behavioral regression tests for the currency normalization system.
 * These prove the invariants that the global currency normalization plan requires.
 */
class CurrencyNormalizationBehavioralTest {

    private lateinit var store: FakeExchangeRateStore
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var converter: CurrencyConverter

    private val NOW = 1716163200000L // 2024-05-20 00:00 UTC
    private val DAY_MS = 86400000L

    @Before
    fun setup() {
        store = FakeExchangeRateStore()
        timeProvider = FakeTimeProvider(NOW)
        converter = CurrencyConverter(store, timeProvider)
    }

    // --- Exchange-rate latest vs historical ---

    @Test
    fun `latest rate uses validDate not lastUpdated`() = runTest {
        // Historical rate backfilled today with old validDate
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.85, lastUpdated = NOW, source = "backfill", validDate = NOW - 30 * DAY_MS)
        // Fresh rate with today's validDate
        store.rates["USD_EUR_fresh"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW - 1000, source = "api", validDate = NOW)
        // getLatestRateForPair should prefer the one with highest validDate
        store.latestByValidDate = true

        val outcome = converter.convertOutcome(100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE)
        assertTrue(outcome is ConversionOutcome.Converted)
        val converted = outcome as ConversionOutcome.Converted
        assertEquals(0.92, converted.rateUsed, 0.0)
    }

    @Test
    fun `historical backfill does not poison latest rate`() = runTest {
        // Only rate: historical backfill with old validDate but fresh lastUpdated
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.80, lastUpdated = NOW, source = "backfill", validDate = NOW - 60 * DAY_MS)

        val outcome = converter.convertOutcome(100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE, stalePolicy = StaleRatePolicy.None)
        // Should still find it (it's the only rate) — staleness disabled for this test
        assertTrue(outcome is ConversionOutcome.Converted)
    }

    // --- convertOutcome historical basis requires date ---

    @Test
    fun `convertOutcome historical basis without atMillis fails and never falls back to latest`() = runTest {
        // A latest rate exists; a historical basis without a date must still fail.
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val bases = listOf(
            RateBasis.TRANSACTION_DATE,
            RateBasis.PERIOD_START,
            RateBasis.PERIOD_END,
            RateBasis.FORECAST_DATE
        )
        assertEquals("row count", 4, bases.size)

        bases.forEach { basis ->
            val outcome = converter.convertOutcome(100.0, "USD", "EUR", basis, atMillis = null)

            assertTrue("$basis: should fail without date but was $outcome", outcome is ConversionOutcome.Failed)
            assertEquals(
                "$basis: failure type",
                ConversionFailureType.MISSING_HISTORICAL_RATE,
                (outcome as ConversionOutcome.Failed).failureType
            )
        }
    }

    // --- Moved from domain.currency.CurrencyNormalizationBehavioralTest (B6-6) ---

    @Test
    fun `latest rate prefers fresh manual rate over historical backfill`() = runTest {
        // Historical backfill inserted today with yesterday's validDate.
        store.rates["USD_EUR_backfill"] = DomainExchangeRate(
            "USD", "EUR", 0.85, lastUpdated = NOW, source = "backfill", validDate = NOW - DAY_MS
        )

        // Fresh manual rate stored through a converter whose clock is 3h into the day, so the
        // stored validDate (start of today, UTC == NOW) is distinguishable from the clock time.
        val midDayConverter = CurrencyConverter(store, FakeTimeProvider(NOW + 3 * 3_600_000L))
        midDayConverter.storeRate("USD", "EUR", 0.92, "manual")

        val latest = store.getLatestRateForPair("USD", "EUR")
        assertNotNull(latest)
        assertEquals(0.92, latest!!.rate, 0.0)
        assertEquals("manual", latest.source)
        assertEquals(NOW, latest.validDate)
        assertEquals(NOW + 3 * 3_600_000L, latest.lastUpdated)
    }

    @Test
    fun `historical conversion uses as-of validDate not lastUpdated`() = runTest {
        // Rate valid two days ago (midnight), inserted today.
        val validTwoDaysAgo = NOW - 2 * DAY_MS
        store.rates["USD_EUR"] = DomainExchangeRate(
            "USD", "EUR", 0.88, lastUpdated = NOW, source = "api", validDate = validTwoDaysAgo
        )

        // As-of lookup three hours into that day finds it.
        val found = converter.convertAsOf(100.0, "USD", "EUR", validTwoDaysAgo + 3 * 3_600_000L)
        assertNotNull(found)
        assertEquals(0.88, found!!.rateUsed, 0.0)
        assertMoneyEquals(88.0, found.convertedAmount, "as-of converted")

        // As-of lookup before the rate's validDate must not find it, despite lastUpdated = today.
        assertNull(converter.convertAsOf(100.0, "USD", "EUR", validTwoDaysAgo - 1L))
    }

    @Test
    fun `empty aggregate preserves explicit rateBasis`() {
        val aggregate = MoneyAggregate.empty(CurrencyCode("EUR"), RateBasis.PERIOD_END)

        assertEquals(RateBasis.PERIOD_END, aggregate.rateBasis)
        assertEquals(RateBasis.PERIOD_END, aggregate.requestedRateBasis)
    }

    @Test
    fun `single currency identity does not default to latest`() {
        val aggregate = MoneyAggregate.singleCurrency(
            amount = 100.0,
            currency = CurrencyCode("EUR"),
            transactionCount = 1,
            rateBasis = RateBasis.TRANSACTION_DATE
        )

        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.rateBasis)
        assertEquals(RateBasis.IDENTITY, aggregate.actualRateBasis)
    }

    @Test
    fun `aggregate metadata counts missing and excluded rows`() = runTest {
        // Only a USD->EUR rate is available; GBP has none; EUR is identity.
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "USD", NOW),
            fakeExpense(2, 50.0, "GBP", NOW), // no rate
            fakeExpense(3, 75.0, "EUR", NOW)
        )

        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertTrue(aggregate.isPartial)
        assertEquals(2, aggregate.metadata.includedTransactionCount)
        assertEquals(1, aggregate.metadata.excludedTransactionCount)
        assertEquals(1, aggregate.metadata.missingRateCount)
        assertMoneyEquals(165.0, aggregate.displayAmount, "USD 100*0.90 + EUR 75")
    }

    @Test
    fun `convertOutcome identity returns rate 1 and IDENTITY path`() = runTest {
        val outcome = converter.convertOutcome(50.0, "EUR", "EUR", RateBasis.TRANSACTION_DATE, atMillis = NOW)
        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        assertEquals(1.0, c.rateUsed, 0.0)
        assertEquals(ConversionPath.IDENTITY, c.conversionPath)
        assertEquals(RateBasis.IDENTITY, c.rateBasis)
    }

    @Test
    fun `convertOutcome missing rate returns Failed not null`() = runTest {
        // No rates stored
        val outcome = converter.convertOutcome(100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE)
        assertTrue(outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.MISSING_RATE, (outcome as ConversionOutcome.Failed).failureType)
    }

    @Test
    fun `convertOutcome invalid currency returns Failed`() = runTest {
        val outcome = converter.convertOutcome(100.0, "XYZ", "EUR", RateBasis.LATEST_AVAILABLE)
        assertTrue(outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.INVALID_SOURCE_CURRENCY, (outcome as ConversionOutcome.Failed).failureType)
    }

    // --- MoneyNormalizationEngine ---

    @Test
    fun `aggregate expenses missing rate excludes and marks partial`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        // No GBP rate
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "USD", NOW),
            fakeExpense(2, 50.0, "GBP", NOW)
        )
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.conversionFailures.size)
        assertMoneyEquals(92.0, aggregate.displayAmount, "only USD converted")
        assertEquals(RateBasis.LATEST_AVAILABLE, aggregate.rateBasis)
        assertEquals(2, aggregate.totalTransactionCount)
        assertEquals(1, aggregate.failedTransactionCount)
        assertEquals(setOf(CurrencyCode("USD"), CurrencyCode("GBP")), aggregate.sourceBuckets.map { it.currency }.toSet())
        assertEquals(1, aggregate.metadata.includedTransactionCount)
        assertEquals(1, aggregate.metadata.excludedTransactionCount)
    }

    @Test
    fun `aggregate expenses transaction date sets rateBasis`() = runTest {
        store.ratesAsOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(fakeExpense(1, 100.0, "USD", NOW))
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.rateBasis)
        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.requestedRateBasis)
        assertMoneyEquals(90.0, aggregate.displayAmount, "USD 100 * 0.90")
    }

    @Test
    fun `aggregate expenses conserves two included and three failed source rows`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 10.0, "EUR", NOW),
            fakeExpense(2, 20.0, "EUR", NOW),
            fakeExpense(3, 30.0, "GBP", NOW),
            fakeExpense(4, 40.0, "GBP", NOW),
            fakeExpense(5, 50.0, "GBP", NOW)
        )

        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertMoneyExact(30.0, aggregate.displayAmount, "displayAmount")
        assertEquals(5, aggregate.totalTransactionCount)
        assertEquals(3, aggregate.failedTransactionCount)
        assertEquals(2, aggregate.metadata.includedTransactionCount)
        assertEquals(3, aggregate.metadata.excludedTransactionCount)
        assertMoneyExact(30.0, aggregate.sourceBuckets.single { it.currency == CurrencyCode.EUR }.amount, "EUR bucket")
        assertMoneyExact(120.0, aggregate.sourceBuckets.single { it.currency == CurrencyCode("GBP") }.amount, "GBP bucket")
    }

    @Test
    fun `aggregate expenses preserves effective amounts for included and failed sources`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(
                id = 1,
                amount = 100.0,
                currency = "EUR",
                date = NOW,
                isSharedExpense = true,
                myShareAmount = 40.0
            ),
            fakeExpense(
                id = 2,
                amount = 100.0,
                currency = "GBP",
                date = NOW,
                isSharedExpense = true,
                myShareAmount = 30.0
            )
        )

        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertMoneyExact(40.0, aggregate.displayAmount, "displayAmount")
        assertMoneyExact(40.0, aggregate.sourceBuckets.single { it.currency == CurrencyCode.EUR }.amount, "EUR bucket")
        assertMoneyExact(30.0, aggregate.sourceBuckets.single { it.currency == CurrencyCode("GBP") }.amount, "GBP bucket")
        assertMoneyExact(30.0, aggregate.conversionFailures.single().originalAmount.amount, "failed original amount")
    }

    @Test
    fun `aggregate expenses retains stale failed source`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate(
            "USD",
            "EUR",
            0.92,
            lastUpdated = NOW,
            source = "api",
            validDate = NOW - 8 * DAY_MS
        )
        val engine = MoneyNormalizationEngine(converter)

        val aggregate = engine.aggregateExpenses(
            listOf(fakeExpense(1, 100.0, "USD", NOW)),
            CurrencyCode.EUR,
            RateBasis.LATEST_AVAILABLE
        )

        assertMoneyExact(0.0, aggregate.displayAmount, "displayAmount")
        assertEquals(1, aggregate.totalTransactionCount)
        assertEquals(1, aggregate.sourceBuckets.single().transactionCount)
        assertEquals(FailureReason.RATE_STALE, aggregate.conversionFailures.single().reason)
    }

    @Test
    fun `filtered transaction types are excluded from source denominator`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 10.0, "EUR", NOW, transactionType = TransactionType.PURCHASE),
            fakeExpense(2, 20.0, "EUR", NOW, transactionType = TransactionType.DEPOSIT),
            fakeExpense(3, 30.0, "EUR", NOW, transactionType = TransactionType.TRANSFER)
        )

        val aggregate = engine.aggregateExpenses(
            expenses,
            CurrencyCode.EUR,
            RateBasis.LATEST_AVAILABLE,
            TransactionTypeFilter.PURCHASE_ONLY
        )

        assertMoneyExact(10.0, aggregate.displayAmount, "displayAmount")
        assertEquals(1, aggregate.totalTransactionCount)
        assertEquals(1, aggregate.sourceBuckets.single().transactionCount)
    }

    // --- Invalid currency handling (PR2) ---

    @Test
    fun `normalizeExpense with invalid currency returns excluded with INVALID_CURRENCY`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expense = fakeExpense(1, 100.0, "123", NOW) // numeric currency is invalid
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue("Expected Excluded for invalid currency", result is NormalizationResult.Excluded)
        val excluded = result as NormalizationResult.Excluded
        assertEquals(FailureReason.INVALID_CURRENCY, excluded.failure.reason)
        assertEquals(CurrencyCode("XXX"), excluded.failure.originalAmount.currency)
        assertEquals("123", excluded.failure.rawOriginalCurrency)
    }

    @Test
    fun `aggregate expenses with invalid currency returns excluded row not crash`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "USD", NOW),
            fakeExpense(2, 50.0, "AB1", NOW),   // invalid currency (contains digit)
            fakeExpense(3, 20.0, "E1R", NOW)   // invalid (contains digit)
        )
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue(aggregate.isPartial)
        // USD should convert; two invalid currencies should be excluded
        assertEquals(2, aggregate.conversionFailures.size)
        assertMoneyEquals(92.0, aggregate.displayAmount, "only USD converted")
        assertEquals(
            2,
            aggregate.conversionFailures.count { it.reason == FailureReason.INVALID_CURRENCY }
        )
        assertEquals(3, aggregate.totalTransactionCount)
        assertEquals(2, aggregate.sourceBuckets.single { it.currency == CurrencyCode("XXX") }.transactionCount)
        assertFalse(aggregate.sourceBuckets.any { it.currency == CurrencyCode.EUR })
    }

    @Test
    fun `aggregate expenses all invalid currency returns UNAVAILABLE quality`() = runTest {
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(
            fakeExpense(1, 100.0, "XYZ123", NOW),
            fakeExpense(2, 50.0, "AB", NOW) // too short
        )
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)

        assertTrue(aggregate.isPartial)
        assertEquals(ConversionQuality.UNAVAILABLE, aggregate.conversionQuality)
        assertMoneyExact(0.0, aggregate.displayAmount, "displayAmount")
        assertEquals(2, aggregate.conversionFailures.size)
        assertEquals(2, aggregate.totalTransactionCount)
        assertEquals(2, aggregate.sourceBuckets.single().transactionCount)
        assertEquals(CurrencyCode("XXX"), aggregate.sourceBuckets.single().currency)
    }

    // --- BucketDatePolicy enforcement ---

    @Test
    fun `bucket policy require date missing date fails bucket`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val buckets = listOf(
            MoneyBucketInput(100.0, CurrencyCode("USD"), 5, bucketDate = null)
        )
        val aggregate = engine.aggregateBuckets(buckets, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE, BucketDatePolicy.RequireBucketDate)

        assertTrue(aggregate.isPartial)
        assertMoneyExact(0.0, aggregate.displayAmount, "displayAmount")
        assertEquals(1, aggregate.conversionFailures.size)
        assertEquals(1, aggregate.sourceBuckets.size)
        assertEquals(5, aggregate.totalTransactionCount)
        assertEquals(0, aggregate.metadata.includedTransactionCount)
        assertEquals(5, aggregate.metadata.excludedTransactionCount)
    }

    // --- Helpers ---

    private fun fakeExpense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long,
        transactionType: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null
    ) =
        com.yourname.expensetracker.data.database.entity.Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "Test",
            transactionType = transactionType,
            date = date,
            isNotMine = isNotMine,
            isSharedExpense = isSharedExpense,
            myShareAmount = myShareAmount
        )
}

// --- Test doubles ---

private class FakeTimeProvider(private val now: Long) : TimeProvider {
    override fun now(): Long = now
}

private class FakeExchangeRateStore : ExchangeRateStore {
    val rates = mutableMapOf<String, DomainExchangeRate>()
    val ratesAsOf = mutableMapOf<String, DomainExchangeRate>()
    var latestByValidDate = false

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        if (latestByValidDate) {
            // Simulate validDate-ordered lookup
            val candidates = rates.values.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
            return candidates.maxByOrNull { it.validDate ?: 0L }
        }
        return rates["${fromCurrency}_${toCurrency}"]
    }

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        val candidates = rates.values.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
        return candidates.maxByOrNull { it.validDate ?: 0L }
            ?: rates["${fromCurrency}_${toCurrency}"]
            ?: rates["${fromCurrency}_${toCurrency}_fresh"]
    }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        return ratesAsOf["${fromCurrency}_${toCurrency}_$atMillis"]
            ?: rates["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}
