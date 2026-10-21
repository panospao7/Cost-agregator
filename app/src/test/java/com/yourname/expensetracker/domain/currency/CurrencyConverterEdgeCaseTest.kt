package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CurrencyConverterEdgeCaseTest {

    private val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
    private val converter = CurrencyConverter(exchangeRateStore, timeProvider = mockk(relaxed = true))

    @Test
    fun `unknown currency pair without any path returns null`() = runTest {
        // Arrange
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null

        // Act
        val result = converter.convert(100.0, "BTC", "CHF")

        // Assert
        assertNull(result)
    }

    @Test
    fun `stale direct rate is refused by legacy convert 24h policy`() = runTest {
        // NEW-P5-012 (D4): the legacy convert path enforces the named
        // StaleRatePolicy.Default (24h). A direct rate older than 24h must be
        // REFUSED (treated as unavailable → null when no composite exists),
        // never silently used. The pre-D4 version of this test asserted the
        // OPPOSITE ("stale direct rate is still used") — an artifact of a
        // relaxed TimeProvider whose now()=0 made every fixture rate look
        // fresh; that relaxation also masked the TTL path entirely.
        val fixedNow = 1_700_000_000_000L
        val staleTimestamp = fixedNow - StaleRatePolicy.Default.maxAgeMs!! - 1 // just over 24h
        val fixedClockConverter = CurrencyConverter(
            exchangeRateStore,
            timeProvider = object : TimeProvider {
                override fun now(): Long = fixedNow
            }
        )
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.91,
            lastUpdated = staleTimestamp,
            source = "stale-fixture"
        )
        // No EUR-composite legs exist → the refused direct rate yields null.

        // Act
        val result = fixedClockConverter.convert(100.0, "USD", "EUR")

        // Assert — D4 policy: stale → unavailable
        assertNull("Rate older than 24h must be refused by the legacy 24h policy", result)
    }

    @Test
    fun `fresh direct rate within 24h converts and preserves rate timestamp`() = runTest {
        // Companion pin: the D4 policy is a 24h TTL, not a blanket refusal —
        // a rate just inside the threshold still converts and carries the
        // rate's own lastUpdated as the result timestamp.
        val fixedNow = 1_700_000_000_000L
        val freshTimestamp = fixedNow - StaleRatePolicy.Default.maxAgeMs!! + 1 // just under 24h
        val fixedClockConverter = CurrencyConverter(
            exchangeRateStore,
            timeProvider = object : TimeProvider {
                override fun now(): Long = fixedNow
            }
        )
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.91,
            lastUpdated = freshTimestamp,
            source = "fresh-fixture"
        )

        val result = fixedClockConverter.convert(100.0, "USD", "EUR")

        assertNotNull(result)
        assertApproxEquals(91.0, result!!.convertedAmount, 0.000001)
        assertApproxEquals(0.91, result.rateUsed, 0.000001)
        assertEquals(freshTimestamp, result.timestamp)
    }

    @Test
    fun `zero amount conversion returns zero converted amount`() = runTest {
        // Arrange
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "USD",
            rate = 1.085,
            lastUpdated = 123L,
            source = "fixture"
        )

        // Act
        val result = converter.convert(0.0, "EUR", "USD")

        // Assert
        assertNotNull(result)
        assertApproxEquals(0.0, result!!.convertedAmount, 0.000001)
        assertApproxEquals(1.085, result.rateUsed, 0.000001)
    }

    @Test
    fun `negative amount keeps sign after conversion`() = runTest {
        // Arrange
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "USD",
            rate = 1.085,
            lastUpdated = 123L,
            source = "fixture"
        )

        // Act
        val result = converter.convert(-25.0, "EUR", "USD")

        // Assert
        assertNotNull(result)
        assertApproxEquals(-27.125, result!!.convertedAmount, 0.000001)
        assertApproxEquals(1.085, result.rateUsed, 0.000001)
    }

    @Test
    fun `accumulated conversion drift over repeated cycles stays bounded`() = runTest {
        // Arrange
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "USD",
            rate = 1.085,
            lastUpdated = 111L,
            source = "fixture"
        )
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 1.0 / 1.085,
            lastUpdated = 112L,
            source = "fixture"
        )

        var currentAmount = 100.0

        // Act
        repeat(500) {
            currentAmount = converter.convert(currentAmount, "EUR", "USD")!!.convertedAmount
            currentAmount = converter.convert(currentAmount, "USD", "EUR")!!.convertedAmount
        }

        // Assert
        assertApproxEquals(100.0, currentAmount, 0.0001)
    }

    // ── MONEY-06: invalid rates from the store never produce a conversion ──

    private val moneyNow = 1_700_000_000_000L
    private val invalidRates = listOf(
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.5
    )
    private val fixedClockConverter = CurrencyConverter(
        exchangeRateStore,
        timeProvider = object : TimeProvider {
            override fun now(): Long = moneyNow
        }
    )

    private fun rateRow(from: String, to: String, rate: Double) = DomainExchangeRate(
        fromCurrency = from,
        toCurrency = to,
        rate = rate,
        lastUpdated = moneyNow,
        source = "fixture",
        validDate = moneyNow
    )

    @Test
    fun `legacy convert fails closed on invalid direct rate`() = runTest {
        for (bad in invalidRates) {
            coEvery { exchangeRateStore.getRate(any(), any()) } returns null
            coEvery { exchangeRateStore.getRate("USD", "GBP") } returns rateRow("USD", "GBP", bad)

            assertNull("direct rate $bad", fixedClockConverter.convert(100.0, "USD", "GBP"))
        }
    }

    @Test
    fun `legacy convert fails closed on invalid composite leg`() = runTest {
        for (bad in invalidRates) {
            coEvery { exchangeRateStore.getRate(any(), any()) } returns null
            coEvery { exchangeRateStore.getRate("USD", "EUR") } returns rateRow("USD", "EUR", bad)
            coEvery { exchangeRateStore.getRate("EUR", "GBP") } returns rateRow("EUR", "GBP", 0.85)
            assertNull("first leg $bad", fixedClockConverter.convert(100.0, "USD", "GBP"))

            coEvery { exchangeRateStore.getRate("USD", "EUR") } returns rateRow("USD", "EUR", 0.9)
            coEvery { exchangeRateStore.getRate("EUR", "GBP") } returns rateRow("EUR", "GBP", bad)
            assertNull("second leg $bad", fixedClockConverter.convert(100.0, "USD", "GBP"))
        }
    }

    @Test
    fun `legacy convert still converts with valid direct and composite rates`() = runTest {
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null
        coEvery { exchangeRateStore.getRate("USD", "GBP") } returns rateRow("USD", "GBP", 0.8)
        val direct = fixedClockConverter.convert(100.0, "USD", "GBP")
        assertNotNull(direct)
        assertApproxEquals(80.0, direct!!.convertedAmount, 0.000001)

        coEvery { exchangeRateStore.getRate("USD", "GBP") } returns null
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns rateRow("USD", "EUR", 0.9)
        coEvery { exchangeRateStore.getRate("EUR", "GBP") } returns rateRow("EUR", "GBP", 0.8)
        val composite = fixedClockConverter.convert(100.0, "USD", "GBP")
        assertNotNull(composite)
        assertApproxEquals(72.0, composite!!.convertedAmount, 0.000001)
    }

    @Test
    fun `convertAsOf fails closed on invalid direct and composite rates`() = runTest {
        for (bad in invalidRates) {
            coEvery { exchangeRateStore.getRateAsOf(any(), any(), any()) } returns null
            coEvery { exchangeRateStore.getRateAsOf("USD", "GBP", moneyNow) } returns rateRow("USD", "GBP", bad)
            assertNull("direct $bad", fixedClockConverter.convertAsOf(100.0, "USD", "GBP", moneyNow))

            coEvery { exchangeRateStore.getRateAsOf("USD", "GBP", moneyNow) } returns null
            coEvery { exchangeRateStore.getRateAsOf("USD", "EUR", moneyNow) } returns rateRow("USD", "EUR", bad)
            coEvery { exchangeRateStore.getRateAsOf("EUR", "GBP", moneyNow) } returns rateRow("EUR", "GBP", 0.8)
            assertNull("composite leg1 $bad", fixedClockConverter.convertAsOf(100.0, "USD", "GBP", moneyNow))

            coEvery { exchangeRateStore.getRateAsOf("USD", "EUR", moneyNow) } returns rateRow("USD", "EUR", 0.9)
            coEvery { exchangeRateStore.getRateAsOf("EUR", "GBP", moneyNow) } returns rateRow("EUR", "GBP", bad)
            assertNull("composite leg2 $bad", fixedClockConverter.convertAsOf(100.0, "USD", "GBP", moneyNow))
        }
    }

    @Test
    fun `convertAsOf still converts valid direct rate`() = runTest {
        coEvery { exchangeRateStore.getRateAsOf("USD", "GBP", moneyNow) } returns rateRow("USD", "GBP", 0.8)

        val result = fixedClockConverter.convertAsOf(100.0, "USD", "GBP", moneyNow)

        assertNotNull(result)
        assertApproxEquals(80.0, result!!.convertedAmount, 0.000001)
    }

    @Test
    fun `convertOutcome latest basis never yields Converted for invalid rates`() = runTest {
        for (bad in invalidRates) {
            coEvery { exchangeRateStore.getLatestRateForPair(any(), any()) } returns null
            coEvery { exchangeRateStore.getLatestRateForPair("USD", "GBP") } returns rateRow("USD", "GBP", bad)
            val direct = fixedClockConverter.convertOutcome(100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE)
            assertFailedMissing("direct $bad", direct)

            coEvery { exchangeRateStore.getLatestRateForPair("USD", "GBP") } returns null
            coEvery { exchangeRateStore.getLatestRateForPair("USD", "EUR") } returns rateRow("USD", "EUR", bad)
            coEvery { exchangeRateStore.getLatestRateForPair("EUR", "GBP") } returns rateRow("EUR", "GBP", 0.8)
            assertFailedMissing(
                "composite leg1 $bad",
                fixedClockConverter.convertOutcome(100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE)
            )

            coEvery { exchangeRateStore.getLatestRateForPair("USD", "EUR") } returns rateRow("USD", "EUR", 0.9)
            coEvery { exchangeRateStore.getLatestRateForPair("EUR", "GBP") } returns rateRow("EUR", "GBP", bad)
            assertFailedMissing(
                "composite leg2 $bad",
                fixedClockConverter.convertOutcome(100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE)
            )
        }
    }

    @Test
    fun `convertOutcome historical basis never yields Converted for invalid rates`() = runTest {
        for (bad in invalidRates) {
            coEvery { exchangeRateStore.getRateAsOf(any(), any(), any()) } returns null
            coEvery { exchangeRateStore.getRateAsOf("USD", "GBP", moneyNow) } returns rateRow("USD", "GBP", bad)
            val direct = fixedClockConverter.convertOutcome(
                100.0, "USD", "GBP", RateBasis.TRANSACTION_DATE, atMillis = moneyNow
            )
            assertEquals("direct $bad", true, direct is ConversionOutcome.Failed)

            coEvery { exchangeRateStore.getRateAsOf("USD", "GBP", moneyNow) } returns null
            coEvery { exchangeRateStore.getRateAsOf("USD", "EUR", moneyNow) } returns rateRow("USD", "EUR", 0.9)
            coEvery { exchangeRateStore.getRateAsOf("EUR", "GBP", moneyNow) } returns rateRow("EUR", "GBP", bad)
            val composite = fixedClockConverter.convertOutcome(
                100.0, "USD", "GBP", RateBasis.TRANSACTION_DATE, atMillis = moneyNow
            )
            assertEquals("composite $bad", true, composite is ConversionOutcome.Failed)
        }
    }

    @Test
    fun `convertOutcome still converts valid latest direct and composite rates`() = runTest {
        coEvery { exchangeRateStore.getLatestRateForPair(any(), any()) } returns null
        coEvery { exchangeRateStore.getLatestRateForPair("USD", "GBP") } returns rateRow("USD", "GBP", 0.8)
        val direct = fixedClockConverter.convertOutcome(100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE)
        assertEquals(true, direct is ConversionOutcome.Converted)
        assertApproxEquals(80.0, (direct as ConversionOutcome.Converted).convertedAmount, 0.000001)

        coEvery { exchangeRateStore.getLatestRateForPair("USD", "GBP") } returns null
        coEvery { exchangeRateStore.getLatestRateForPair("USD", "EUR") } returns rateRow("USD", "EUR", 0.9)
        coEvery { exchangeRateStore.getLatestRateForPair("EUR", "GBP") } returns rateRow("EUR", "GBP", 0.8)
        val composite = fixedClockConverter.convertOutcome(100.0, "USD", "GBP", RateBasis.LATEST_AVAILABLE)
        assertEquals(true, composite is ConversionOutcome.Converted)
        assertApproxEquals(72.0, (composite as ConversionOutcome.Converted).convertedAmount, 0.000001)
    }

    private fun assertFailedMissing(message: String, outcome: ConversionOutcome) {
        assertEquals(message, true, outcome is ConversionOutcome.Failed)
        assertEquals(
            message,
            ConversionFailureType.MISSING_RATE,
            (outcome as ConversionOutcome.Failed).failureType
        )
    }

    @Test
    fun `storeRate rejects non positive and non finite rates`() = runTest {
        converter.storeRate("EUR", "USD", 0.0)
        converter.storeRate("EUR", "USD", -1.0)
        converter.storeRate("EUR", "USD", Double.NaN)
        converter.storeRate("EUR", "USD", Double.POSITIVE_INFINITY)

        coVerify(exactly = 0) { exchangeRateStore.insertOrUpdate(any()) }
    }

    @Test
    fun `storeRates skips invalid entries and persists valid ones only`() = runTest {
        converter.storeRates(
            listOf(
                Triple("EUR", "USD", 1.1),
                Triple("EUR", "GBP", 0.0),
                Triple("EUR", "CHF", Double.NEGATIVE_INFINITY)
            )
        )

        coVerify(exactly = 1) {
            exchangeRateStore.insertOrUpdateAll(match { rates ->
                rates.size == 1 && rates.single().toCurrency == "USD" && rates.single().rate == 1.1
            })
        }
    }
}