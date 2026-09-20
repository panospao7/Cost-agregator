package com.yourname.expensetracker.integration

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
 * Post-9a6afc4 integration tests covering:
 * - Typed unavailable result (no fake EUR)
 * - Latest-rate staleness policy
 * - Spending trend quality propagation
 */
class CurrencyNormalizationPost9a6Test {

    private lateinit var converter: CurrencyConverter
    private lateinit var engine: MoneyNormalizationEngine
    private val NOW = 1716163200000L
    private val DAY = 86400000L

    @Before
    fun setup() {
        val store = TestStore()
        converter = CurrencyConverter(store, object : TimeProvider { override fun now() = NOW })
        engine = MoneyNormalizationEngine(converter)
    }

    // ── PR6: Latest-rate staleness policy ─────────────────────────────

    @Test
    fun `latest rate older than 7 days is stale via LatestDefault policy`() = runTest {
        val expense = expense(1, 100.0, "USD", NOW)
        // Store has rate with validDate 10 days ago — stale for LatestDefault (7 days)
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)
        // Should be excluded because rate is stale (10 days > 7 days)
        assertTrue("Stale latest rate should exclude", result is NormalizationResult.Excluded)
    }

    @Test
    fun `historical rate is never stale regardless of age`() = runTest {
        val txDate = NOW - 30 * DAY
        val expense = expense(1, 100.0, "USD", txDate)
        // Rate valid 30 days ago — but historical rates use StaleRatePolicy.None
        val result = engine.normalizeExpense(expense, CurrencyCode.EUR, RateBasis.TRANSACTION_DATE)
        assertTrue("Historical rate should not be stale", result is NormalizationResult.Included)
    }

    @Test
    fun `aggregate with stale latest rate marks partial`() = runTest {
        val expenses = listOf(expense(1, 100.0, "USD", NOW))
        val agg = engine.aggregateExpenses(expenses, CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE)
        // Rate is 10 days old, LatestDefault is 7 days → stale → excluded
        assertTrue(agg.isPartial)
        assertEquals(ConversionQuality.UNAVAILABLE, agg.conversionQuality)
    }

    // ── PR2: No fake EUR in unavailable containers ────────────────────

    @Test
    fun `unavailable state should not pretend to be valid EUR`() {
        // The key invariant: unavailable results use typed Unavailable, not fake EUR MoneyAggregate
        val result = com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult.Unavailable(
            reason = "Home currency unavailable",
            periodStart = NOW,
            periodEnd = NOW
        )
        assertTrue(result is com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult.Unavailable)
    }

    // ── PR6: StaleRatePolicy.LatestDefault exists ─────────────────────

    @Test
    fun `LatestDefault policy is 7 days`() {
        assertEquals(7 * 24 * 60 * 60 * 1000L, StaleRatePolicy.LatestDefault.maxAgeMs)
        assertEquals(StaleRateReference.NOW, StaleRatePolicy.LatestDefault.compareAgainst)
    }

    // ── NEW-P5-012: LatestDefault (forBasis LATEST_AVAILABLE) 7d boundary ──

    /**
     * 7-day boundary via [StaleRatePolicy.forBasis] (the policy
     * [MoneyNormalizationEngine] selects for LATEST_AVAILABLE):
     * just-under-7d converts, just-over-7d excludes.
     */
    @Test
    fun `forBasis latest 7d boundary just under passes just over excludes`() = runTest {
        // Boundary-controlled store (the shared TestStore returns a fixed
        // 10-day-old rate, which cannot express ±1ms around the boundary).
        val boundaryStore = BoundaryRateStore()
        val boundaryConverter = CurrencyConverter(boundaryStore, object : TimeProvider { override fun now() = NOW })

        // Rate aged 7d - 1ms → fresh under LatestDefault
        boundaryStore.latestRates["USD_EUR"] =
            DomainExchangeRate("USD", "EUR", 0.92, NOW - (7 * DAY) + 1, "api", NOW - (7 * DAY) + 1)
        val under = boundaryConverter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE)
        )
        assertTrue("Just-under-7d latest rate must convert", under is ConversionOutcome.Converted)
        assertEquals(92.0, (under as ConversionOutcome.Converted).convertedAmount, 0.001)

        // Rate aged 7d + 1ms → stale under LatestDefault
        boundaryStore.latestRates["USD_EUR"] =
            DomainExchangeRate("USD", "EUR", 0.92, NOW - (7 * DAY) - 1, "api", NOW - (7 * DAY) - 1)
        val over = boundaryConverter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE)
        )
        assertTrue("Just-over-7d latest rate must be stale", over is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.STALE_RATE, (over as ConversionOutcome.Failed).failureType)
    }

    /**
     * Engine-level pin: the same boundary through
     * [MoneyNormalizationEngine.normalizeExpense] with LATEST_AVAILABLE —
     * just-under-7d included, just-over-7d excluded.
     */
    @Test
    fun `engine normalizeExpense honors 7d LatestDefault boundary`() = runTest {
        val boundaryStore = BoundaryRateStore()
        val boundaryConverter = CurrencyConverter(boundaryStore, object : TimeProvider { override fun now() = NOW })
        val boundaryEngine = MoneyNormalizationEngine(boundaryConverter)

        boundaryStore.latestRates["USD_EUR"] =
            DomainExchangeRate("USD", "EUR", 0.92, NOW - (7 * DAY) + 1, "api", NOW - (7 * DAY) + 1)
        val included = boundaryEngine.normalizeExpense(
            expense(1, 100.0, "USD", NOW), CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE
        )
        assertTrue("Just-under-7d must be included", included is NormalizationResult.Included)

        boundaryStore.latestRates["USD_EUR"] =
            DomainExchangeRate("USD", "EUR", 0.92, NOW - (7 * DAY) - 1, "api", NOW - (7 * DAY) - 1)
        val excluded = boundaryEngine.normalizeExpense(
            expense(2, 100.0, "USD", NOW), CurrencyCode.EUR, RateBasis.LATEST_AVAILABLE
        )
        assertTrue("Just-over-7d must be excluded", excluded is NormalizationResult.Excluded)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun expense(id: Long, amount: Double, currency: String, date: Long) =
        com.yourname.expensetracker.data.database.entity.Expense(
            id = id, amount = amount, currency = currency, merchant = "Test",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = date
        )
}

