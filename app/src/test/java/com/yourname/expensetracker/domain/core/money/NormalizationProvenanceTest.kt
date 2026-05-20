package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense
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
 * PR 2 — Normalization provenance completion tests.
 * Covers CURR-70F-05: NormalizedExpense must carry rate provenance.
 */
class NormalizationProvenanceTest {

    private lateinit var store: FakeRateStore
    private lateinit var converter: CurrencyConverter
    private lateinit var engine: MoneyNormalizationEngine

    private val NOW = 1716163200000L
    private val DAY = 86400000L

    @Before
    fun setup() {
        store = FakeRateStore()
        converter = CurrencyConverter(store, FakeTimeProv(NOW))
        engine = MoneyNormalizationEngine(converter)
    }

    // ── rateValidDate and rateLastUpdated populated ────────────────────

    @Test
    fun `normalizeExpense includes rateValidDate from outcome`() = runTest {
        val validDate = NOW - 2 * DAY
        store.asOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "ecb", validDate)

        val result = engine.normalizeExpense(expense(1, 100.0, "USD", NOW), CurrencyCode.EUR)
        assertTrue(result is NormalizationResult.Included)
        val ne = (result as NormalizationResult.Included).value
        assertEquals(validDate, ne.rateValidDate)
    }

    @Test
    fun `normalizeExpense includes rateLastUpdated from outcome`() = runTest {
        val lastUpdated = NOW - 1000L
        store.asOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, lastUpdated, "ecb", NOW)

        val result = engine.normalizeExpense(expense(1, 100.0, "USD", NOW), CurrencyCode.EUR)
        assertTrue(result is NormalizationResult.Included)
        val ne = (result as NormalizationResult.Included).value
        assertEquals(lastUpdated, ne.rateLastUpdated)
    }

    @Test
    fun `normalizeExpense includes conversionPath`() = runTest {
        store.asOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "ecb", NOW)

        val result = engine.normalizeExpense(expense(1, 100.0, "USD", NOW), CurrencyCode.EUR)
        val ne = (result as NormalizationResult.Included).value
        assertEquals("DIRECT", ne.conversionPath)
    }

    // ── Identity rows ──────────────────────────────────────────────────

    @Test
    fun `normalizeExpense identity records IDENTITY basis`() = runTest {
        val result = engine.normalizeExpense(expense(1, 50.0, "EUR", NOW), CurrencyCode.EUR)
        val ne = (result as NormalizationResult.Included).value
        assertEquals("IDENTITY", ne.rateBasis)
        assertEquals("IDENTITY", ne.conversionPath)
        assertEquals(1.0, ne.rateUsed!!, 0.0)
        assertNull(ne.rateValidDate)
        assertNull(ne.rateLastUpdated)
    }

    // ── Aggregate metadata counts ──────────────────────────────────────

    @Test
    fun `aggregate metadata counts missing and invalid correctly`() = runTest {
        store.asOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "ecb", NOW)
        // GBP has no rate → MISSING_RATE
        // XYZ is invalid currency → INVALID_AMOUNT

        val expenses = listOf(
            expense(1, 100.0, "USD", NOW),
            expense(2, 50.0, "GBP", NOW),
            expense(3, 25.0, "XYZ", NOW)
        )
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertEquals(1, agg.metadata.includedTransactionCount)
        assertEquals(2, agg.metadata.excludedTransactionCount)
        assertTrue(agg.metadata.missingRateCount >= 1)
        assertTrue(agg.metadata.invalidCurrencyCount >= 1)
    }

    @Test
    fun `aggregate quality UNAVAILABLE when all rows fail`() = runTest {
        // No rates at all
        val expenses = listOf(expense(1, 100.0, "USD", NOW))
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertEquals(ConversionQuality.UNAVAILABLE, agg.conversionQuality)
    }

    @Test
    fun `aggregate quality PARTIAL when some rows fail`() = runTest {
        store.asOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "ecb", NOW)
        val expenses = listOf(
            expense(1, 100.0, "USD", NOW),
            expense(2, 50.0, "GBP", NOW) // no rate
        )
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertEquals(ConversionQuality.PARTIAL, agg.conversionQuality)
    }

    @Test
    fun `aggregate quality COMPLETE when all succeed`() = runTest {
        store.asOf["USD_EUR_$NOW"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "ecb", NOW)
        val expenses = listOf(expense(1, 100.0, "USD", NOW))
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)

        assertEquals(ConversionQuality.COMPLETE, agg.conversionQuality)
    }

    @Test
    fun `aggregate quality ESTIMATED for PERIOD_MIDPOINT_ESTIMATE`() = runTest {
        val midpoint = NOW - 15 * DAY
        store.asOf["USD_EUR_$midpoint"] = DomainExchangeRate("USD", "EUR", 0.88, NOW, "ecb", midpoint)
        store.latest["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.88, NOW, "ecb", midpoint)

        val buckets = listOf(MoneyBucketInput(100.0, CurrencyCode("USD"), 3, bucketDate = midpoint))
        val agg = engine.aggregateBuckets(buckets, CurrencyCode.EUR, RateBasis.PERIOD_MIDPOINT_ESTIMATE, BucketDatePolicy.RequireBucketDate)

        assertEquals(ConversionQuality.ESTIMATED, agg.conversionQuality)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun expense(id: Long, amount: Double, currency: String, date: Long) = Expense(
        id = id, amount = amount, currency = currency, merchant = "Test",
        transactionType = TransactionType.PURCHASE, date = date
    )
}

// ── Test doubles ───────────────────────────────────────────────────────

private class FakeTimeProv(private val now: Long) : TimeProvider {
    override fun now(): Long = now
}

private class FakeRateStore : ExchangeRateStore {
    val latest = mutableMapOf<String, DomainExchangeRate>()
    val asOf = mutableMapOf<String, DomainExchangeRate>()

    override suspend fun getRate(fromCurrency: String, toCurrency: String) =
        latest["${fromCurrency}_${toCurrency}"]

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String) =
        latest["${fromCurrency}_${toCurrency}"]

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long) =
        asOf["${fromCurrency}_${toCurrency}_$atMillis"]
            ?: latest["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { latest["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = latest.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}
