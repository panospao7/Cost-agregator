package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.*
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Behavioral regression tests for currency normalization (PR 10).
 * Tests the exact bugs identified in the deep evaluation report.
 */
class CurrencyNormalizationBehavioralTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var exchangeRateStore: FakeExchangeRateStore
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var normalizationEngine: MoneyNormalizationEngine

    @Before
    fun setup() {
        timeProvider = FakeTimeProvider()
        exchangeRateStore = FakeExchangeRateStore()
        currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        normalizationEngine = MoneyNormalizationEngine(currencyConverter)
    }

    // ── PR A: Exchange-rate correctness ────────────────────────────────

    @Test
    fun `latest rate prefers fresh manual rate over historical backfill`() = runTest {
        val now = timeProvider.now()
        val yesterday = now - 86400000L
        
        // Historical backfill inserted today
        exchangeRateStore.insertRate(
            DomainExchangeRate("USD", "EUR", 0.85, lastUpdated = now, validDate = yesterday)
        )
        
        // Fresh manual rate inserted today
        currencyConverter.storeRate("USD", "EUR", 0.92, "manual")
        
        // Latest lookup should prefer the fresh rate (validDate = today)
        val latest = exchangeRateStore.getLatestRateForPair("USD", "EUR")
        assertNotNull(latest)
        assertEquals(0.92, latest!!.rate, 0.001)
    }

    @Test
    fun `historical rate uses as-of date not lastUpdated`() = runTest {
        val now = timeProvider.now()
        val twoDaysAgo = now - 2 * 86400000L
        val startOfTwoDaysAgo = (twoDaysAgo / 86400000L) * 86400000L
        
        // Rate valid two days ago, inserted today
        exchangeRateStore.insertRate(
            DomainExchangeRate("USD", "EUR", 0.88, lastUpdated = now, validDate = startOfTwoDaysAgo)
        )
        
        // Historical lookup at two days ago should find it
        val historical = exchangeRateStore.getRateAsOf("USD", "EUR", twoDaysAgo)
        assertNotNull(historical)
        assertEquals(0.88, historical!!.rate, 0.001)
    }

    // ── PR B: ConversionOutcome semantics ──────────────────────────────

    @Test
    fun `convertOutcome transaction date without date fails`() = runTest {
        val outcome = currencyConverter.convertOutcome(
            amount = 100.0,
            fromCurrency = "USD",
            toCurrency = "EUR",
            rateBasis = RateBasis.TRANSACTION_DATE,
            atMillis = null
        )
        
        assertTrue(outcome is ConversionOutcome.Failed)
        val failed = outcome as ConversionOutcome.Failed
        assertEquals(ConversionFailureType.MISSING_HISTORICAL_RATE, failed.failureType)
    }

    @Test
    fun `convertOutcome period end without date fails`() = runTest {
        val outcome = currencyConverter.convertOutcome(
            amount = 100.0,
            fromCurrency = "USD",
            toCurrency = "EUR",
            rateBasis = RateBasis.PERIOD_END,
            atMillis = null
        )
        
        assertTrue(outcome is ConversionOutcome.Failed)
    }

    @Test
    fun `stale historical rate uses validDate not lastUpdated`() = runTest {
        val now = timeProvider.now()
        val oldDate = now - 10 * 86400000L
        val startOfOldDate = (oldDate / 86400000L) * 86400000L
        
        // Old rate inserted today
        exchangeRateStore.insertRate(
            DomainExchangeRate("USD", "EUR", 0.85, lastUpdated = now, validDate = startOfOldDate)
        )
        
        // Historical conversion with staleness check using TRANSACTION_DATE reference:
        // compares |atMillis - rate.validDate| = |oldDate - startOfOldDate| ≈ 0 → fresh
        val outcome = currencyConverter.convertOutcome(
            amount = 100.0,
            fromCurrency = "USD",
            toCurrency = "EUR",
            rateBasis = RateBasis.TRANSACTION_DATE,
            atMillis = oldDate,
            stalePolicy = StaleRatePolicy(maxAgeMs = 86400000L, compareAgainst = StaleRateReference.TRANSACTION_DATE)
        )
        
        // Should succeed because rate validDate is close to transaction date
        assertTrue(outcome is ConversionOutcome.Converted)
    }

    // ── PR C: MoneyAggregate rateBasis ─────────────────────────────────

    @Test
    fun `aggregateExpenses transaction date sets rateBasis`() = runTest {
        val now = timeProvider.now()
        exchangeRateStore.insertRate(
            DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = now, validDate = now)
        )
        
        val expenses = listOf(
            createExpense(1, 100.0, "USD", now)
        )
        
        val aggregate = normalizationEngine.aggregateExpenses(
            expenses = expenses,
            homeCurrency = CurrencyCode("EUR"),
            rateBasis = RateBasis.TRANSACTION_DATE
        )
        
        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.rateBasis)
        assertEquals(RateBasis.TRANSACTION_DATE, aggregate.requestedRateBasis)
    }

    @Test
    fun `empty aggregate preserves explicit rateBasis`() = runTest {
        val aggregate = MoneyAggregate.empty(CurrencyCode("EUR"), RateBasis.PERIOD_END)
        
        assertEquals(RateBasis.PERIOD_END, aggregate.rateBasis)
        assertEquals(RateBasis.PERIOD_END, aggregate.requestedRateBasis)
    }

    @Test
    fun `single currency identity does not default to latest`() = runTest {
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
        val now = timeProvider.now()
        // Only EUR rate available
        exchangeRateStore.insertRate(
            DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = now, validDate = now)
        )
        
        val expenses = listOf(
            createExpense(1, 100.0, "USD", now),
            createExpense(2, 50.0, "GBP", now), // No rate
            createExpense(3, 75.0, "EUR", now)
        )
        
        val aggregate = normalizationEngine.aggregateExpenses(
            expenses = expenses,
            homeCurrency = CurrencyCode("EUR"),
            rateBasis = RateBasis.TRANSACTION_DATE
        )
        
        assertTrue(aggregate.isPartial)
        assertEquals(2, aggregate.metadata.includedTransactionCount)
        assertEquals(1, aggregate.metadata.excludedTransactionCount)
        assertEquals(1, aggregate.metadata.missingRateCount)
    }

    @Test
    fun `bucket policy require date missing date fails`() = runTest {
        val buckets = listOf(
            MoneyBucketInput(
                amount = 100.0,
                currency = CurrencyCode("USD"),
                transactionCount = 1,
                bucketDate = null // Missing date
            )
        )
        
        val aggregate = normalizationEngine.aggregateBuckets(
            buckets = buckets,
            homeCurrency = CurrencyCode("EUR"),
            rateBasis = RateBasis.TRANSACTION_DATE,
            bucketDatePolicy = BucketDatePolicy.RequireBucketDate
        )
        
        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.conversionFailures.size)
    }

    // ── Helper methods ──────────────────────────────────────────────────

    private fun createExpense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long
    ): Expense {
        return Expense(
            id = id,
            merchant = "Test",
            amount = amount,
            currency = currency,
            date = date,
            transactionType = TransactionType.PURCHASE,
            categoryId = null,
            isNotMine = false,
            source = "test",
            createdAt = date
        )
    }
}

private class FakeTimeProvider : TimeProvider {
    override fun now(): Long = 1716163200000L // 2024-05-20 00:00 UTC
}

private class FakeExchangeRateStore : ExchangeRateStore {
    private val rates = mutableListOf<DomainExchangeRate>()

    fun insertRate(rate: DomainExchangeRate) { rates.add(rate) }

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        rates.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
            .maxByOrNull { it.validDate ?: 0L }

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        rates.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
            .maxByOrNull { it.validDate ?: 0L }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? =
        rates.filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency && (it.validDate ?: 0L) <= atMillis }
            .maxByOrNull { it.validDate ?: 0L }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rates.add(rate) }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { this.rates.addAll(rates) }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = rates.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) { rates.removeAll { it.lastUpdated < olderThan } }
}
