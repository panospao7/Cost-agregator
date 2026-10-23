package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * manual-entry-currency A1: [RateEligibilityEvaluator] (INV-1, 2, 3; AE-1..13, 25, 27, 34, 37).
 *
 * Every date is passed with an explicit injected zone; no test relies on the device default zone
 * (one test changes the JVM default to prove the evaluator ignores it).
 */
class RateEligibilityEvaluatorTest {

    private val athens: ZoneId = ZoneId.of("Europe/Athens")
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val london: ZoneId = ZoneId.of("Europe/London")

    private val eur = CurrencyCode("EUR")
    private val usd = CurrencyCode("USD")
    private val gbp = CurrencyCode("GBP")
    private val bgn = CurrencyCode("BGN")
    private val rub = CurrencyCode("RUB")
    private val hrk = CurrencyCode("HRK")

    private lateinit var store: RateStoreFake
    private lateinit var evaluator: RateEligibilityEvaluator

    @Before
    fun setUp() {
        store = RateStoreFake()
        evaluator = RateEligibilityEvaluator(CurrencyConverter(store, FakeTimeProvider(0L)))
    }

    // ---- helpers ----

    private fun oct(day: Int): LocalDate = LocalDate.of(2025, 10, day)

    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun millis(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Stores a rate dated [date] (UTC midnight). `lastUpdated` is deliberately far from `validDate`. */
    private fun put(
        from: String,
        to: String,
        rate: Double,
        date: LocalDate?,
        source: String = "ecb"
    ) {
        store.rows += DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = 4_102_444_800_000L,
            source = source,
            validDate = date?.let { utcMidnight(it) }
        )
    }

    private suspend fun evaluate(
        currency: CurrencyCode,
        home: CurrencyCode,
        expenseAtMillis: Long,
        zone: ZoneId
    ): RateEligibility = evaluator.evaluate(currency, home, expenseAtMillis, zone)

    private fun assertEligible(
        expectedRate: Double,
        expectedDate: LocalDate,
        expectedAge: Int,
        expectedPath: RateEligibilityPath,
        expectedLabel: RateSourceLabel,
        actual: RateEligibility
    ) {
        assertTrue("expected Eligible but was $actual", actual is RateEligibility.Eligible)
        actual as RateEligibility.Eligible
        assertEquals(expectedRate, actual.rate, 0.0)
        assertEquals(expectedDate, actual.rateDate)
        assertEquals(expectedAge, actual.ageDays)
        assertEquals(expectedPath, actual.path)
        assertEquals(expectedLabel, actual.sourceLabel)
    }

    // ---- AE-1 .. AE-5: normal, weekend, none, stale, boundary ----