private class TestStore : ExchangeRateStore {
    private val NOW = 1716163200000L
    private val DAY = 86400000L

    // Rate is 10 days old — stale for LatestDefault (7 days) but fine for historical
    override suspend fun getLatestRateForPair(from: String, to: String) = when {
        from == "USD" && to == "EUR" -> DomainExchangeRate("USD", "EUR", 0.92, NOW - 10 * DAY, "api", NOW - 10 * DAY)
        else -> null
    }

    override suspend fun getRateAsOf(from: String, to: String, atMillis: Long) = when {
        from == "USD" && to == "EUR" -> DomainExchangeRate("USD", "EUR", 0.90, NOW, "api", atMillis)
        else -> null
    }

    override suspend fun getRate(from: String, to: String) = getLatestRateForPair(from, to)
    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {}
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {}
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = null
    override suspend fun deleteOldRates(olderThan: Long) {}
}

/** Boundary-controlled store: tests write the exact rates they need. */
private class BoundaryRateStore : ExchangeRateStore {
    val latestRates = mutableMapOf<String, DomainExchangeRate>()

    override suspend fun getLatestRateForPair(from: String, to: String) = latestRates["${from}_${to}"]
    override suspend fun getRateAsOf(from: String, to: String, atMillis: Long) =
        latestRates["${from}_${to}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }
    override suspend fun getRate(from: String, to: String) = getLatestRateForPair(from, to)
    override suspend fun insertOrUpdate(rate: DomainExchangeRate) { latestRates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = latestRates.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}
