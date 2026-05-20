package com.yourname.expensetracker.domain.core.money

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
        assertEquals(0.92, converted.rateUsed, 0.001)
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
    fun `convertOutcome transaction date without atMillis fails`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)

        val outcome = converter.convertOutcome(100.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, atMillis = null)
        assertTrue("Should fail without date", outcome is ConversionOutcome.Failed)
    }

    @Test
    fun `convertOutcome period end without atMillis fails`() = runTest {
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.92, lastUpdated = NOW, source = "api", validDate = NOW)

        val outcome = converter.convertOutcome(100.0, "USD", "EUR", RateBasis.PERIOD_END, atMillis = null)
        assertTrue("Should fail without date", outcome is ConversionOutcome.Failed)
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
        assertEquals(92.0, aggregate.displayAmount, 0.01) // Only USD converted
        assertEquals(RateBasis.LATEST_AVAILABLE, aggregate.rateBasis)
    }

    @Test
    fun `aggregate expenses transaction date sets rateBasis`() = runTest {
        store.ratesAsOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "api", validDate = NOW)
        val engine = MoneyNormalizationEngine(converter)
        val expenses = listOf(fakeExpense(1, 100.0, "USD", NOW))
        val aggregate = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.rateBasis)
        assertEquals(90.0, aggregate.displayAmount, 0.01)
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
        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(1, aggregate.conversionFailures.size)
    }

    // --- Helpers ---

    private fun fakeExpense(id: Long, amount: Double, currency: String, date: Long) =
        com.yourname.expensetracker.data.database.entity.Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "Test",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = date
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
