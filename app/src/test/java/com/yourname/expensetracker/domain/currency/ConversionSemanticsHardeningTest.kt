package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.*
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale

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

    // ── NEW-P5-012: legacy convert() path pinned to StaleRatePolicy.Default ──

    /**
     * Direct-rate 24h boundary via [CurrencyConverter.convert] (String API):
     * just-under passes, just-over falls through to unavailable (null — the
     * composite legs are absent in this fixture).
     */
    @Test
    fun `legacy convert direct rate 24h boundary just under passes just over fails`() = runTest {
        val rate = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW - StaleRatePolicy.Default.maxAgeMs!!)

        // JUST UNDER: age = maxAge - 1ms → fresh
        store.latestRates["USD_EUR"] = rate.copy(lastUpdated = NOW - StaleRatePolicy.Default.maxAgeMs!! + 1)
        val fresh = converter.convert(100.0, "USD", "EUR")
        assertTrue("Just-under-24h direct rate must convert", fresh is ConversionResult)
        assertEquals(90.0, fresh!!.convertedAmount, 0.001)

        // JUST OVER: age = maxAge + 1ms → stale → direct leg unavailable
        store.latestRates["USD_EUR"] = rate.copy(lastUpdated = NOW - StaleRatePolicy.Default.maxAgeMs!! - 1)
        val stale = converter.convert(100.0, "USD", "EUR")
        assertNull(
            "Just-over-24h direct rate must be treated as unavailable (null)",
            stale
        )
    }

    /**
     * Via-EUR composite 24h boundary: BOTH legs must be within 24h. A composite
     * with one leg just-over fails even though the other leg is fresh.
     */
    @Test
    fun `legacy convert via EUR composite fails when either leg exceeds 24h`() = runTest {
        // Fresh composite: both legs well within 24h → converts
        store.latestRates["GBP_EUR"] = rate("GBP", "EUR", 1.17, validDate = NOW, lastUpdated = NOW - 1)
        store.latestRates["EUR_JPY"] = rate("EUR", "JPY", 160.0, validDate = NOW, lastUpdated = NOW - 1)
        val ok = converter.convert(100.0, "GBP", "JPY")
        assertTrue("Both legs fresh → composite converts", ok is ConversionResult)
        assertEquals(100.0 * 1.17 * 160.0, ok!!.convertedAmount, 0.001)

        // ONE leg just-over 24h → composite unavailable
        store.latestRates["EUR_JPY"] = rate("EUR", "JPY", 160.0, validDate = NOW, lastUpdated = NOW - StaleRatePolicy.Default.maxAgeMs!! - 1)
        val staleLeg = converter.convert(100.0, "GBP", "JPY")
        assertNull("One stale composite leg must fail the whole composite", staleLeg)

        // BOTH legs just-over 24h → still unavailable
        store.latestRates["GBP_EUR"] = rate("GBP", "EUR", 1.17, validDate = NOW, lastUpdated = NOW - StaleRatePolicy.Default.maxAgeMs!! - 1)
        val bothStale = converter.convert(100.0, "GBP", "JPY")
        assertNull("Both legs stale must also fail", bothStale)
    }

    /**
     * Policy-source pin: the legacy threshold must BE
     * [StaleRatePolicy.Default.maxAgeMs] — a hypothetical policy change would
     * move this boundary (the boundary tests above derive expectations from the
     * policy constant, so they cannot silently diverge from it).
     */
    @Test
    fun `legacy convert boundary is derived from StaleRatePolicy Default`() {
        assertEquals(
            "Legacy 24h threshold must equal StaleRatePolicy.Default.maxAgeMs",
            24L * 60L * 60L * 1000L,
            StaleRatePolicy.Default.maxAgeMs
        )
    }

    /**
     * Default-parameter pin: [CurrencyConverter.convertOutcome] without an
     * explicit stalePolicy behaves as [StaleRatePolicy.Default] (24h) — a
     * 25-hour-old rate is STALE_RATE under the default, while it would still
     * pass under LatestDefault (7d). This proves the default did not silently
     * drift to the latest-basis policy.
     */
    @Test
    fun `convertOutcome default stalePolicy is StaleRatePolicy Default 24h`() = runTest {
        // Rate aged 24h + 1ms (age via validDate, compareAgainst NOW)
        store.latestRates["USD_EUR"] = rate(
            "USD", "EUR", 0.90,
            validDate = NOW - StaleRatePolicy.Default.maxAgeMs!! - 1,
            lastUpdated = NOW
        )

        // No stalePolicy argument → exercises the default parameter
        val outcome = converter.convertOutcome(
            100.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE
        )

        assertTrue("25h-old rate must be stale under the 24h default policy", outcome is ConversionOutcome.Failed)
        assertEquals(ConversionFailureType.STALE_RATE, (outcome as ConversionOutcome.Failed).failureType)
    }

    // ── NEW-P5-012: convertAsOf is TTL-EXEMPT (transaction-date basis) ──

    /**
     * Pin: [CurrencyConverter.convertAsOf] must NOT apply latest-rate TTL
     * rules. A rate whose lastUpdated is months old but whose validDate covers
     * the requested transaction date converts successfully — the same age
     * fails the 24h policy on the legacy [CurrencyConverter.convert] path.
     */
    @Test
    fun `convertAsOf is exempt from latest-rate TTL old-but-valid rate converts`() = runTest {
        val txDate = NOW - 90 * DAY
        // Historical rate valid ON the tx date; lastUpdated equally old.
        // Age vs NOW is 90 days — would be stale under any latest-rate TTL.
        store.asOfRates["USD_EUR_$txDate"] =
            rate("USD", "EUR", 0.85, validDate = txDate, lastUpdated = txDate)

        val result = converter.convertAsOf(100.0, "USD", "EUR", txDate)

        assertTrue("Historical rate valid on tx date must convert", result is ConversionResult)
        assertEquals(85.0, result!!.convertedAmount, 0.001)
        assertEquals(txDate, result.timestamp)
    }

    /**
     * Contrast pin: the SAME aged rate through the legacy latest path is
     * refused (24h TTL) — proving the exemption is path-specific, not a
     * global TTL relaxation.
     */
    @Test
    fun `same aged rate via legacy latest path is refused while asOf converts`() = runTest {
        val txDate = NOW - 90 * DAY
        val oldRate = rate("USD", "EUR", 0.85, validDate = txDate, lastUpdated = txDate)
        store.asOfRates["USD_EUR_$txDate"] = oldRate
        store.latestRates["USD_EUR"] = oldRate

        val asOf = converter.convertAsOf(100.0, "USD", "EUR", txDate)
        assertTrue("as-of path: transaction-date basis, TTL-exempt", asOf is ConversionResult)

        val legacy = converter.convert(100.0, "USD", "EUR")
        assertNull("latest path: 90-day-old rate must fail the 24h policy", legacy)
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

    @Test
    fun `storeRates explicit publication date is used for historical lookup`() = runTest {
        val publicationDate = NOW - DAY

        converter.storeRates(
            rates = listOf(Triple("USD", "EUR", 0.90)),
            source = "ecb",
            validDate = publicationDate
        )

        val stored = store.datedRates["USD_EUR_$publicationDate"]
        assertNotNull(stored)
        assertEquals(publicationDate, stored!!.validDate)
        assertEquals(NOW, stored.lastUpdated)
        assertEquals("ecb", stored.source)
        assertNotNull(converter.convertAsOf(100.0, "USD", "EUR", publicationDate))
        assertNull(converter.convertAsOf(100.0, "USD", "EUR", publicationDate - 1L))
    }

    @Test
    fun `storeRates repeat publication upserts the same pair date`() = runTest {
        val publicationDate = NOW - DAY
        val rates = listOf(Triple("USD", "EUR", 0.90))
        converter.storeRates(rates, source = "ecb", validDate = publicationDate)

        time.current = NOW + DAY
        converter.storeRates(rates, source = "ecb", validDate = publicationDate)

        assertEquals(1, store.datedRates.size)
        assertEquals(NOW + DAY, store.datedRates.getValue("USD_EUR_$publicationDate").lastUpdated)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    @Test
    fun `priority offered currencies are accepted by typed and legacy conversion`() = runTest {
        listOf("CNY", "NZD", "INR").forEachIndexed { index, code ->
            store.latestRates["${code}_EUR"] = rate(
                code,
                "EUR",
                rate = 0.5 + index * 0.1,
                validDate = NOW,
                lastUpdated = NOW
            )

            val typed = converter.convertOutcome(
                100.0,
                code,
                "EUR",
                RateBasis.LATEST_AVAILABLE,
                stalePolicy = StaleRatePolicy.None
            )

            assertTrue("Expected typed conversion for $code", typed is ConversionOutcome.Converted)
            assertNotNull("Expected legacy conversion for $code", converter.convert(100.0, code, "EUR"))
        }
    }

    @Test
    fun `unknown blank and malformed codes fail before identity`() = runTest {
        val cases = listOf(
            Triple("", "EUR", ConversionFailureType.INVALID_SOURCE_CURRENCY),
            Triple("US D", "EUR", ConversionFailureType.INVALID_SOURCE_CURRENCY),
            Triple("ZZZ", "ZZZ", ConversionFailureType.INVALID_SOURCE_CURRENCY),
            Triple("USD", "", ConversionFailureType.INVALID_TARGET_CURRENCY),
            Triple("USD", "EU R", ConversionFailureType.INVALID_TARGET_CURRENCY),
            Triple("USD", "ZZZ", ConversionFailureType.INVALID_TARGET_CURRENCY)
        )

        cases.forEach { (from, to, expectedFailure) ->
            val typed = converter.convertOutcome(
                10.0,
                from,
                to,
                RateBasis.LATEST_AVAILABLE,
                stalePolicy = StaleRatePolicy.None
            )
            assertTrue(typed is ConversionOutcome.Failed)
            assertEquals(expectedFailure, (typed as ConversionOutcome.Failed).failureType)
            assertEquals(expectedFailure.name, typed.message)
            assertNull(converter.convert(10.0, from, to))
        }
    }

    @Test
    fun `lowercase normalization is locale independent`() = runTest {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            store.latestRates["INR_EUR"] = rate("INR", "EUR", 0.011, validDate = NOW, lastUpdated = NOW)

            val typed = converter.convertOutcome(
                100.0,
                "inr",
                "eur",
                RateBasis.LATEST_AVAILABLE,
                stalePolicy = StaleRatePolicy.None
            )

            assertTrue(typed is ConversionOutcome.Converted)
            assertNotNull(converter.convert(100.0, "inr", "eur"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `inactive HRK remains recognized for historical identity values`() = runTest {
        val typed = converter.convertOutcome(
            25.0,
            "hrk",
            "HRK",
            RateBasis.LATEST_AVAILABLE,
            stalePolicy = StaleRatePolicy.None
        )
        val legacy = converter.convert(25.0, "hrk", "HRK")

        assertTrue(typed is ConversionOutcome.Converted)
        assertEquals(25.0, (typed as ConversionOutcome.Converted).convertedAmount, 0.0)
        assertNotNull(legacy)
        assertEquals("HRK", legacy!!.originalCurrency)
        assertEquals("HRK", legacy.targetCurrency)
    }

    @Test
    fun `reverse display quote uses captured forward quote without lookup`() {
        val forward = ConversionOutcome.Converted(
            originalAmount = 100.0,
            originalCurrency = CurrencyCode("EUR"),
            convertedAmount = 110.0,
            targetCurrency = CurrencyCode("USD"),
            rateUsed = 1.1,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = NOW,
            rateLastUpdated = NOW,
            rateSource = "ecb",
            conversionPath = ConversionPath.DIRECT
        )

        val reversed = converter.reverseDisplayQuote(55.0, forward)

        assertTrue(reversed is ConversionOutcome.Converted)
        reversed as ConversionOutcome.Converted
        assertEquals(50.0, reversed.convertedAmount, 0.000001)
        assertEquals(1.0 / 1.1, reversed.rateUsed, 0.000001)
        assertEquals(CurrencyCode("USD"), reversed.originalCurrency)
        assertEquals(CurrencyCode("EUR"), reversed.targetCurrency)
        assertTrue(store.latestRates.isEmpty())
    }

    @Test
    fun `reverse display quote rejects invalid quote amount and output`() {
        val forward = ConversionOutcome.Converted(
            originalAmount = 1.0,
            originalCurrency = CurrencyCode("EUR"),
            convertedAmount = 1.0,
            targetCurrency = CurrencyCode("USD"),
            rateUsed = 1.0,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = NOW,
            rateLastUpdated = NOW,
            rateSource = "ecb",
            conversionPath = ConversionPath.DIRECT
        )

        val invalidAmount = converter.reverseDisplayQuote(Double.NaN, forward)
        val invalidQuote = converter.reverseDisplayQuote(1.0, forward.copy(rateUsed = 0.0))
        val invalidOutput = converter.reverseDisplayQuote(Double.MAX_VALUE, forward.copy(rateUsed = Double.MIN_VALUE))

        assertEquals("INVALID_REVERSE_AMOUNT", (invalidAmount as ConversionOutcome.Failed).message)
        assertEquals("INVALID_REVERSE_QUOTE", (invalidQuote as ConversionOutcome.Failed).message)
        assertEquals("INVALID_REVERSE_OUTPUT", (invalidOutput as ConversionOutcome.Failed).message)
    }

    private fun rate(
        from: String, to: String, rate: Double,
        validDate: Long? = null, lastUpdated: Long = NOW, source: String = "api"
    ) = DomainExchangeRate(from, to, rate, lastUpdated, source, validDate)
}

// ── Test doubles ───────────────────────────────────────────────────────

private class FakeTime(var current: Long) : TimeProvider {
    override fun now(): Long = current
}

private class FakeStore : ExchangeRateStore {
    val latestRates = mutableMapOf<String, DomainExchangeRate>()
    val asOfRates = mutableMapOf<String, DomainExchangeRate>()
    val datedRates = mutableMapOf<String, DomainExchangeRate>()

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        latestRates["${fromCurrency}_${toCurrency}"]

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? =
        latestRates["${fromCurrency}_${toCurrency}"]

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? =
        asOfRates["${fromCurrency}_${toCurrency}_$atMillis"]
            ?: datedRates.values
                .filter {
                    it.fromCurrency == fromCurrency &&
                        it.toCurrency == toCurrency &&
                        (it.validDate ?: Long.MAX_VALUE) <= atMillis
                }
                .maxByOrNull { it.validDate ?: Long.MIN_VALUE }
            ?: latestRates["${fromCurrency}_${toCurrency}"]?.takeIf { (it.validDate ?: 0L) <= atMillis }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        latestRates["${rate.fromCurrency}_${rate.toCurrency}"] = rate
        rate.validDate?.let { validDate ->
            datedRates["${rate.fromCurrency}_${rate.toCurrency}_$validDate"] = rate
        }
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
