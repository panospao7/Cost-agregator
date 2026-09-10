package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.*
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR 1 — Conversion semantics hardening tests.
 * Covers CURR-70F-01, CURR-70F-02, CURR-70F-03, CURR-70F-04.
 */
class ConversionSemanticsHardeningTest {

    private lateinit var store: FakeStore
    private lateinit var time: FakeTime
    private lateinit var converter: CurrencyConverter

    private val NOW = 1716163200000L // 2024-05-20 00:00 UTC
    private val DAY = 86400000L

    // Relaxed barrier so adapter write methods pass through in NORMAL mode.
    private val writeBarrier =
        io.mockk.mockk<com.yourname.expensetracker.data.backup.DatabaseWriteBarrier>(relaxed = true)

    @Before
    fun setup() {
        store = FakeStore()
        time = FakeTime(NOW)
        converter = CurrencyConverter(store, time)
    }

    // ── CURR-70F-01: PERIOD_MIDPOINT_ESTIMATE uses historical lookup ───

    @Test
    fun `convertOutcome PERIOD_MIDPOINT_ESTIMATE uses as-of rate`() = runTest {
        val midpoint = NOW - 15 * DAY
        store.asOfRates["USD_EUR_$midpoint"] = rate("USD", "EUR", 0.88, validDate = midpoint)
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 1.50, validDate = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.PERIOD_MIDPOINT_ESTIMATE, atMillis = midpoint,
            stalePolicy = StaleRatePolicy.None
        )

        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        assertEquals(0.88, c.rateUsed, 0.001)
    }

    @Test
    fun `convertOutcome PERIOD_MIDPOINT_ESTIMATE does not use latest rate`() = runTest {
        val midpoint = NOW - 15 * DAY
        // Only latest rate exists, no as-of rate
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 1.50, validDate = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.PERIOD_MIDPOINT_ESTIMATE, atMillis = midpoint,
            stalePolicy = StaleRatePolicy.None
        )

        // Should fail because no historical rate exists — must NOT fall back to latest
        assertTrue(outcome is ConversionOutcome.Failed)
        assertEquals(
            ConversionFailureType.MISSING_HISTORICAL_RATE,
            (outcome as ConversionOutcome.Failed).failureType
        )
    }

    @Test
    fun `convertOutcome PERIOD_MIDPOINT_ESTIMATE without date fails`() = runTest {
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.92, validDate = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR",
            RateBasis.PERIOD_MIDPOINT_ESTIMATE, atMillis = null
        )

        assertTrue(outcome is ConversionOutcome.Failed)
        assertEquals(
            ConversionFailureType.MISSING_HISTORICAL_RATE,
            (outcome as ConversionOutcome.Failed).failureType
        )
    }

    // ── CURR-70F-02: StaleRatePolicy.compareAgainst honored ────────────

    @Test
    fun `stalePolicy NOW compares now vs rate validDate`() = runTest {
        // Rate valid 2 days ago — age from NOW = 2 days
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - 2 * DAY, lastUpdated = NOW - 2 * DAY)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.NOW)
        )

        assertTrue("Should be stale", outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.STALE_RATE, (outcome as ConversionOutcome.Failed).failureType)
    }

    @Test
    fun `stalePolicy NOW fresh rate passes`() = runTest {
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.NOW)
        )

        assertTrue(outcome is ConversionOutcome.Converted)
    }

    @Test
    fun `stalePolicy TRANSACTION_DATE compares atMillis vs rate validDate`() = runTest {
        val txDate = NOW - 5 * DAY
        // Rate valid 5 days ago — same as txDate, so age = 0
        store.asOfRates["USD_EUR_$txDate"] = rate("USD", "EUR", 0.88, validDate = txDate, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, atMillis = txDate,
            stalePolicy = StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.TRANSACTION_DATE)
        )

        assertTrue("Rate valid on same day as tx should be fresh", outcome is ConversionOutcome.Converted)
    }

    @Test
    fun `stalePolicy TRANSACTION_DATE stale when rate far from tx date`() = runTest {
        val txDate = NOW - 5 * DAY
        // Rate valid 10 days ago — 5 days before txDate
        store.asOfRates["USD_EUR_$txDate"] = rate("USD", "EUR", 0.88, validDate = txDate - 5 * DAY, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, atMillis = txDate,
            stalePolicy = StaleRatePolicy(maxAgeMs = 2 * DAY, compareAgainst = StaleRateReference.TRANSACTION_DATE)
        )

        assertTrue("Rate 5 days from tx should be stale with 2-day max", outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.STALE_RATE, (outcome as ConversionOutcome.Failed).failureType)
    }

    @Test
    fun `stalePolicy RATE_VALID_DATE compares lastUpdated vs validDate`() = runTest {
        // Rate valid 30 days ago, inserted today — age = |NOW - (NOW-30d)| = 30 days
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - 30 * DAY, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy(maxAgeMs = 7 * DAY, compareAgainst = StaleRateReference.RATE_VALID_DATE)
        )

        assertTrue("30-day gap between lastUpdated and validDate should be stale", outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.STALE_RATE, (outcome as ConversionOutcome.Failed).failureType)
    }

    @Test
    fun `stalePolicy missing reference does not mark fresh`() = runTest {
        // TRANSACTION_DATE policy but no atMillis for latest basis — age cannot be computed
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE, atMillis = null,
            stalePolicy = StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.TRANSACTION_DATE)
        )

        // Cannot compute age → must not silently pass as fresh
        assertTrue(outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.STALE_RATE, (outcome as ConversionOutcome.Failed).failureType)
    }

    // ── CURR-70F-03: Composite EUR-bridge weakest-leg provenance ───────

    @Test
    fun `composite rate uses oldest validDate for staleness`() = runTest {
        val oldDate = NOW - 10 * DAY
        val freshDate = NOW - 1 * DAY
        // USD->EUR leg: old validDate
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = oldDate, lastUpdated = NOW)
        // EUR->GBP leg: fresh validDate
        store.latestRates["EUR_GBP"] = rate("EUR", "GBP", 0.85, validDate = freshDate, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )

        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        assertEquals(ConversionPath.VIA_BASE_CURRENCY, c.conversionPath)
        // validDate should be the oldest (weakest) leg
        assertEquals(oldDate, c.rateValidDate)
    }

    @Test
    fun `composite rate uses weakest lastUpdated for freshness`() = runTest {
        val oldUpdate = NOW - 5 * DAY
        val freshUpdate = NOW
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = oldUpdate)
        store.latestRates["EUR_GBP"] = rate("EUR", "GBP", 0.85, validDate = NOW, lastUpdated = freshUpdate)

        val outcome = converter.convertOutcome(
            100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )

        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        assertEquals(oldUpdate, c.rateLastUpdated)
    }

    @Test
    fun `composite rate source mentions both legs`() = runTest {
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW, source = "ecb")
        store.latestRates["EUR_GBP"] = rate("EUR", "GBP", 0.85, validDate = NOW, lastUpdated = NOW, source = "boe")

        val outcome = converter.convertOutcome(
            100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )

        assertTrue(outcome is ConversionOutcome.Converted)
        val c = outcome as ConversionOutcome.Converted
        assertTrue("Source should contain both legs", c.rateSource?.contains("+") == true)
        assertTrue(c.rateSource!!.contains("ecb"))
        assertTrue(c.rateSource!!.contains("boe"))
    }

    @Test
    fun `composite rate records VIA_BASE_CURRENCY path`() = runTest {
        store.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW)
        store.latestRates["EUR_GBP"] = rate("EUR", "GBP", 0.85, validDate = NOW, lastUpdated = NOW)

        val outcome = converter.convertOutcome(
            100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )

        assertTrue(outcome is ConversionOutcome.Converted)
        assertEquals(ConversionPath.VIA_BASE_CURRENCY, (outcome as ConversionOutcome.Converted).conversionPath)
    }

    // ── CURR-70F-04: Storage boundary rejects validDate=0/null ─────────

    @Test(expected = IllegalArgumentException::class)
    fun `storeAdapter rejects null validDate`() = runTest {
        // Attempt to store a rate with null validDate through the adapter
        val adapter = com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter(FakeDao(), writeBarrier)
        adapter.insertOrUpdate(DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "test", validDate = null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `storeAdapter rejects zero validDate`() = runTest {
        val adapter = com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter(FakeDao(), writeBarrier)
        adapter.insertOrUpdate(DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "test", validDate = 0L))
    }

    @Test
    fun `storeAdapter accepts valid validDate`() = runTest {
        val dao = FakeDao()
        val adapter = com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter(dao, writeBarrier)
        adapter.insertOrUpdate(DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "test", validDate = NOW))
        assertEquals(1, dao.inserted.size)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun rate(
        from: String, to: String, rate: Double,
        validDate: Long? = null, lastUpdated: Long = NOW, source: String = "api"
    ) = DomainExchangeRate(from, to, rate, lastUpdated, source, validDate)
}

