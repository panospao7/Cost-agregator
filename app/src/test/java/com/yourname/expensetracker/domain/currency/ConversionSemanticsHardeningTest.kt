package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.domain.core.money.*
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * PR 1 — Conversion semantics hardening tests.
 * Covers CURR-70F-01, CURR-70F-02, CURR-70F-03, CURR-70F-04.
 *
 * Consolidated in B6-6 into row tables (fresh store/converter per row).
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

    // ── CURR-70F-01 / CURR-70F-02: historical lookup and StaleRatePolicy.compareAgainst ──

    @Test
    fun `convertOutcome historical lookup and stale policy rows`() = runTest {
        val midpoint = NOW - 15 * DAY
        val txDate = NOW - 5 * DAY
        val rows = listOf(
            // CURR-70F-01: PERIOD_MIDPOINT_ESTIMATE uses the as-of rate, never the latest.
            StaleRow(
                "midpoint uses as-of rate", RateBasis.PERIOD_MIDPOINT_ESTIMATE, midpoint, StaleRatePolicy.None,
                setup = {
                    asOfRates["USD_EUR_$midpoint"] = rate("USD", "EUR", 0.88, validDate = midpoint)
                    latestRates["USD_EUR"] = rate("USD", "EUR", 1.50, validDate = NOW)
                },
                expectedFailure = null, expectedRate = 0.88, expectedConverted = 88.0
            ),
            StaleRow(
                "midpoint does not fall back to latest rate", RateBasis.PERIOD_MIDPOINT_ESTIMATE, midpoint, StaleRatePolicy.None,
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 1.50, validDate = NOW) },
                expectedFailure = ConversionFailureType.MISSING_HISTORICAL_RATE
            ),
            StaleRow(
                "midpoint without date fails", RateBasis.PERIOD_MIDPOINT_ESTIMATE, null, null,
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.92, validDate = NOW) },
                expectedFailure = ConversionFailureType.MISSING_HISTORICAL_RATE
            ),
            // CURR-70F-02: compareAgainst = NOW (age from validDate).
            StaleRow(
                "NOW policy stale rate (2 days vs 1 day max)", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.NOW),
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - 2 * DAY, lastUpdated = NOW - 2 * DAY) },
                expectedFailure = ConversionFailureType.STALE_RATE
            ),
            StaleRow(
                "NOW policy fresh rate passes", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.NOW),
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW) },
                expectedFailure = null, expectedRate = 0.90, expectedConverted = 90.0
            ),
            StaleRow(
                "NOW policy age exactly at max is fresh", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.NOW),
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - DAY, lastUpdated = NOW) },
                expectedFailure = null, expectedRate = 0.90, expectedConverted = 90.0
            ),
            StaleRow(
                "NOW policy age one ms over max is stale", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.NOW),
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - DAY - 1, lastUpdated = NOW) },
                expectedFailure = ConversionFailureType.STALE_RATE
            ),
            // compareAgainst = TRANSACTION_DATE (atMillis vs rate validDate).
            StaleRow(
                "TRANSACTION_DATE policy rate valid on tx day is fresh", RateBasis.TRANSACTION_DATE, txDate,
                StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.TRANSACTION_DATE),
                setup = { asOfRates["USD_EUR_$txDate"] = rate("USD", "EUR", 0.88, validDate = txDate, lastUpdated = NOW) },
                expectedFailure = null, expectedRate = 0.88, expectedConverted = 88.0
            ),
            StaleRow(
                "TRANSACTION_DATE policy rate 5 days from tx is stale with 2-day max", RateBasis.TRANSACTION_DATE, txDate,
                StaleRatePolicy(maxAgeMs = 2 * DAY, compareAgainst = StaleRateReference.TRANSACTION_DATE),
                setup = { asOfRates["USD_EUR_$txDate"] = rate("USD", "EUR", 0.88, validDate = txDate - 5 * DAY, lastUpdated = NOW) },
                expectedFailure = ConversionFailureType.STALE_RATE
            ),
            // compareAgainst = RATE_VALID_DATE (lastUpdated vs validDate).
            StaleRow(
                "RATE_VALID_DATE policy 30-day gap is stale with 7-day max", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = 7 * DAY, compareAgainst = StaleRateReference.RATE_VALID_DATE),
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - 30 * DAY, lastUpdated = NOW) },
                expectedFailure = ConversionFailureType.STALE_RATE
            ),
            StaleRow(
                "RATE_VALID_DATE policy 1-day gap is fresh with 7-day max", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = 7 * DAY, compareAgainst = StaleRateReference.RATE_VALID_DATE),
                // Both timestamps are far from NOW so a NOW-based reference would be stale (30 days).
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - 30 * DAY, lastUpdated = NOW - 29 * DAY) },
                expectedFailure = null, expectedRate = 0.90, expectedConverted = 90.0
            ),
            // Age cannot be computed: must not be treated as fresh.
            StaleRow(
                "missing reference (TRANSACTION_DATE policy, no atMillis) does not mark fresh", RateBasis.LATEST_AVAILABLE, null,
                StaleRatePolicy(maxAgeMs = DAY, compareAgainst = StaleRateReference.TRANSACTION_DATE),
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = NOW) },
                expectedFailure = ConversionFailureType.STALE_RATE
            ),
            // NEW-P5-012: omitted stalePolicy == StaleRatePolicy.Default (24h), not LatestDefault (7d).
            StaleRow(
                "default policy 24h plus 1ms is stale", RateBasis.LATEST_AVAILABLE, null, null,
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - DAY - 1, lastUpdated = NOW) },
                expectedFailure = ConversionFailureType.STALE_RATE
            ),
            StaleRow(
                "default policy 24h minus 1ms is fresh", RateBasis.LATEST_AVAILABLE, null, null,
                setup = { latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW - DAY + 1, lastUpdated = NOW) },
                expectedFailure = null, expectedRate = 0.90, expectedConverted = 90.0
            )
        )
        assertEquals("row count", 14, rows.size)

        rows.forEach { row ->
            val rowStore = FakeStore().also(row.setup)
            val rowConverter = CurrencyConverter(rowStore, FakeTime(NOW))

            val outcome = if (row.policy != null) {
                rowConverter.convertOutcome(100.0, "USD", "EUR", row.basis, atMillis = row.atMillis, stalePolicy = row.policy)
            } else {
                rowConverter.convertOutcome(100.0, "USD", "EUR", row.basis, atMillis = row.atMillis)
            }

            if (row.expectedFailure != null) {
                assertTrue("${row.name}: expected Failed but was $outcome", outcome is ConversionOutcome.Failed)
                assertEquals(row.name, row.expectedFailure, (outcome as ConversionOutcome.Failed).failureType)
            } else {
                assertTrue("${row.name}: expected Converted but was $outcome", outcome is ConversionOutcome.Converted)
                val converted = outcome as ConversionOutcome.Converted
                assertEquals("${row.name}: rate", row.expectedRate!!, converted.rateUsed, 0.0)
                assertMoneyEquals(row.expectedConverted!!, converted.convertedAmount, "${row.name}: converted")
            }
        }
    }

    // ── NEW-P5-012: legacy convert() path pinned to StaleRatePolicy.Default ──

    /**
     * Direct-rate 24h boundary via [CurrencyConverter.convert] (String API):
     * under/at the boundary passes, just-over falls through to unavailable (null —
     * the composite legs are absent in this fixture).
     */
    @Test
    fun `legacy convert direct rate 24h boundary just under passes just over fails`() = runTest {
        val rows = listOf(
            Triple("just under 24h", NOW - DAY + 1, 90.0),
            Triple("exactly 24h", NOW - DAY, 90.0),
            Triple("just over 24h", NOW - DAY - 1, null)
        )
        assertEquals("row count", 3, rows.size)

        rows.forEach { (name, lastUpdated, expectedConverted) ->
            val rowStore = FakeStore()
            rowStore.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, validDate = NOW, lastUpdated = lastUpdated)
            val rowConverter = CurrencyConverter(rowStore, FakeTime(NOW))

            val result = rowConverter.convert(100.0, "USD", "EUR")

            if (expectedConverted == null) {
                assertNull("$name: stale direct rate must be treated as unavailable (null)", result)
            } else {
                assertNotNull("$name: must convert", result)
                assertMoneyEquals(expectedConverted, result!!.convertedAmount, name)
            }
        }
    }

    /**
     * Via-EUR composite 24h boundary: BOTH legs must be within 24h. A composite
     * with one leg just-over fails even though the other leg is fresh.
     */
    @Test
    fun `legacy convert via EUR composite fails when either leg exceeds 24h`() = runTest {
        val fresh = NOW - 1
        val stale = NOW - DAY - 1
        // Rows: name, GBP->EUR lastUpdated, EUR->JPY lastUpdated, expected converted (null = unavailable).
        val rows = listOf(
            Triple("both legs fresh", fresh to fresh, 18_720.0),
            Triple("EUR to JPY leg just over 24h", fresh to stale, null),
            Triple("GBP to EUR leg just over 24h", stale to fresh, null),
            Triple("both legs just over 24h", stale to stale, null)
        )
        assertEquals("row count", 4, rows.size)

        rows.forEach { (name, legs, expectedConverted) ->
            val rowStore = FakeStore()
            rowStore.latestRates["GBP_EUR"] = rate("GBP", "EUR", 1.17, validDate = NOW, lastUpdated = legs.first)
            rowStore.latestRates["EUR_JPY"] = rate("EUR", "JPY", 160.0, validDate = NOW, lastUpdated = legs.second)
            val rowConverter = CurrencyConverter(rowStore, FakeTime(NOW))

            val result = rowConverter.convert(100.0, "GBP", "JPY")

            if (expectedConverted == null) {
                assertNull("$name: composite must be unavailable", result)
            } else {
                assertNotNull("$name: composite must convert", result)
                assertMoneyEquals(expectedConverted, result!!.convertedAmount, name)
            }
        }
    }

    /**
     * Policy-source pin: the legacy threshold must BE
     * [StaleRatePolicy.Default.maxAgeMs] — the boundary rows above hard-code 24h,
     * so this pin fails if the policy constant drifts from 24h.
     */
    @Test
    fun `legacy convert boundary is derived from StaleRatePolicy Default`() {
        assertEquals(
            "Legacy 24h threshold must equal StaleRatePolicy.Default.maxAgeMs",
            24L * 60L * 60L * 1000L,
            StaleRatePolicy.Default.maxAgeMs
        )
    }

    // ── NEW-P5-012: convertAsOf is TTL-EXEMPT (transaction-date basis) ──

    /**
     * Pin: [CurrencyConverter.convertAsOf] must NOT apply latest-rate TTL rules.
     * A rate whose lastUpdated is months old but whose validDate covers the requested
     * transaction date converts successfully, while the SAME aged rate through the
     * legacy latest path is refused (24h TTL) — the exemption is path-specific.
     */
    @Test
    fun `convertAsOf is exempt from latest-rate TTL old-but-valid rate converts while legacy path refuses`() = runTest {
        val txDate = NOW - 90 * DAY
        val oldRate = rate("USD", "EUR", 0.85, validDate = txDate, lastUpdated = txDate)
        store.asOfRates["USD_EUR_$txDate"] = oldRate
        store.latestRates["USD_EUR"] = oldRate

        val asOf = converter.convertAsOf(100.0, "USD", "EUR", txDate)
        assertNotNull("as-of path: transaction-date basis, TTL-exempt", asOf)
        assertMoneyEquals(85.0, asOf!!.convertedAmount, "as-of converted")
        assertEquals(txDate, asOf.timestamp)

        val legacy = converter.convert(100.0, "USD", "EUR")
        assertNull("latest path: 90-day-old rate must fail the 24h policy", legacy)
    }

    // ── CURR-70F-03: Composite EUR-bridge weakest-leg provenance ───────

    @Test
    fun `composite rate provenance takes weakest leg and records VIA_BASE_CURRENCY path`() = runTest {
        // Rows: name, USD->EUR leg, EUR->GBP leg, expected validDate, expected lastUpdated, expected source.
        val rows = listOf(
            ProvenanceRow(
                "oldest validDate wins (first leg old)",
                Leg(NOW - 10 * DAY, NOW, "api"), Leg(NOW - 1 * DAY, NOW, "api"),
                NOW - 10 * DAY, NOW, "api+api"
            ),
            ProvenanceRow(
                "oldest validDate wins (second leg old)",
                Leg(NOW - 1 * DAY, NOW, "api"), Leg(NOW - 10 * DAY, NOW, "api"),
                NOW - 10 * DAY, NOW, "api+api"
            ),
            ProvenanceRow(
                "weakest lastUpdated wins",
                Leg(NOW, NOW - 5 * DAY, "api"), Leg(NOW, NOW, "api"),
                NOW, NOW - 5 * DAY, "api+api"
            ),
            ProvenanceRow(
                "source mentions both legs",
                Leg(NOW, NOW, "ecb"), Leg(NOW, NOW, "boe"),
                NOW, NOW, "ecb+boe"
            )
        )
        assertEquals("row count", 4, rows.size)

        rows.forEach { row ->
            val rowStore = FakeStore()
            rowStore.latestRates["USD_EUR"] = rate("USD", "EUR", 0.90, row.first.validDate, row.first.lastUpdated, row.first.source)
            rowStore.latestRates["EUR_GBP"] = rate("EUR", "GBP", 0.85, row.second.validDate, row.second.lastUpdated, row.second.source)
            val rowConverter = CurrencyConverter(rowStore, FakeTime(NOW))

            val outcome = rowConverter.convertOutcome(
                100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE,
                stalePolicy = StaleRatePolicy.None
            )

            assertTrue("${row.name}: expected Converted but was $outcome", outcome is ConversionOutcome.Converted)
            val c = outcome as ConversionOutcome.Converted
            assertEquals("${row.name}: path", ConversionPath.VIA_BASE_CURRENCY, c.conversionPath)
            assertEquals("${row.name}: validDate", row.expectedValidDate, c.rateValidDate)
            assertEquals("${row.name}: lastUpdated", row.expectedLastUpdated, c.rateLastUpdated)
            assertEquals("${row.name}: source", row.expectedSource, c.rateSource)
            assertMoneyEquals(76.5, c.convertedAmount, "${row.name}: converted")
        }
    }

    // ── CURR-70F-04: Storage boundary rejects validDate=0/null ─────────

    @Test
    fun `storeAdapter accepts only a positive validDate`() = runTest {
        // Rows: name, validDate, accepted.
        val rows = listOf<Triple<String, Long?, Boolean>>(
            Triple("null validDate", null, false),
            Triple("zero validDate", 0L, false),
            Triple("negative validDate", -1L, false),
            Triple("valid validDate", NOW, true)
        )
        assertEquals("row count", 4, rows.size)

        rows.forEach { (name, validDate, accepted) ->
            val dao = FakeDao()
            val adapter = com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter(dao, writeBarrier)
            val candidate = DomainExchangeRate("USD", "EUR", 0.90, lastUpdated = NOW, source = "test", validDate = validDate)

            if (accepted) {
                adapter.insertOrUpdate(candidate)
                assertEquals("$name: inserted count", 1, dao.inserted.size)
            } else {
                try {
                    adapter.insertOrUpdate(candidate)
                    fail("$name: expected IllegalArgumentException")
                } catch (expected: IllegalArgumentException) {
                    assertEquals("$name: nothing inserted on rejection", 0, dao.inserted.size)
                }
            }
        }
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

    // ── Currency-code acceptance ───────────────────────────────────────

    @Test
    fun `priority offered currencies are accepted by typed and legacy conversion`() = runTest {
        // Rows: code, rate to EUR, expected converted amount for 100.0.
        val rows = listOf(
            Triple("CNY", 0.5, 50.0),
            Triple("NZD", 0.6, 60.0),
            Triple("INR", 0.7, 70.0)
        )
        assertEquals("row count", 3, rows.size)

        rows.forEach { (code, rateToEur, expectedConverted) ->
            val rowStore = FakeStore()
            rowStore.latestRates["${code}_EUR"] = rate(code, "EUR", rateToEur, validDate = NOW, lastUpdated = NOW)
            val rowConverter = CurrencyConverter(rowStore, FakeTime(NOW))

            val typed = rowConverter.convertOutcome(
                100.0, code, "EUR", RateBasis.LATEST_AVAILABLE, stalePolicy = StaleRatePolicy.None
            )
            val legacy = rowConverter.convert(100.0, code, "EUR")

            assertTrue("Expected typed conversion for $code", typed is ConversionOutcome.Converted)
            assertMoneyEquals(expectedConverted, (typed as ConversionOutcome.Converted).convertedAmount, "$code typed")
            assertNotNull("Expected legacy conversion for $code", legacy)
            assertMoneyEquals(expectedConverted, legacy!!.convertedAmount, "$code legacy")
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
        assertEquals("row count", 6, cases.size)

        cases.forEach { (from, to, expectedFailure) ->
            val row = "from='$from' to='$to'"
            val typed = converter.convertOutcome(
                10.0,
                from,
                to,
                RateBasis.LATEST_AVAILABLE,
                stalePolicy = StaleRatePolicy.None
            )
            assertTrue("$row: expected Failed but was $typed", typed is ConversionOutcome.Failed)
            assertEquals(row, expectedFailure, (typed as ConversionOutcome.Failed).failureType)
            assertEquals(row, expectedFailure.name, typed.message)
            assertNull("$row: legacy convert must be null", converter.convert(10.0, from, to))
        }
    }

    @Test
    fun `lowercase normalization is locale independent`() = runTest {
        val previousLocale = Locale.getDefault()
        val previousFormat = Locale.getDefault(Locale.Category.FORMAT)
        val previousDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
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
            Locale.setDefault(Locale.Category.FORMAT, previousFormat)
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay)
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
        assertMoneyExact(25.0, (typed as ConversionOutcome.Converted).convertedAmount, "typed identity")
        assertNotNull(legacy)
        assertEquals("HRK", legacy!!.originalCurrency)
        assertEquals("HRK", legacy.targetCurrency)
    }

    // ── Reverse display quote ──────────────────────────────────────────

    @Test
    fun `reverse display quote uses captured forward quote without lookup`() {
        val forward = forwardQuote(rateUsed = 1.1, convertedAmount = 110.0, originalAmount = 100.0)

        val reversed = converter.reverseDisplayQuote(55.0, forward)

        assertTrue(reversed is ConversionOutcome.Converted)
        reversed as ConversionOutcome.Converted
        // Reverse quote is exact division of the captured forward quote, no rounding.
        assertEquals("reversed converted", 55.0 / 1.1, reversed.convertedAmount, 0.0)
        assertEquals("reversed rate", 1.0 / 1.1, reversed.rateUsed, 0.0)
        assertEquals(CurrencyCode("USD"), reversed.originalCurrency)
        assertEquals(CurrencyCode("EUR"), reversed.targetCurrency)
        assertTrue(store.latestRates.isEmpty())
    }

    @Test
    fun `reverse display quote rejects invalid quote amount and output`() {
        val forward = forwardQuote(rateUsed = 1.0, convertedAmount = 1.0, originalAmount = 1.0)
        // Rows: name, reverse amount, forward quote, expected failure message.
        val rows = listOf(
            Triple("NaN amount", Double.NaN to forward, "INVALID_REVERSE_AMOUNT"),
            Triple("zero rate", 1.0 to forward.copy(rateUsed = 0.0), "INVALID_REVERSE_QUOTE"),
            Triple("overflowing output", Double.MAX_VALUE to forward.copy(rateUsed = Double.MIN_VALUE), "INVALID_REVERSE_OUTPUT")
        )
        assertEquals("row count", 3, rows.size)

        rows.forEach { (name, input, expectedMessage) ->
            val outcome = converter.reverseDisplayQuote(input.first, input.second)

            assertTrue("$name: expected Failed but was $outcome", outcome is ConversionOutcome.Failed)
            assertEquals(name, expectedMessage, (outcome as ConversionOutcome.Failed).message)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun forwardQuote(rateUsed: Double, convertedAmount: Double, originalAmount: Double) =
        ConversionOutcome.Converted(
            originalAmount = originalAmount,
            originalCurrency = CurrencyCode("EUR"),
            convertedAmount = convertedAmount,
            targetCurrency = CurrencyCode("USD"),
            rateUsed = rateUsed,
            rateBasis = RateBasis.LATEST_AVAILABLE,
            rateValidDate = NOW,
            rateLastUpdated = NOW,
            rateSource = "ecb",
            conversionPath = ConversionPath.DIRECT
        )

    private fun rate(
        from: String, to: String, rate: Double,
        validDate: Long? = null, lastUpdated: Long = NOW, source: String = "api"
    ) = DomainExchangeRate(from, to, rate, lastUpdated, source, validDate)
}

// ── Row types ──────────────────────────────────────────────────────────

private data class StaleRow(
    val name: String,
    val basis: RateBasis,
    val atMillis: Long?,
    /** null = omit the stalePolicy argument (exercises the default parameter). */
    val policy: StaleRatePolicy?,
    val setup: FakeStore.() -> Unit,
    val expectedFailure: ConversionFailureType?,
    val expectedRate: Double? = null,
    val expectedConverted: Double? = null
)

private data class Leg(val validDate: Long, val lastUpdated: Long, val source: String)

private data class ProvenanceRow(
    val name: String,
    val first: Leg,
    val second: Leg,
    val expectedValidDate: Long,
    val expectedLastUpdated: Long,
    val expectedSource: String
)

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
