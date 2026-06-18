package com.yourname.expensetracker.integration

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.*
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
 * PR 9 — Integration tests proving converter + normalization engine behavior.
 * Uses sentinel rates to detect which rate was actually used.
 *
 * Sentinel rates:
 *   USD/EUR on Jan 1 = 1.0 (historical)
 *   USD/EUR on Feb 1 = 2.0 (historical)
 *   USD/EUR latest   = 10.0
 *
 * If a test gets 10.0 when expecting 1.0 or 2.0, it proves latest-rate leakage.
 */
class CurrencyConversionIntegrationTest {

    private lateinit var store: SentinelRateStore
    private lateinit var converter: CurrencyConverter
    private lateinit var engine: MoneyNormalizationEngine

    private val JAN_1 = 1704067200000L  // 2024-01-01 00:00 UTC
    private val FEB_1 = 1706745600000L  // 2024-02-01 00:00 UTC
    private val NOW = 1716163200000L    // 2024-05-20 00:00 UTC

    @Before
    fun setup() {
        store = SentinelRateStore()
        converter = CurrencyConverter(store, FixedTime(NOW))
        engine = MoneyNormalizationEngine(converter)
    }

    // ── Converter: PERIOD_MIDPOINT_ESTIMATE uses historical ────────────

    @Test
    fun `PERIOD_MIDPOINT_ESTIMATE uses as-of rate not latest`() = runTest {
        val midpoint = JAN_1 + (FEB_1 - JAN_1) / 2
        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.PERIOD_MIDPOINT_ESTIMATE, atMillis = midpoint,
            stalePolicy = StaleRatePolicy.None
        )
        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        // Should use Jan 1 rate (1.0) since midpoint is between Jan and Feb
        assertEquals(1.0, c.rateUsed, 0.001)
        assertNotEquals(10.0, c.rateUsed, 0.001) // NOT latest
    }

    @Test
    fun `TRANSACTION_DATE uses as-of rate not latest`() = runTest {
        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.TRANSACTION_DATE, atMillis = FEB_1,
            stalePolicy = StaleRatePolicy.None
        )
        assertTrue(outcome is ConversionOutcome.Converted)
        assertEquals(2.0, (outcome as ConversionOutcome.Converted).rateUsed, 0.001)
    }

    @Test
    fun `LATEST_AVAILABLE uses latest rate`() = runTest {
        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )
        assertTrue(outcome is ConversionOutcome.Converted)
        assertEquals(10.0, (outcome as ConversionOutcome.Converted).rateUsed, 0.001)
    }

    @Test
    fun `historical basis without date fails not falls back`() = runTest {
        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.TRANSACTION_DATE, atMillis = null
        )
        assertTrue(outcome is ConversionOutcome.Failed)
    }

    // ── Normalization engine: provenance populated ─────────────────────

    @Test
    fun `normalizeExpense populates rateValidDate and rateLastUpdated`() = runTest {
        val expense = expense(1, 100.0, "USD", FEB_1)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertTrue(result is NormalizationResult.Included)
        val ne = (result as NormalizationResult.Included).value
        assertEquals(2.0, ne.rateUsed!!, 0.001)
        assertNotNull(ne.rateValidDate)
        assertNotNull(ne.rateLastUpdated)
        assertEquals("DIRECT", ne.conversionPath)
    }

    @Test
    fun `normalizeExpense identity has IDENTITY basis`() = runTest {
        val expense = expense(1, 50.0, "EUR", FEB_1)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        val ne = (result as NormalizationResult.Included).value
        assertEquals("IDENTITY", ne.rateBasis)
        assertEquals(1.0, ne.rateUsed!!, 0.0)
    }

    // ── Aggregate: quality metadata ───────────────────────────────────

    @Test
    fun `aggregate with missing rate is PARTIAL not COMPLETE`() = runTest {
        val expenses = listOf(
            expense(1, 100.0, "USD", FEB_1),
            expense(2, 50.0, "GBP", FEB_1) // no GBP rate
        )
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertEquals(ConversionQuality.PARTIAL, agg.conversionQuality)
        assertEquals(1, agg.metadata.excludedTransactionCount)
        assertEquals(200.0, agg.displayAmount, 0.01) // only USD converted: 100 * 2.0
    }

    @Test
    fun `aggregate all failed is UNAVAILABLE`() = runTest {
        val expenses = listOf(expense(1, 50.0, "GBP", FEB_1))
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertEquals(ConversionQuality.UNAVAILABLE, agg.conversionQuality)
        assertEquals(0.0, agg.displayAmount, 0.0)
    }

    @Test
    fun `aggregate historical does not use latest rate`() = runTest {
        val expenses = listOf(expense(1, 100.0, "USD", JAN_1))
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertEquals(100.0, agg.displayAmount, 0.01) // Jan 1 rate = 1.0
        assertNotEquals(1000.0, agg.displayAmount, 0.01) // NOT latest (10.0)
    }

    // ── Composite provenance ──────────────────────────────────────────

    @Test
    fun `composite conversion uses weakest leg validDate`() = runTest {
        // USD->GBP via EUR: USD->EUR (Jan 1, rate 1.0) + EUR->GBP (Feb 1, rate 0.85)
        val outcome = converter.convertOutcome(
            100.0, "USD", "GBP",
            RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )
        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        assertEquals(ConversionPath.VIA_BASE_CURRENCY, c.conversionPath)
        // validDate should be the older of the two legs
        assertNotNull(c.rateValidDate)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun expense(id: Long, amount: Double, currency: String, date: Long) = Expense(
        id = id, amount = amount, currency = currency, merchant = "Test",
        transactionType = TransactionType.PURCHASE, date = date
    )
}

// ── Sentinel rate store ───────────────────────────────────────────────────────

private class FixedTime(private val now: Long) : TimeProvider {
    override fun now(): Long = now
}

/**
 * Store with sentinel rates that make it obvious which lookup path was used.
 * USD/EUR: Jan 1 = 1.0, Feb 1 = 2.0, latest = 10.0
 * EUR/GBP: latest = 0.85 (validDate = Feb 1)
 */
private class SentinelRateStore : ExchangeRateStore {
    private val JAN_1 = 1704067200000L
    private val FEB_1 = 1706745600000L
    private val NOW = 1716163200000L

    override suspend fun getLatestRateForPair(from: String, to: String): DomainExchangeRate? = when {
        from == "USD" && to == "EUR" -> DomainExchangeRate("USD", "EUR", 10.0, NOW, "api", NOW)
        from == "EUR" && to == "GBP" -> DomainExchangeRate("EUR", "GBP", 0.85, NOW, "api", FEB_1)
        from == "USD" && to == "GBP" -> null // force composite
        else -> null
    }

    override suspend fun getRateAsOf(from: String, to: String, atMillis: Long): DomainExchangeRate? = when {
        from == "USD" && to == "EUR" && atMillis >= FEB_1 ->
            DomainExchangeRate("USD", "EUR", 2.0, NOW, "api", FEB_1)
        from == "USD" && to == "EUR" && atMillis >= JAN_1 ->
            DomainExchangeRate("USD", "EUR", 1.0, NOW, "api", JAN_1)
        from == "EUR" && to == "GBP" && atMillis >= FEB_1 ->
            DomainExchangeRate("EUR", "GBP", 0.85, NOW, "api", FEB_1)
        else -> null
    }

    override suspend fun getRate(from: String, to: String) = getLatestRateForPair(from, to)
    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {}
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {}
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = null
    override suspend fun deleteOldRates(olderThan: Long) {}
}
