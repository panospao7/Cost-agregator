package com.yourname.expensetracker.data.currency

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class ExchangeRateStoreAdapterTest {

    private val exchangeRateDao = mockk<ExchangeRateDao>(relaxed = true)
    private lateinit var adapter: ExchangeRateStoreAdapter

    @Before
    fun setUp() {
        adapter = ExchangeRateStoreAdapter(exchangeRateDao)
    }

    @Test
    fun `get rate delegates to dao correctly`() = runTest {
        val entity = ExchangeRate(
            id = 1L,
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.92,
            lastUpdated = 1_700_000_001_000L,
            source = "api"
        )
        coEvery { exchangeRateDao.getRate("USD", "EUR") } returns entity

        val result = adapter.getRate("USD", "EUR")

        coVerify(exactly = 1) { exchangeRateDao.getRate("USD", "EUR") }
        assertNotNull(result)
        assertEquals("USD", result?.fromCurrency)
        assertEquals("EUR", result?.toCurrency)
        assertApproxEquals(0.92, result?.rate ?: 0.0, 0.0001)
        assertEquals(1_700_000_001_000L, result?.lastUpdated)
        assertEquals("api", result?.source)
    }

    @Test
    fun `save rate delegates to dao upsert`() = runTest {
        val rate = DomainExchangeRate(
            fromCurrency = "GBP",
            toCurrency = "EUR",
            rate = 1.17,
            lastUpdated = 1_700_000_002_000L,
            source = "manual"
        )
        coEvery { exchangeRateDao.insertOrUpdate(any()) } returns 10L

        adapter.insertOrUpdate(rate)

        val entitySlot = slot<ExchangeRate>()
        coVerify(exactly = 1) { exchangeRateDao.insertOrUpdate(capture(entitySlot)) }
        assertEquals("GBP", entitySlot.captured.fromCurrency)
        assertEquals("EUR", entitySlot.captured.toCurrency)
        assertApproxEquals(1.17, entitySlot.captured.rate, 0.0001)
        assertEquals(1_700_000_002_000L, entitySlot.captured.lastUpdated)
        assertEquals("manual", entitySlot.captured.source)
    }

    @Test
    fun `get all rates for base currency returns filtered results`() = runTest {
        val baseCurrency = "EUR"
        val entities = listOf(
            ExchangeRate(
                id = 1L,
                fromCurrency = "USD",
                toCurrency = baseCurrency,
                rate = 0.91,
                lastUpdated = 1_700_000_003_000L,
                source = "api"
            ),
            ExchangeRate(
                id = 2L,
                fromCurrency = "CHF",
                toCurrency = baseCurrency,
                rate = 1.03,
                lastUpdated = 1_700_000_004_000L,
                source = "api"
            )
        )
        every { exchangeRateDao.getAllRatesForBase(baseCurrency) } returns flowOf(entities)

        val result = adapter.getAllRatesForBase(baseCurrency).first()

        verify(exactly = 1) { exchangeRateDao.getAllRatesForBase(baseCurrency) }
        assertEquals(2, result.size)
        assertEquals(listOf("USD", "CHF"), result.map { it.fromCurrency })
        assertEquals(listOf(baseCurrency, baseCurrency), result.map { it.toCurrency })
        assertApproxEquals(0.91, result[0].rate, 0.0001)
        assertApproxEquals(1.03, result[1].rate, 0.0001)
    }

    @Test
    fun `delete stale rates delegates to dao cleanup method`() = runTest {
        val cutoff = 1_699_000_000_000L
        coEvery { exchangeRateDao.deleteOldRates(cutoff) } returns Unit

        adapter.deleteOldRates(cutoff)

        coVerify(exactly = 1) { exchangeRateDao.deleteOldRates(cutoff) }
    }
}
