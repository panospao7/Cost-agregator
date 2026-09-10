package com.yourname.expensetracker.data.currency

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ExchangeRateStoreAdapterTest {

    private val exchangeRateDao = mockk<ExchangeRateDao>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private lateinit var adapter: ExchangeRateStoreAdapter

    @Before
    fun setUp() {
        adapter = ExchangeRateStoreAdapter(exchangeRateDao, writeBarrier)
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
            source = "manual",
            validDate = 1_700_000_002_000L
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
        every { exchangeRateDao.getRatesToCurrency(baseCurrency) } returns flowOf(entities)

        val result = adapter.getRatesToCurrency(baseCurrency).first()

        verify(exactly = 1) { exchangeRateDao.getRatesToCurrency(baseCurrency) }
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

    // ── Write barrier evidence (DB ownership policy) ───────────────

    @Test
    fun `insertOrUpdate checks write barrier before dao mutation`() = runTest {
        val rate = DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.92,
            lastUpdated = 1_700_000_001_000L,
            source = "api",
            validDate = 1_700_000_001_000L
        )
        coEvery { exchangeRateDao.insertOrUpdate(any()) } returns 1L

        adapter.insertOrUpdate(rate)

        verify(exactly = 1) {
            writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdate")
        }
        coVerify(exactly = 1) { exchangeRateDao.insertOrUpdate(any()) }
    }

    @Test
    fun `insertOrUpdateAll checks write barrier before dao mutation`() = runTest {
        val rates = listOf(
            DomainExchangeRate("USD", "EUR", 0.92, 1_700_000_001_000L, "api", 1_700_000_001_000L),
            DomainExchangeRate("GBP", "EUR", 1.17, 1_700_000_002_000L, "api", 1_700_000_002_000L)
        )

        adapter.insertOrUpdateAll(rates)

        verify(exactly = 1) {
            writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdateAll")
        }
        coVerify(exactly = 1) { exchangeRateDao.insertOrUpdateAll(any()) }
    }

    @Test
    fun `deleteOldRates checks write barrier before dao mutation`() = runTest {
        val cutoff = 1_699_000_000_000L

        adapter.deleteOldRates(cutoff)

        verify(exactly = 1) {
            writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.deleteOldRates")
        }
        coVerify(exactly = 1) { exchangeRateDao.deleteOldRates(cutoff) }
    }

    @Test
    fun `write mutations blocked during restore mode`() = runTest {
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        every { maintenanceMode.isWritesAllowed() } returns false
        val blockingBarrier = DatabaseWriteBarrier(maintenanceMode)
        val blockingAdapter = ExchangeRateStoreAdapter(exchangeRateDao, blockingBarrier)
        val rate = DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.92,
            lastUpdated = 1_700_000_001_000L,
            source = "api",
            validDate = 1_700_000_001_000L
        )

        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { blockingAdapter.insertOrUpdate(rate) }
        }
        coVerify(exactly = 0) { exchangeRateDao.insertOrUpdate(any()) }
    }
}
