package com.yourname.expensetracker.domain.currency

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timber.log.Timber
import java.util.Locale

/**
 * Currency conversion: rate lookups, composite (via EUR) rates, aggregates, rate
 * storage, supported-currency catalog and the deprecated formatter.
 *
 * Consolidated in B6-6: absorbs the former CurrencyConverterGoldenTest and
 * CurrencyConverterStressTest. Clock is fixed ([NOW]) so the 24h StaleRatePolicy.Default
 * window is deterministic (NEW-P5-012). Fixture rates default to `lastUpdated = NOW` or
 * `NOW - 1 s` (within the 24h window, so fresh for the fixed clock); a test that needs a stale
 * rate sets `lastUpdated` explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyConversionTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val FRESH_TS = NOW - 1_000L
    }

    private val fixedClock = object : TimeProvider {
        override fun now(): Long = NOW
    }

    private val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
    private val converter = CurrencyConverter(exchangeRateStore, timeProvider = fixedClock)

    private fun rate(from: String, to: String, rate: Double, lastUpdated: Long = NOW, source: String = "test") =
        DomainExchangeRate(
            fromCurrency = from,
            toCurrency = to,
            rate = rate,
            lastUpdated = lastUpdated,
            source = source
        )

    // ---------------------------------------------------------------- convert

    @Test
    fun `convert returns same amount with rate 1 and both currencies when currencies are identical`() = runTest {
        val result = converter.convert(100.0, "EUR", "EUR")

        assertThat(result).isNotNull()
        assertMoneyExact(100.0, result!!.originalAmount, "originalAmount")
        assertMoneyExact(100.0, result.convertedAmount, "convertedAmount")
        assertThat(result.rateUsed).isEqualTo(1.0)
        assertThat(result.originalCurrency).isEqualTo("EUR")
        assertThat(result.targetCurrency).isEqualTo("EUR")
    }

    @Test
    fun `convert direct rate result contains all required fields`() = runTest {
        // Survivor of: direct-rate, original/target currency and timestamp tests (exact subsets).
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns
            rate("USD", "EUR", 0.85, lastUpdated = FRESH_TS)

        val result = converter.convert(100.0, "USD", "EUR")

        assertThat(result).isNotNull()
        assertMoneyExact(100.0, result!!.originalAmount, "originalAmount")
        assertThat(result.originalCurrency).isEqualTo("USD")
        assertMoneyEquals(85.0, result.convertedAmount, "convertedAmount")
        assertThat(result.targetCurrency).isEqualTo("EUR")
        assertThat(result.rateUsed).isEqualTo(0.85)
        assertThat(result.timestamp).isEqualTo(1_699_999_999_000L)
    }

    @Test
    fun `convert uses EUR as intermediate when no direct rate`() = runTest {
        // Rows: from, to, fromToEurRate, eurToToRate, expectedCombinedRate, rateTolerance,
        // amount, expectedConverted (golden values; GBP->JPY is the former Golden row).
        data class Row(
            val name: String,
            val from: String,
            val to: String,
            val toEur: Double,
            val eurTo: Double,
            val expectedRate: Double,
            val rateTolerance: Double,
            val amount: Double,
            val expectedConverted: Double
        )
        val rows = listOf(
            Row("USD to GBP", "USD", "GBP", 0.85, 0.88, 0.748, 0.0, 100.0, 74.8),
            Row("GBP to JPY golden", "GBP", "JPY", 1.17, 162.50, 190.125, 0.0001, 100.0, 19012.50)
        )
        assertThat(rows).hasSize(2)

        rows.forEach { row ->
            val store = mockk<ExchangeRateStore>(relaxed = true)
            coEvery { store.getRate(row.from, row.to) } returns null
            coEvery { store.getRate(row.from, "EUR") } returns
                rate(row.from, "EUR", row.toEur, lastUpdated = NOW - 1)
            coEvery { store.getRate("EUR", row.to) } returns
                rate("EUR", row.to, row.eurTo, lastUpdated = NOW - 1)
            val rowConverter = CurrencyConverter(store, timeProvider = fixedClock)

            val result = rowConverter.convert(row.amount, row.from, row.to)

            assertThat(result).isNotNull()
            assertApproxEquals(row.expectedRate, result!!.rateUsed, row.rateTolerance, "${row.name}: rate ")
            assertMoneyEquals(row.expectedConverted, result.convertedAmount, "${row.name}: converted")
        }
    }

    @Test
    fun `convert returns null when a supported pair has no direct or composite rate`() = runTest {
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null

        val result = converter.convert(100.0, "USD", "GBP")

        assertThat(result).isNull()
        // Direct lookup, then both composite legs via EUR were attempted before giving up.
        coVerify(exactly = 1) { exchangeRateStore.getRate("USD", "GBP") }
        coVerify(exactly = 1) { exchangeRateStore.getRate("USD", "EUR") }
        coVerify(exactly = 1) { exchangeRateStore.getRate("EUR", "GBP") }
    }

    @Test
    fun `convert returns null for unsupported currency codes without any rate lookup`() = runTest {
        val result = converter.convert(100.0, "XYZ", "ABC")

        assertThat(result).isNull()
        coVerify(exactly = 0) { exchangeRateStore.getRate(any(), any()) }
    }

    @Test
    fun `convert handles case insensitive currency codes`() = runTest {
        // Survivor of the duplicate "rate lookup is case insensitive" test.
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns rate("USD", "EUR", 0.85)

        val result = converter.convert(100.0, "usd", "eur")

        assertThat(result).isNotNull()
        coVerify(exactly = 1) { exchangeRateStore.getRate("USD", "EUR") }
    }

    @Test
    fun `convert applies the rate to negative and zero amounts`() = runTest {
        // Rows: name, amount, expected converted, exact (pass-through zero) vs computed.
        val rows = listOf(
            Triple("negative", -100.0, -85.0),
            Triple("zero", 0.0, 0.0)
        )
        assertThat(rows).hasSize(2)

        rows.forEach { (name, amount, expected) ->
            val store = mockk<ExchangeRateStore>(relaxed = true)
            coEvery { store.getRate("USD", "EUR") } returns rate("USD", "EUR", 0.85)
            val rowConverter = CurrencyConverter(store, timeProvider = fixedClock)

            val result = rowConverter.convert(amount, "USD", "EUR")

            assertThat(result).isNotNull()
            if (amount == 0.0) {
                assertMoneyExact(expected, result!!.convertedAmount, name)
            } else {
                assertMoneyEquals(expected, result!!.convertedAmount, name)
            }
        }
    }

    // ---------------------------------------------------------- accumulation

    @Test
    fun `five hundred conversions accumulated preserve total within tight tolerance`() = runTest {
        // Folded from CurrencyConverterStressTest (B6-6). 500 * (33.33 * 1.085) = 500 * 36.16305.
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns rate("EUR", "USD", 1.085, source = "fixture")

        var total = 0.0
        repeat(500) {
            total += converter.convert(33.33, "EUR", "USD")!!.convertedAmount
        }

        assertApproxEquals(18_081.525, total, 0.000001)
    }

    @Test
    fun `same amount roundtrip after many iterations stays numerically stable`() = runTest {
        // Folded from CurrencyConverterStressTest (B6-6).
        val eurToUsd = 1.085
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns rate("EUR", "USD", eurToUsd, source = "fixture")
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns rate("USD", "EUR", 1.0 / eurToUsd, source = "fixture")

        var amount = 99_999.99
        repeat(500) {
            val usd = converter.convert(amount, "EUR", "USD")
            assertThat(usd).isNotNull()
            amount = converter.convert(usd!!.convertedAmount, "USD", "EUR")!!.convertedAmount
        }

        assertApproxEquals(99_999.99, amount, 0.001)
    }

    // --------------------------------------------------------- convertMultiple

    @Test
    fun `convertMultiple sums converted amounts`() = runTest {
        coEvery { exchangeRateStore.getLatestRateForPair("USD", "EUR") } returns rate("USD", "EUR", 0.85)
        coEvery { exchangeRateStore.getLatestRateForPair("GBP", "EUR") } returns rate("GBP", "EUR", 1.14)

        val total = converter.convertMultiple(listOf(100.0 to "USD", 50.0 to "GBP"), "EUR")

        // 100 USD = 85 EUR, 50 GBP = 57 EUR, Total = 142 EUR
        assertMoneyEquals(142.0, total.total, "total")
        assertThat(total.failedConversions).isEmpty()
    }

    @Test
    fun `convertMultiple records failure when conversion fails`() = runTest {
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null

        val total = converter.convertMultiple(listOf(100.0 to "XYZ"), "EUR")

        assertMoneyExact(0.0, total.total, "total")
        assertThat(total.failedConversions).hasSize(1)
    }

    @Test
    fun `convertMultiple with empty list returns zero`() = runTest {
        val total = converter.convertMultiple(emptyList(), "EUR")

        assertMoneyExact(0.0, total.total, "total")
        assertThat(total.failedConversions).isEmpty()
    }

    @Test
    fun `convertMultiple logs only aggregate count and code for mixed conversions`() = runTest {
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertThat(t).isNull()
                messages += message
            }
        }
        Timber.plant(tree)
        try {
            val aggregate = converter.convertMultiple(listOf(100.0 to "EUR", 1234.56 to "XYZ"), "EUR")

            assertMoneyExact(100.0, aggregate.total, "total")
            assertThat(aggregate.failedConversions).hasSize(1)
            assertMoneyExact(1234.56, aggregate.failedConversions.single().originalAmount, "failed amount")
            assertThat(messages).containsExactly("CurrencyConverter: MISSING_RATE count=1")
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `convertMultiple distinguishes supported missing rate and stale rate with controlled failure reasons`() = runTest {
        val staleTs = NOW - 8L * 24L * 60L * 60L * 1000L
        coEvery { exchangeRateStore.getLatestRateForPair("USD", "EUR") } returns null
        coEvery { exchangeRateStore.getLatestRateForPair("GBP", "EUR") } returns
            rate("GBP", "EUR", 1.14, lastUpdated = staleTs)
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertThat(t).isNull()
                messages += message
            }
        }
        Timber.plant(tree)
        try {
            val missing = converter.convertMultiple(listOf(1234.56 to "USD"), "EUR")
            assertMoneyExact(0.0, missing.total, "missing total")
            assertThat(missing.failedConversions.single().originalCurrency).isEqualTo("USD")
            assertThat(missing.failedConversions.single().failureType).isEqualTo(FailedConversion.MISSING_RATE)
            assertThat(missing.failedConversions.single().reason).isEqualTo("MISSING_RATE")

            val stale = converter.convertMultiple(listOf(50.0 to "GBP"), "EUR")
            assertMoneyExact(50.0, stale.failedConversions.single().originalAmount, "stale failed amount")
            assertThat(stale.failedConversions.single().failureType).isEqualTo(FailedConversion.STALE_RATE)
            assertThat(stale.failedConversions.single().reason).isEqualTo("STALE_RATE")

            val mixed = converter.convertMultiple(listOf(100.0 to "EUR", 1234.56 to "USD", 50.0 to "GBP"), "EUR")
            assertMoneyExact(100.0, mixed.total, "mixed total")
            assertThat(mixed.failedConversions.map { it.failureType }).containsExactly(
                FailedConversion.MISSING_RATE, FailedConversion.STALE_RATE
            ).inOrder()
            assertThat(mixed.failedConversions.map { it.reason }).containsExactly(
                "MISSING_RATE", "STALE_RATE"
            ).inOrder()
            assertThat(messages).containsExactly(
                "CurrencyConverter: MISSING_RATE count=1",
                "CurrencyConverter: STALE_RATE count=1",
                "CurrencyConverter: STALE_RATE count=2"
            ).inOrder()
        } finally {
            Timber.uproot(tree)
        }
    }

    // ------------------------------------- convertOutcome: transaction-date basis
    // Pins the converter behaviour RateEligibilityEvaluator depends on (manual-entry-currency A1).

    private fun datedRate(
        from: String,
        to: String,
        rate: Double,
        validDate: Long?,
        source: String
    ) = DomainExchangeRate(
        fromCurrency = from,
        toCurrency = to,
        rate = rate,
        lastUpdated = NOW,
        source = source,
        validDate = validDate
    )

    private fun historicalStore(): ExchangeRateStore {
        val store = mockk<ExchangeRateStore>(relaxed = true)
        coEvery { store.getRateAsOf(any(), any(), any()) } returns null
        return store
    }

    @Test
    fun `convertOutcome transaction date via EUR reports the older leg valid date and the plus joined sources`() = runTest {
        val bound = 9_000L
        // Both leg orders: the older date must win whether it is the first or the second leg.
        listOf(2_000L to 5_000L, 5_000L to 2_000L).forEach { (toEurDate, eurToDate) ->
            val store = historicalStore()
            coEvery { store.getRateAsOf("GBP", "EUR", bound) } returns
                datedRate("GBP", "EUR", 1.15, toEurDate, "ecb")
            coEvery { store.getRateAsOf("EUR", "USD", bound) } returns
                datedRate("EUR", "USD", 1.08, eurToDate, "manual")
            val historicalConverter = CurrencyConverter(store, timeProvider = fixedClock)

            val outcome = historicalConverter.convertOutcome(
                1.0, "GBP", "USD", RateBasis.TRANSACTION_DATE, bound, StaleRatePolicy.None
            )

            assertThat(outcome).isInstanceOf(ConversionOutcome.Converted::class.java)
            outcome as ConversionOutcome.Converted
            assertThat(outcome.conversionPath).isEqualTo(ConversionPath.VIA_BASE_CURRENCY)
            assertThat(outcome.rateValidDate).isEqualTo(2_000L)
            assertThat(outcome.rateSource).isEqualTo("ecb+manual")
            assertThat(outcome.rateUsed).isEqualTo(1.15 * 1.08)
        }
    }

    @Test
    fun `convertOutcome transaction date via EUR with both legs ecb joins the sources with plus`() = runTest {
        val bound = 9_000L
        val store = historicalStore()
        coEvery { store.getRateAsOf("GBP", "EUR", bound) } returns datedRate("GBP", "EUR", 1.15, 3_000L, "ecb")
        coEvery { store.getRateAsOf("EUR", "USD", bound) } returns datedRate("EUR", "USD", 1.08, 3_000L, "ecb")
        val historicalConverter = CurrencyConverter(store, timeProvider = fixedClock)

        val outcome = historicalConverter.convertOutcome(
            1.0, "GBP", "USD", RateBasis.TRANSACTION_DATE, bound, StaleRatePolicy.None
        ) as ConversionOutcome.Converted

        assertThat(outcome.rateSource).isEqualTo("ecb+ecb")
        assertThat(outcome.rateValidDate).isEqualTo(3_000L)
    }

    @Test
    fun `convertOutcome transaction date reads only the as-of lookup and carries a null valid date through`() = runTest {
        val bound = 9_000L
        val store = historicalStore()
        // A row without a valid date can only come from a store that breaks the as-of rule; the converter
        // does not hide it, it reports rateValidDate = null so the caller can fail closed.
        coEvery { store.getRateAsOf("USD", "EUR", bound) } returns datedRate("USD", "EUR", 0.9, null, "ecb")
        val historicalConverter = CurrencyConverter(store, timeProvider = fixedClock)

        val outcome = historicalConverter.convertOutcome(
            1.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, bound, StaleRatePolicy.None
        ) as ConversionOutcome.Converted

        assertThat(outcome.rateValidDate).isNull()
        assertThat(outcome.conversionPath).isEqualTo(ConversionPath.DIRECT)
        coVerify(exactly = 0) { store.getLatestRateForPair(any(), any()) }
        coVerify(exactly = 0) { store.getRate(any(), any()) }
    }

    @Test
    fun `convertOutcome transaction date without a rate at or before the bound is a typed Failed result`() = runTest {
        val store = historicalStore()
        val historicalConverter = CurrencyConverter(store, timeProvider = fixedClock)

        val outcome = historicalConverter.convertOutcome(
            1.0, "USD", "GBP", RateBasis.TRANSACTION_DATE, 9_000L, StaleRatePolicy.None
        )

        assertThat(outcome).isInstanceOf(ConversionOutcome.Failed::class.java)
        outcome as ConversionOutcome.Failed
        assertThat(outcome.failureType).isEqualTo(ConversionFailureType.MISSING_HISTORICAL_RATE)
        assertThat(outcome.rateBasis).isEqualTo(RateBasis.TRANSACTION_DATE)
        assertThat(outcome.originalCurrency).isEqualTo("USD")
        assertThat(outcome.targetCurrency).isEqualTo("GBP")
        // Direct lookup, then both legs via EUR, all bounded by the same date; never the latest rate.
        coVerify(exactly = 1) { store.getRateAsOf("USD", "GBP", 9_000L) }
        coVerify(exactly = 1) { store.getRateAsOf("USD", "EUR", 9_000L) }
        coVerify(exactly = 1) { store.getRateAsOf("EUR", "GBP", 9_000L) }
        coVerify(exactly = 0) { store.getLatestRateForPair(any(), any()) }
    }

    // ------------------------------------------------------------ rate storage

    @Test
    fun `storeRate inserts exchange rate`() = runTest {
        coEvery { exchangeRateStore.insertOrUpdate(any()) } just runs

        converter.storeRate("USD", "EUR", 0.85)

        val rateSlot = slot<DomainExchangeRate>()
        coVerify(exactly = 1) { exchangeRateStore.insertOrUpdate(capture(rateSlot)) }

        assertThat(rateSlot.captured.fromCurrency).isEqualTo("USD")
        assertThat(rateSlot.captured.toCurrency).isEqualTo("EUR")
        assertThat(rateSlot.captured.rate).isEqualTo(0.85)
        assertThat(rateSlot.captured.source).isEqualTo("manual")
    }

    @Test
    fun `storeRates inserts multiple rates`() = runTest {
        coEvery { exchangeRateStore.insertOrUpdateAll(any()) } just runs

        val rates = listOf(
            Triple("USD", "EUR", 0.85),
            Triple("GBP", "EUR", 1.14),
            Triple("JPY", "EUR", 0.0076)
        )

        converter.storeRates(rates, "api")

        val ratesSlot = slot<List<DomainExchangeRate>>()
        coVerify(exactly = 1) { exchangeRateStore.insertOrUpdateAll(capture(ratesSlot)) }

        assertThat(ratesSlot.captured).hasSize(3)
        assertThat(ratesSlot.captured.all { it.source == "api" }).isTrue()
    }

    @Test
    fun `hasRate reflects whether the store holds the pair`() = runTest {
        // Rows: name, stored rate (null = absent), expected.
        val rows = listOf(
            Triple("rate exists", rate("USD", "EUR", 0.85), true),
            Triple("rate absent", null, false)
        )
        assertThat(rows).hasSize(2)

        rows.forEach { (name, stored, expected) ->
            val store = mockk<ExchangeRateStore>(relaxed = true)
            coEvery { store.getRate("USD", "EUR") } returns stored
            val rowConverter = CurrencyConverter(store, timeProvider = fixedClock)

            assertWithMessage("row '$name'").that(rowConverter.hasRate("USD", "EUR")).isEqualTo(expected)
        }
    }

    @Test
    fun `getLastUpdateTime returns timestamp of latest rate or null when no rates`() = runTest {
        // Rows: name, latest stored rate, expected timestamp.
        val rows = listOf(
            Triple("latest rate present", rate("USD", "EUR", 0.85, lastUpdated = 1234567890L), 1234567890L),
            Triple("no rates", null, null)
        )
        assertThat(rows).hasSize(2)

        rows.forEach { (name, stored, expected) ->
            val store = mockk<ExchangeRateStore>(relaxed = true)
            coEvery { store.getLatestRate() } returns stored
            val rowConverter = CurrencyConverter(store, timeProvider = fixedClock)

            assertWithMessage("row '$name'").that(rowConverter.getLastUpdateTime()).isEqualTo(expected)
        }
    }

    @Test
    fun `cleanupOldRates removes rates older than timestamp`() = runTest {
        coEvery { exchangeRateStore.deleteOldRates(any()) } just runs

        val cutoff = 1234567890L
        converter.cleanupOldRates(cutoff)

        coVerify(exactly = 1) { exchangeRateStore.deleteOldRates(cutoff) }
    }

    // --------------------------------------------------------------- formatter

    @Suppress("DEPRECATION")
    @Test
    fun `formatAmount uses symbol or code fallback with two decimals`() {
        // Rows: amount, currency code, exact expected string (Locale pinned to US).
        val rows = listOf(
            Triple(100.0, "EUR", "€100.00"),
            Triple(100.0, "XYZ", "XYZ100.00"), // unknown currency falls back to the code
            Triple(100.999, "EUR", "€101.00") // rounds to two decimal places
        )
        assertThat(rows).hasSize(3)

        val previous = Locale.getDefault()
        val previousFormat = Locale.getDefault(Locale.Category.FORMAT)
        val previousDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.US)
        try {
            rows.forEach { (amount, code, expected) ->
                assertThat(converter.formatAmount(amount, code)).isEqualTo(expected)
            }
        } finally {
            // setDefault(Locale) overwrites the per-category defaults, so restore the base
            // locale first and then each category explicitly.
            Locale.setDefault(previous)
            Locale.setDefault(Locale.Category.FORMAT, previousFormat)
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay)
        }
    }

    // ------------------------------------------------------ supported currency

    @Test
    fun `supported currency fromCode resolves major currencies and returns null for unknown`() {
        // Rows: input code, expected code (null = unknown), expected symbol.
        val rows = listOf(
            Triple("EUR", "EUR", "€"),
            Triple("USD", "USD", "$"),
            Triple("GBP", "GBP", "£"),
            Triple("JPY", "JPY", "¥"),
            Triple("XYZ", null, null)
        )
        assertThat(rows).hasSize(5)

        rows.forEach { (input, expectedCode, expectedSymbol) ->
            val currency = SupportedCurrency.fromCode(input)
            if (expectedCode == null) {
                assertThat(currency).isNull()
            } else {
                assertThat(currency).isNotNull()
                assertThat(currency!!.code).isEqualTo(expectedCode)
                assertThat(currency.symbol).isEqualTo(expectedSymbol)
            }
        }
    }

    @Test
    fun `default base currency is EUR`() {
        assertThat(CurrencyConverter.DEFAULT_BASE_CURRENCY).isEqualTo("EUR")
    }
}
