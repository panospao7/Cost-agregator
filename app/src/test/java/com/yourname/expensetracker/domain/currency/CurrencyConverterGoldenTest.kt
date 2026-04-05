package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class CurrencyConverterGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var exchangeRateStore: ExchangeRateStore
    private lateinit var converter: CurrencyConverter

    @Before
    override fun setUp() {
        super.setUp()
        exchangeRateStore = mockk(relaxed = true)
        converter = CurrencyConverter(exchangeRateStore)
    }

    @Test
    fun `same currency conversion returns amount unchanged with rate 1`() = runTest {
        val amount = 100.0

        val result = converter.convert(amount, "EUR", "EUR")

        assertNotNull(result)
        assertApproxEquals(100.00, result!!.convertedAmount, 0.01)
        assertApproxEquals(1.0, result.rateUsed, 0.0001)
        assertEquals("EUR", result.originalCurrency)
        assertEquals("EUR", result.targetCurrency)
    }

    @Test
    fun `cross rate conversion via eur returns expected combined rate and converted amount`() = runTest {
        coEvery { exchangeRateStore.getRate("GBP", "JPY") } returns null
        coEvery { exchangeRateStore.getRate("GBP", "EUR") } returns DomainExchangeRate(
            fromCurrency = "GBP",
            toCurrency = "EUR",
            rate = 1.17,
            lastUpdated = 1_700_000_000_000L,
            source = "golden"
        )
        coEvery { exchangeRateStore.getRate("EUR", "JPY") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "JPY",
            rate = 162.50,
            lastUpdated = 1_700_000_100_000L,
            source = "golden"
        )

        val result = converter.convert(100.00, "GBP", "JPY")

        assertNotNull(result)
        assertApproxEquals(190.125, result!!.rateUsed, 0.0001)
        assertApproxEquals(19012.50, result.convertedAmount, 0.01)
    }
}
