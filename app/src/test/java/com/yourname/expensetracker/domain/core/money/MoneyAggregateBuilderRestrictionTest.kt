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

    private lateinit var converter: CurrencyConverter
    private val NOW = 1716163200000L

    @Before
    fun setup() {
        val store = TestRateStore()
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
    }
}

private class TestTime(private val now: Long) : TimeProvider {
    override fun now(): Long = now
}

private class TestRateStore : ExchangeRateStore {
    val rates = mutableMapOf<String, DomainExchangeRate>()

    override suspend fun getRate(fromCurrency: String, toCurrency: String) = rates["${fromCurrency}_${toCurrency}"]
    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String) = rates["${fromCurrency}_${toCurrency}"]
    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long) =
        rates["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }
    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}
