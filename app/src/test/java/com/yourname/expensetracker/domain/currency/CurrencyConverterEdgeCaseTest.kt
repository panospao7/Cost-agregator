package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.assertApproxEquals
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