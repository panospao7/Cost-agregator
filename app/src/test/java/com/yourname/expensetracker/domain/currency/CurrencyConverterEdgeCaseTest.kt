package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.assertApproxEquals
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
    fun `stale direct rate is still used and timestamp is preserved`() = runTest {
        // Arrange
        val staleTimestamp = 1_577_836_800_000L // 2020-01-01 UTC
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.91,
            lastUpdated = staleTimestamp,
            source = "stale-fixture"
        )

        // Act
        val result = converter.convert(100.0, "USD", "EUR")

        // Assert
        assertNotNull(result)
        assertApproxEquals(91.0, result!!.convertedAmount, 0.000001)
        assertApproxEquals(0.91, result.rateUsed, 0.000001)
        assertEquals(staleTimestamp, result.timestamp)
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