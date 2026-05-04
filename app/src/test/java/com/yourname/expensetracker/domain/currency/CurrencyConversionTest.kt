package com.yourname.expensetracker.domain.currency

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * PHASE 6 TEST: Currency Conversion
 * 
 * Tests currency conversion logic, rate lookups, and multi-currency support.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrencyConversionTest {

    private val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
    private lateinit var converter: CurrencyConverter

    @Before
    fun setup() {
        converter = CurrencyConverter(exchangeRateStore, timeProvider = mockk())
    }

    @Test
    fun `convert returns same amount when currencies are identical`() = runTest {
        val result = converter.convert(100.0, "EUR", "EUR")
        
        assertThat(result).isNotNull()
        assertThat(result!!.originalAmount).isEqualTo(100.0)
        assertThat(result.convertedAmount).isEqualTo(100.0)
        assertThat(result.rateUsed).isEqualTo(1.0)
    }

    @Test
    fun `convert uses direct rate when available`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val result = converter.convert(100.0, "USD", "EUR")
        
        assertThat(result).isNotNull()
        assertThat(result!!.convertedAmount).isEqualTo(85.0) // 100 * 0.85
        assertThat(result.rateUsed).isEqualTo(0.85)
    }

    @Test
    fun `convert uses EUR as intermediate when no direct rate`() = runTest {
        // No direct USD->GBP rate
        coEvery { exchangeRateStore.getRate("USD", "GBP") } returns null
        
        // But have USD->EUR and EUR->GBP
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        coEvery { exchangeRateStore.getRate("EUR", "GBP") } returns DomainExchangeRate(
            fromCurrency = "EUR", toCurrency = "GBP", rate = 0.88,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val result = converter.convert(100.0, "USD", "GBP")
        
        assertThat(result).isNotNull()
        // Combined rate: 0.85 * 0.88 = 0.748
        assertThat(result!!.convertedAmount).isEqualTo(74.8)
        assertThat(result.rateUsed).isEqualTo(0.748)
    }

    @Test
    fun `convert returns null when no rate available`() = runTest {
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null
        
        val result = converter.convert(100.0, "XYZ", "ABC")
        
        assertThat(result).isNull()
    }

    @Test
    fun `convert handles case insensitive currency codes`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val result = converter.convert(100.0, "usd", "eur")
        
        assertThat(result).isNotNull()
        coVerify { exchangeRateStore.getRate("USD", "EUR") }
    }

    @Test
    fun `convert includes original and target currencies in result`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val result = converter.convert(100.0, "USD", "EUR")
        
        assertThat(result!!.originalCurrency).isEqualTo("USD")
        assertThat(result.targetCurrency).isEqualTo("EUR")
    }

    @Test
    fun `convert includes timestamp from exchange rate`() = runTest {
        val timestamp = 1234567890L
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.85,
            lastUpdated = timestamp,
            source = "test"
        )
        
        val result = converter.convert(100.0, "USD", "EUR")
        
        assertThat(result!!.timestamp).isEqualTo(timestamp)
    }

    @Test
    fun `convertMultiple sums converted amounts`() = runTest {
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        coEvery { exchangeRateStore.getRate("GBP", "EUR") } returns DomainExchangeRate(
            fromCurrency = "GBP", toCurrency = "EUR", rate = 1.14,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val amounts = listOf(
            100.0 to "USD",
            50.0 to "GBP"
        )
        
        val total = converter.convertMultiple(amounts, "EUR")
        
        // 100 USD = 85 EUR, 50 GBP = 57 EUR, Total = 142 EUR
        assertThat(total.total).isEqualTo(142.0)
        assertThat(total.failedConversions).isEmpty()
    }

    @Test
    fun `convertMultiple records failure when conversion fails`() = runTest {
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null
        
        val amounts = listOf(100.0 to "XYZ")
        
        val total = converter.convertMultiple(amounts, "EUR")
        
        assertThat(total.total).isEqualTo(0.0)
        assertThat(total.failedConversions).hasSize(1)
    }

    @Test
    fun `storeRate inserts exchange rate`() = runTest {
        coEvery { exchangeRateStore.insertOrUpdate(any()) } just runs
        
        converter.storeRate("USD", "EUR", 0.85)
        
        val rateSlot = slot<DomainExchangeRate>()
        coVerify { exchangeRateStore.insertOrUpdate(capture(rateSlot)) }
        
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
        coVerify { exchangeRateStore.insertOrUpdateAll(capture(ratesSlot)) }
        
        assertThat(ratesSlot.captured).hasSize(3)
        assertThat(ratesSlot.captured.all { it.source == "api" }).isTrue()
    }

    @Test
    fun `hasRate returns true when rate exists`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val hasRate = converter.hasRate("USD", "EUR")
        
        assertThat(hasRate).isTrue()
    }

    @Test
    fun `hasRate returns false when rate does not exist`() = runTest {
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns null
        
        val hasRate = converter.hasRate("USD", "EUR")
        
        assertThat(hasRate).isFalse()
    }

    @Test
    fun `getLastUpdateTime returns timestamp of latest rate`() = runTest {
        val timestamp = 1234567890L
        coEvery { exchangeRateStore.getLatestRate() } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.85,
            lastUpdated = timestamp,
            source = "test"
        )
        
        val lastUpdate = converter.getLastUpdateTime()
        
        assertThat(lastUpdate).isEqualTo(timestamp)
    }

    @Test
    fun `getLastUpdateTime returns null when no rates`() = runTest {
        coEvery { exchangeRateStore.getLatestRate() } returns null
        
        val lastUpdate = converter.getLastUpdateTime()
        
        assertThat(lastUpdate).isNull()
    }

    @Test
    fun `cleanupOldRates removes rates older than timestamp`() = runTest {
        coEvery { exchangeRateStore.deleteOldRates(any()) } just runs
        
        val cutoff = 1234567890L
        converter.cleanupOldRates(cutoff)
        
        coVerify { exchangeRateStore.deleteOldRates(cutoff) }
    }

    @Test
    fun `formatAmount includes currency symbol`() = runTest {
        val formatted = converter.formatAmount(100.0, "EUR")
        
        assertThat(formatted).contains("€")
        assertThat(formatted).contains("100")
    }

    @Test
    fun `formatAmount handles unknown currency`() = runTest {
        val formatted = converter.formatAmount(100.0, "XYZ")
        
        // Falls back to currency code
        assertThat(formatted).contains("XYZ")
        assertThat(formatted).contains("100")
    }

    @Test
    fun `formatAmount rounds to two decimal places`() = runTest {
        val formatted = converter.formatAmount(100.999, "EUR")
        
        assertThat(formatted).contains("101.00")
    }

    @Test
    fun `supported currencies includes major currencies`() = runTest {
        val supported = listOf(
            SupportedCurrency.EUR,
            SupportedCurrency.USD,
            SupportedCurrency.GBP,
            SupportedCurrency.JPY
        )
        
        assertThat(supported).isNotEmpty()
        assertThat(supported.map { it.code }).contains("EUR")
        assertThat(supported.map { it.code }).contains("USD")
    }

    @Test
    fun `supported currency fromCode finds matching currency`() = runTest {
        val currency = SupportedCurrency.fromCode("EUR")
        
        assertThat(currency).isNotNull()
        assertThat(currency!!.code).isEqualTo("EUR")
        assertThat(currency.symbol).isEqualTo("€")
    }

    @Test
    fun `supported currency fromCode returns null for unknown`() = runTest {
        val currency = SupportedCurrency.fromCode("XYZ")
        
        assertThat(currency).isNull()
    }

    @Test
    fun `default base currency is EUR`() = runTest {
        assertThat(CurrencyConverter.DEFAULT_BASE_CURRENCY).isEqualTo("EUR")
    }

    @Test
    fun `convert with negative amount works correctly`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val result = converter.convert(-100.0, "USD", "EUR")
        
        assertThat(result!!.convertedAmount).isEqualTo(-85.0)
    }

    @Test
    fun `convert with zero amount returns zero`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        val result = converter.convert(0.0, "USD", "EUR")
        
        assertThat(result!!.convertedAmount).isEqualTo(0.0)
    }

    @Test
    fun `convertMultiple with empty list returns zero`() = runTest {
        val total = converter.convertMultiple(emptyList(), "EUR")
        
        assertThat(total.total).isEqualTo(0.0)
        assertThat(total.failedConversions).isEmpty()
    }

    @Test
    fun `rate lookup is case insensitive`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD", toCurrency = "EUR", rate = 0.85,
            lastUpdated = System.currentTimeMillis(),
            source = "test"
        )
        
        converter.convert(100.0, "usd", "eur")
        
        coVerify { exchangeRateStore.getRate("USD", "EUR") }
    }

    @Test
    fun `conversion result contains all required fields`() = runTest {
        coEvery { 
            exchangeRateStore.getRate("USD", "EUR") 
        } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.85,
            lastUpdated = 1234567890L,
            source = "test"
        )
        
        val result = converter.convert(100.0, "USD", "EUR")
        
        assertThat(result!!.originalAmount).isEqualTo(100.0)
        assertThat(result.originalCurrency).isEqualTo("USD")
        assertThat(result.convertedAmount).isEqualTo(85.0)
        assertThat(result.targetCurrency).isEqualTo("EUR")
        assertThat(result.rateUsed).isEqualTo(0.85)
        assertThat(result.timestamp).isEqualTo(1234567890L)
    }
}