    @Test
    fun `AE-1 a rate dated the expense day is eligible with age 0 direct and ECB`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        val result = evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        assertEligible(1.0 / 1.08, oct(15), 0, RateEligibilityPath.DIRECT, RateSourceLabel.ECB, result)
    }

    @Test
    fun `AE-2 weekend uses the Friday rate with age 2`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(10))
        val result = evaluate(usd, eur, millis(oct(12), 12, 0, athens), athens)
        assertEligible(1.0 / 1.08, oct(10), 2, RateEligibilityPath.DIRECT, RateSourceLabel.ECB, result)
    }

    @Test
    fun `AE-3 an empty rate table is None`() = runTest {
        assertEquals(RateEligibility.None, evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `AE-4 a rate 8 days older is Stale and a later rate does not rescue an earlier date`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(7))
        assertEquals(
            RateEligibility.Stale(oct(7), 8),
            evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        )
        put("USD", "EUR", 1.0 / 1.09, oct(16))
        assertEquals(
            RateEligibility.Stale(oct(7), 8),
            evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        )
        assertEligible(
            1.0 / 1.09, oct(16), 0, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, millis(oct(16), 12, 0, athens), athens)
        )
    }

    @Test
    fun `AE-5 age 7 is eligible and age 8 is stale`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(8))
        assertEligible(
            1.0 / 1.08, oct(8), 7, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        )
        assertEquals(
            RateEligibility.Stale(oct(8), 8),
            evaluate(usd, eur, millis(oct(16), 12, 0, athens), athens)
        )
    }

    @Test
    fun `eligibility ignores lastUpdated, which is far in the future in these fixtures`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(7))
        // lastUpdated (year 2100) must not make the 8-day-old rate fresh.
        assertTrue(evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens) is RateEligibility.Stale)
    }

    // ---- AE-6 .. AE-8: Greece near midnight, first download ----

    @Test
    fun `AE-6 Athens 01 30 on 16 Oct uses the 16 Oct rate (calendar date, not UTC instant)`() = runTest {
        put("GBP", "EUR", 1.0 / 0.87, oct(16))
        // 16 Oct 01:30 Athens (UTC+3) is 15 Oct 22:30 UTC.
        val result = evaluate(gbp, eur, millis(oct(16), 1, 30, athens), athens)
        assertEligible(1.0 / 0.87, oct(16), 0, RateEligibilityPath.DIRECT, RateSourceLabel.ECB, result)
    }

    @Test
    fun `AE-7 Athens 23 30 on 15 Oct has no 16 Oct rate`() = runTest {
        put("GBP", "EUR", 1.0 / 0.87, oct(16))
        assertEquals(RateEligibility.None, evaluate(gbp, eur, millis(oct(15), 23, 30, athens), athens))
    }

    @Test
    fun `AE-8 first download date gives age 1 for the next day and None for an earlier date`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(14))
        assertEligible(
            1.0 / 1.08, oct(14), 1, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        )
        assertEquals(RateEligibility.None, evaluate(usd, eur, millis(oct(13), 12, 0, athens), athens))
    }

    // ---- AE-9, AE-10: holiday gap, DST ----

    @Test
    fun `AE-9 Easter gap Thursday 2 Apr to Tuesday 7 Apr is age 5`() = runTest {
        val thursday = LocalDate.of(2026, 4, 2)
        val tuesday = LocalDate.of(2026, 4, 7)
        put("USD", "EUR", 1.0 / 1.08, thursday)
        assertEligible(
            1.0 / 1.08, thursday, 5, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, millis(tuesday, 9, 0, athens), athens)
        )
    }

    @Test
    fun `AE-10 DST end between rate and expense counts calendar days, age 7 is eligible`() = runTest {
        // Athens leaves summer time on 26 Oct 2025; 24 Oct 00:00Z to 31 Oct 23:59 local spans more than 7 x 24 h.
        put("USD", "EUR", 1.0 / 1.08, oct(24))
        val expense = millis(oct(31), 23, 59, athens)
        val sevenTimes24h = 7L * 24 * 60 * 60 * 1000
        assertTrue(expense - utcMidnight(oct(24)) > sevenTimes24h)
        assertEligible(
            1.0 / 1.08, oct(24), 7, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, expense, athens)
        )
        assertEquals(
            RateEligibility.Stale(oct(24), 8),
            evaluate(usd, eur, millis(LocalDate.of(2025, 11, 1), 0, 1, athens), athens)
        )
    }

    // ---- AE-11: west of UTC ----

    @Test
    fun `AE-11 America New_York 21 00 on 15 Oct uses the 15 Oct rate and reports 15 Oct`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        put("USD", "EUR", 1.0 / 1.09, oct(16))
        // 15 Oct 21:00 EDT (UTC-4) is 16 Oct 01:00 UTC.
        val result = evaluate(usd, eur, millis(oct(15), 21, 0, newYork), newYork)
        assertEligible(1.0 / 1.08, oct(15), 0, RateEligibilityPath.DIRECT, RateSourceLabel.ECB, result)
    }

    // ---- AE-12: via-EUR older leg ----

    @Test
    fun `AE-12 via EUR uses the older leg and is Stale at age 9`() = runTest {
        put("GBP", "EUR", 1.0 / 0.87, oct(14))
        put("EUR", "USD", 1.08, oct(6))
        val result = evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens)
        assertEquals(RateEligibility.Stale(oct(6), 9), result)
    }

    @Test
    fun `via EUR with both legs recent is eligible with the older leg date and the product rate`() = runTest {
        put("GBP", "EUR", 1.0 / 0.87, oct(14))
        put("EUR", "USD", 1.08, oct(10))
        val result = evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens)
        assertEligible(
            (1.0 / 0.87) * 1.08, oct(10), 5,
            RateEligibilityPath.VIA_BASE_CURRENCY, RateSourceLabel.ECB, result
        )
    }

    @Test
    fun `a direct pair wins over via EUR even if the via EUR legs are fresher`() = runTest {
        put("GBP", "USD", 1.25, oct(9))
        put("GBP", "EUR", 1.0 / 0.87, oct(15))
        put("EUR", "USD", 1.08, oct(15))
        val result = evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens)
        assertEligible(1.25, oct(9), 6, RateEligibilityPath.DIRECT, RateSourceLabel.ECB, result)
    }

    // ---- source label ----

    @Test
    fun `the label is ECB only if every leg source is ecb`() = runTest {
        put("GBP", "EUR", 1.0 / 0.87, oct(14), source = "ecb")
        put("EUR", "USD", 1.08, oct(14), source = "manual")
        val mixed = evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens)
        assertTrue(mixed is RateEligibility.Eligible)
        assertEquals(RateSourceLabel.OTHER, (mixed as RateEligibility.Eligible).sourceLabel)
    }

    @Test
    fun `a non-ecb direct rate is labelled OTHER`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15), source = "manual")
        val result = evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        assertEquals(RateSourceLabel.OTHER, (result as RateEligibility.Eligible).sourceLabel)
    }

    // ---- AE-13: Home never reads the rate table ----

    @Test
    fun `AE-13 the home currency is Home and the rate table is never read`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        assertEquals(RateEligibility.Home, evaluate(eur, eur, millis(oct(15), 12, 0, athens), athens))
        assertEquals(0, store.reads)
    }

    @Test
    fun `Home holds for an inactive or rate-less home currency`() = runTest {
        for (home in listOf(bgn, rub, hrk)) {
            assertEquals(RateEligibility.Home, evaluate(home, home, millis(oct(15), 12, 0, athens), athens))
        }
        assertEquals(0, store.reads)
    }

    // ---- AE-25 / AE-27: unsupported and BGN home ----

    @Test
    fun `AE-25 BGN RUB and HRK are Unsupported NOT_SELECTABLE for a EUR-home entry and read no rates`() = runTest {
        put("BGN", "EUR", 0.51, oct(15))
        for (currency in listOf(bgn, rub, hrk)) {
            assertEquals(
                RateEligibility.Unsupported(RateUnsupportedReason.NOT_SELECTABLE),
                evaluate(currency, eur, millis(oct(15), 12, 0, athens), athens)
            )
        }
        assertEquals(0, store.reads)
    }

    @Test
    fun `a code outside the catalog is Unsupported NOT_IN_CATALOG`() = runTest {
        assertEquals(
            RateEligibility.Unsupported(RateUnsupportedReason.NOT_IN_CATALOG),
            evaluate(CurrencyCode("ZZZ"), eur, millis(oct(15), 12, 0, athens), athens)
        )
        assertEquals(0, store.reads)
    }

    @Test
    fun `AE-27 with a BGN home a EUR expense against only older pairs is Stale`() = runTest {
        val old = LocalDate.of(2025, 12, 30)
        put("EUR", "BGN", 1.95583, old)
        val expenseDate = LocalDate.of(2026, 10, 15)
        val result = evaluate(eur, bgn, millis(expenseDate, 12, 0, athens), athens)
        assertEquals(RateEligibility.Stale(old, ChronoUnit.DAYS.between(old, expenseDate).toInt()), result)
    }

    // ---- fail-closed conversions ----

    @Test
    fun `a rate row without a valid date is None`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, null)
        assertEquals(RateEligibility.None, evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `an invalid stored rate is None`() = runTest {
        put("USD", "EUR", 0.0, oct(15))
        assertEquals(RateEligibility.None, evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `a home currency outside the catalog is None because no rate can exist`() = runTest {
        put("USD", "BHD", 0.38, oct(15))
        assertEquals(
            RateEligibility.None,
            evaluate(usd, CurrencyCode("BHD"), millis(oct(15), 12, 0, athens), athens)
        )
    }

    @Test
    fun `invalid stored rates of every kind are None, including a via EUR product that overflows`() = runTest {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            store.rows.clear()
            put("USD", "EUR", bad, oct(15))
            assertEquals("rate $bad", RateEligibility.None, evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens))
        }
        store.rows.clear()
        put("GBP", "EUR", 1e200, oct(15))
        put("EUR", "USD", 1e200, oct(15))
        assertEquals(RateEligibility.None, evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens))
    }

    // ---- evaluator's own fail-closed branches, reached through a store that breaks the DAO rules ----
    // The real DAO returns only rows with validDate <= bound. These stores deliberately do not, to prove
    // the evaluator does not rely on that rule alone (INV-2: no unusable rate may become Eligible).

    @Test
    fun `a store that returns a row without a valid date from the as-of lookup is None (direct)`() = runTest {
        store.asOfOverride = { from, to, _ ->
            if (from == "USD" && to == "EUR") rowOf("USD", "EUR", 1.0 / 1.08, validDate = null) else null
        }
        assertEquals(RateEligibility.None, evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `a store that returns rows without a valid date for both via EUR legs is None`() = runTest {
        store.asOfOverride = { from, to, _ ->
            when {
                from == "GBP" && to == "EUR" -> rowOf("GBP", "EUR", 1.0 / 0.87, validDate = null)
                from == "EUR" && to == "USD" -> rowOf("EUR", "USD", 1.08, validDate = null)
                else -> null
            }
        }
        assertEquals(RateEligibility.None, evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `a store that returns a row dated after the bound is None, not Eligible with a negative age (direct)`() = runTest {
        store.asOfOverride = { from, to, _ ->
            if (from == "USD" && to == "EUR") rowOf("USD", "EUR", 1.0 / 1.08, validDate = utcMidnight(oct(16))) else null
        }
        assertEquals(RateEligibility.None, evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `a store that returns both via EUR legs dated after the bound is None`() = runTest {
        store.asOfOverride = { from, to, _ ->
            when {
                from == "GBP" && to == "EUR" -> rowOf("GBP", "EUR", 1.0 / 0.87, validDate = utcMidnight(oct(16)))
                from == "EUR" && to == "USD" -> rowOf("EUR", "USD", 1.08, validDate = utcMidnight(oct(17)))
                else -> null
            }
        }
        assertEquals(RateEligibility.None, evaluate(gbp, usd, millis(oct(15), 12, 0, athens), athens))
    }

    @Test
    fun `eligibility is by calendar date so a same-day row later than the UTC midnight bound is age zero`() = runTest {
        // Spec §3: age is in calendar days. A (rule-breaking) row stamped later on the same UTC
        // calendar day as the bound is the same rate date, not a future date: Eligible, ageDays 0.
        // The real DAO never returns it (validDate <= bound); a next-day row is None (tests above).
        store.asOfOverride = { from, to, bound ->
            if (from == "USD" && to == "EUR") rowOf("USD", "EUR", 1.0 / 1.08, validDate = bound + 1) else null
        }
        val result = evaluate(usd, eur, millis(oct(15), 12, 0, athens), athens)
        assertEquals(0, (result as RateEligibility.Eligible).ageDays)
        assertEquals(oct(15), result.rateDate)
    }

    // The converter's IDENTITY path (evaluator branch "ConversionPath.IDENTITY -> None") is unreachable from
    // here: CurrencyCode enforces upper-case 3-letter codes, so the converter's normalised from == to holds
    // exactly when currency == home, and that case returns Home before any conversion. The branch is a
    // defensive guard with no real-converter input that reaches it, so it has no test.
    // Likewise the evaluator's own "rate not finite and positive" guard is unreachable behind the converter,
    // which drops every non-finite or non-positive rate (and a non-finite via EUR product) as unavailable;
    // the test above pins the observable result (None) for those inputs.

    // ---- AE-34: injected zone only ----

    @Test
    fun `AE-34 the same instant evaluates with the injected zone, not another zone`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(16))
        // 15 Oct 22:30 UTC: 16 Oct 01:30 in Athens (UTC+3), 15 Oct 23:30 in London (UTC+1).
        val instant = java.time.Instant.parse("2025-10-15T22:30:00Z").toEpochMilli()
        assertEligible(
            1.0 / 1.08, oct(16), 0, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, instant, athens)
        )
        assertEquals(RateEligibility.None, evaluate(usd, eur, instant, london))
    }

    @Test
    fun `the JVM default zone does not influence the result`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(16))
        val instant = java.time.Instant.parse("2025-10-15T22:30:00Z").toEpochMilli()
        val saved = TimeZone.getDefault()
        try {
            for (defaultZone in listOf("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(defaultZone))
                assertTrue(evaluate(usd, eur, instant, athens) is RateEligibility.Eligible)
                assertEquals(RateEligibility.None, evaluate(usd, eur, instant, london))
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    // ---- AE-37: future dates ----

    @Test
    fun `AE-37 tomorrow with today's rate is eligible age 1 and plus 30 days is Stale`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        assertEligible(
            1.0 / 1.08, oct(15), 1, RateEligibilityPath.DIRECT, RateSourceLabel.ECB,
            evaluate(usd, eur, millis(oct(16), 12, 0, athens), athens)
        )
        val result = evaluate(usd, eur, millis(oct(15).plusDays(30), 12, 0, athens), athens)
        assertEquals(RateEligibility.Stale(oct(15), 30), result)
    }

    // ---- converter contract: one historical read bounded at the local date, never latest ----

    @Test
    fun `the lookup is bounded at UTC midnight of the local date`() = runTest {
        put("USD", "EUR", 1.0 / 1.08, oct(15))
        evaluate(usd, eur, millis(oct(15), 23, 30, athens), athens)
        assertEquals(listOf(utcMidnight(oct(15))), store.asOfBounds.distinct())
        assertEquals(0, store.latestReads)
    }

    // ---- fake ----

    private fun rowOf(from: String, to: String, rate: Double, validDate: Long?) = DomainExchangeRate(
        fromCurrency = from,
        toCurrency = to,
        rate = rate,
        lastUpdated = 4_102_444_800_000L,
        source = "ecb",
        validDate = validDate
    )

    /**
     * In-memory store with the DAO semantics: latest `validDate <= bound`; null dates never match.
     * [asOfOverride], when set, replaces that lookup so a test can break the DAO rules on purpose.
     */
    private class RateStoreFake : ExchangeRateStore {
        val rows = mutableListOf<DomainExchangeRate>()
        val asOfBounds = mutableListOf<Long>()
        var asOfOverride: ((from: String, to: String, bound: Long) -> DomainExchangeRate?)? = null
        var reads = 0
        var latestReads = 0

        override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            reads++
            return null
        }

        override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
            reads++
            latestReads++
            return null
        }

        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
            reads++
            asOfBounds += atMillis
            asOfOverride?.let { return it(fromCurrency, toCurrency, atMillis) }
            return rows
                .filter { it.fromCurrency == fromCurrency && it.toCurrency == toCurrency }
                .filter { row -> row.validDate?.let { it <= atMillis } ?: false }
                .maxByOrNull { it.validDate ?: Long.MIN_VALUE }
        }

        override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
            rows += rate
        }

        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
            rows += rates
        }

        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())

        override suspend fun getLatestRate(): DomainExchangeRate? = null

        override suspend fun deleteOldRates(olderThan: Long) = Unit
    }
}
