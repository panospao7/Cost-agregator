package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.assertApproxEquals
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

class CurrencyConverterStressTest {

    private val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
    private val converter = CurrencyConverter(exchangeRateStore)

    @Test
    fun `five hundred conversions accumulated preserve total within tight tolerance`() = runTest {
        // Arrange
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "USD",
            rate = 1.085,
            lastUpdated = 1L,
            source = "fixture"
        )

        var total = 0.0

        // Act
        repeat(500) {
            total += converter.convert(33.33, "EUR", "USD")!!.convertedAmount
        }

        // Assert
        val expected = 500 * (33.33 * 1.085)
        assertApproxEquals(expected, total, 0.000001)
    }

    @Test
    fun `same amount roundtrip after many iterations stays numerically stable`() = runTest {
        // Arrange
        val eurToUsd = 1.085
        val usdToEur = 1.0 / eurToUsd
        coEvery { exchangeRateStore.getRate("EUR", "USD") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "USD",
            rate = eurToUsd,
            lastUpdated = 10L,
            source = "fixture"
        )
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = usdToEur,
            lastUpdated = 11L,
            source = "fixture"
        )

        var amount = 99_999.99

        // Act
        repeat(500) {
            val usd = converter.convert(amount, "EUR", "USD")
            assertNotNull(usd)
            amount = converter.convert(usd!!.convertedAmount, "USD", "EUR")!!.convertedAmount
        }

        // Assert
        assertApproxEquals(99_999.99, amount, 0.001)
    }
}
