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
 * PR 3 — Legacy aggregate API restriction tests.
 * Covers CURR-70F-06 and CURR-70F-07.
 */
class MoneyAggregateBuilderRestrictionTest {

    private lateinit var store: TestRateStore
    private lateinit var converter: CurrencyConverter
    private val NOW = 1716163200000L
    private val DAY = 86400000L

    @Before
    fun setup() {
        store = TestRateStore()
        store.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "api", NOW)
        converter = CurrencyConverter(store, TestTime(NOW))
    }

    // ── CURR-70F-06: Legacy fromBuckets rejects non-LATEST basis ───────

    @Test(expected = IllegalArgumentException::class)
    fun `legacy fromBuckets rejects TRANSACTION_DATE basis`() = runTest {
        @Suppress("DEPRECATION")
        MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter,
            rateBasis = RateBasis.TRANSACTION_DATE
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `legacy fromBuckets rejects PERIOD_END basis`() = runTest {
        @Suppress("DEPRECATION")
        MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter,
            rateBasis = RateBasis.PERIOD_END
        )
    }

    @Test
    fun `legacy fromBuckets accepts LATEST_AVAILABLE`() = runTest {
        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE
        )
        assertEquals(RateBasis.LATEST_AVAILABLE, result.rateBasis)
    }

    // ── CURR-70F-07: Typed builder enforces RequireBucketDate ──────────

    @Test
    fun `typed builder RequireBucketDate missing date fails before converter`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(MoneyBucketInput(100.0, CurrencyCode("USD"), 5, bucketDate = null)),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.TRANSACTION_DATE,
            bucketDatePolicy = BucketDatePolicy.RequireBucketDate
        )
        assertTrue(result.isPartial)
        assertEquals(0.0, result.displayAmount, 0.0)
        assertEquals(1, result.conversionFailures.size)
        assertEquals(1, result.sourceBuckets.size)
        assertEquals(5, result.totalTransactionCount)
        assertEquals(0, result.metadata.includedTransactionCount)
        assertEquals(5, result.metadata.excludedTransactionCount)
        assertEquals(0, store.lookupCount)
    }

    @Test
    fun `typed builder RequireBucketDate with date succeeds`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(MoneyBucketInput(100.0, CurrencyCode("USD"), 5, bucketDate = NOW)),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.TRANSACTION_DATE,
            bucketDatePolicy = BucketDatePolicy.RequireBucketDate
        )
        assertFalse(result.isPartial)
        assertEquals(90.0, result.displayAmount, 0.01)
        assertEquals(1, result.sourceBuckets.size)
        assertEquals(5, result.totalTransactionCount)
        assertEquals(5, result.metadata.includedTransactionCount)
        assertEquals(0, result.metadata.excludedTransactionCount)
    }

    @Test
    fun `typed builder identity success preserves source and count without converter lookup`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(MoneyBucketInput(25.0, CurrencyCode.EUR, 4)),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )

        assertFalse(result.isPartial)
        assertEquals(25.0, result.displayAmount, 0.0)
        assertEquals(1, result.sourceBuckets.size)
        assertEquals(4, result.totalTransactionCount)
        assertEquals(4, result.metadata.includedTransactionCount)
        assertEquals(0, result.metadata.excludedTransactionCount)
        assertEquals(0, store.lookupCount)
    }

    @Test
    fun `typed builder conserves two included and three failed transactions`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(
                MoneyBucketInput(40.0, CurrencyCode.EUR, 2),
                MoneyBucketInput(75.0, CurrencyCode("GBP"), 3)
            ),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )

        assertTrue(result.isPartial)
        assertEquals(40.0, result.displayAmount, 0.0)
        assertEquals(2, result.sourceBuckets.size)
        assertEquals(5, result.totalTransactionCount)
        assertEquals(3, result.failedTransactionCount)
        assertEquals(1, result.failedBucketCount)
        assertEquals(2, result.metadata.includedTransactionCount)
        assertEquals(3, result.metadata.excludedTransactionCount)
    }

    @Test
    fun `typed builder retains every source when all conversions fail`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(
                MoneyBucketInput(50.0, CurrencyCode("GBP"), 2),
                MoneyBucketInput(5000.0, CurrencyCode("JPY"), 3)
            ),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )

        assertEquals(0.0, result.displayAmount, 0.0)
        assertEquals(2, result.sourceBuckets.size)
        assertEquals(5, result.totalTransactionCount)
        assertEquals(5, result.failedTransactionCount)
        assertEquals(0, result.metadata.includedTransactionCount)
        assertEquals(5, result.metadata.excludedTransactionCount)
    }

    @Test
    fun `typed builder preserves repeated currency buckets and distinct dates`() = runTest {
        val firstDate = NOW - DAY
        store.ratesAsOf["USD_EUR_$firstDate"] =
            DomainExchangeRate("USD", "EUR", 0.80, NOW, "api", firstDate)
        store.ratesAsOf["USD_EUR_$NOW"] =
            DomainExchangeRate("USD", "EUR", 0.90, NOW, "api", NOW)

        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(
                MoneyBucketInput(10.0, CurrencyCode("USD"), 1, bucketDate = firstDate),
                MoneyBucketInput(20.0, CurrencyCode("USD"), 2, bucketDate = NOW)
            ),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.TRANSACTION_DATE,
            bucketDatePolicy = BucketDatePolicy.RequireBucketDate
        )

        assertEquals(26.0, result.displayAmount, 0.01)
        assertEquals(2, result.sourceBuckets.size)
        assertEquals(3, result.totalTransactionCount)
        assertEquals(listOf(firstDate, NOW), store.asOfLookups)
    }

    @Test
    fun `typed builder preserves zero and negative finite source amounts`() = runTest {
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(
                MoneyBucketInput(0.0, CurrencyCode.EUR, 1),
                MoneyBucketInput(-5.0, CurrencyCode.EUR, 2)
            ),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )

        assertEquals(-5.0, result.displayAmount, 0.0)
        assertEquals(listOf(0.0, -5.0), result.sourceBuckets.map { it.amount })
        assertEquals(3, result.totalTransactionCount)
    }

    @Test
    fun `typed builder empty and zero count inputs do not invent transactions`() = runTest {
        val empty = MoneyAggregateBuilder.fromBuckets(
            buckets = emptyList(),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )
        val zeroCount = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(MoneyBucketInput(10.0, CurrencyCode.EUR, 0)),
            homeCurrency = CurrencyCode.EUR,
            converter = converter,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            bucketDatePolicy = BucketDatePolicy.Latest
        )

        assertEquals(0, empty.totalTransactionCount)
        assertTrue(empty.sourceBuckets.isEmpty())
        assertEquals(10.0, zeroCount.displayAmount, 0.0)
        assertEquals(0, zeroCount.totalTransactionCount)
        assertEquals(1, zeroCount.sourceBuckets.size)
    }

    @Test
    fun `legacy count contracts remain explicit incomplete and count agnostic`() = runTest {
        @Suppress("DEPRECATION")
        val explicit = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(10.0 to "EUR", 10.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter,
            transactionCounts = listOf(2, 3)
        )
        @Suppress("DEPRECATION")
        val incomplete = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(10.0 to "EUR", 10.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter,
            transactionCounts = listOf(2)
        )
        @Suppress("DEPRECATION")
        val countAgnostic = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(10.0 to "EUR", 10.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter
        )

        assertEquals(5, explicit.totalTransactionCount)
        assertFalse(explicit.metadata.countsIncomplete)
        assertEquals(2, incomplete.totalTransactionCount)
        assertTrue(incomplete.metadata.countsIncomplete)
        assertEquals(0, countAgnostic.totalTransactionCount)
        assertFalse(countAgnostic.metadata.countsIncomplete)
    }
}

private class TestTime(private val now: Long) : TimeProvider {
    override fun now(): Long = now
}

private class TestRateStore : ExchangeRateStore {
    val rates = mutableMapOf<String, DomainExchangeRate>()
    val ratesAsOf = mutableMapOf<String, DomainExchangeRate>()
    val asOfLookups = mutableListOf<Long>()
    var lookupCount: Int = 0

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        lookupCount++
        return rates["${fromCurrency}_${toCurrency}"]
    }

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        lookupCount++
        return rates["${fromCurrency}_${toCurrency}"]
    }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        lookupCount++
        asOfLookups += atMillis
        return ratesAsOf["${fromCurrency}_${toCurrency}_$atMillis"]
            ?: rates["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }
    }
    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}