// ── Test doubles ───────────────────────────────────────────────────────

private class FakeTime(private val now: Long) : TimeProvider {
    override fun now(): Long = now
}

private class FakeStore : ExchangeRateStore {
    val latestRates = mutableMapOf<String, DomainExchangeRate>()
    val asOfRates = mutableMapOf<String, DomainExchangeRate>()

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        latestRates["${fromCurrency}_${toCurrency}"]

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        latestRates["${fromCurrency}_${toCurrency}"]

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? =
        asOfRates["${fromCurrency}_${toCurrency}_$atMillis"]
            ?: latestRates["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        latestRates["${rate.fromCurrency}_${rate.toCurrency}"] = rate
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        rates.forEach { insertOrUpdate(it) }
    }

    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
    override suspend fun getLatestRate(): DomainExchangeRate? = latestRates.values.maxByOrNull { it.lastUpdated }
    override suspend fun deleteOldRates(olderThan: Long) {}
}

private class FakeDao : com.yourname.expensetracker.data.database.dao.ExchangeRateDao {
    val inserted = mutableListOf<com.yourname.expensetracker.data.database.entity.ExchangeRate>()

    override suspend fun insertOrUpdate(rate: com.yourname.expensetracker.data.database.entity.ExchangeRate): Long {
        inserted.add(rate); return inserted.size.toLong()
    }

    override suspend fun insertOrUpdateAll(rates: List<com.yourname.expensetracker.data.database.entity.ExchangeRate>) {
        inserted.addAll(rates)
    }

    override suspend fun getRate(fromCurrency: String, toCurrency: String) = null
    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String) = null
    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, validDate: Long) = null
    override fun getRatesToCurrency(targetCurrency: String): Flow<List<com.yourname.expensetracker.data.database.entity.ExchangeRate>> = flowOf(emptyList())
    override fun getRateFlow(fromCurrency: String, toCurrency: String): Flow<com.yourname.expensetracker.data.database.entity.ExchangeRate?> = flowOf(null)
    override suspend fun getLatestRate() = null
    override suspend fun deleteOldRates(olderThan: Long) {}
    override suspend fun getRateCount(): Int = inserted.size
    override suspend fun deleteAllRates() { inserted.clear() }
}
