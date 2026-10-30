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
import io.mockk.coVerifyOrder
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

    // ── MONEY-06: invalid stored rates fail closed on read ─────────

    private val invalidRates = listOf(
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.5
    )

    private fun entity(rate: Double, from: String = "USD", to: String = "EUR") = ExchangeRate(
        id = 1L,
        fromCurrency = from,
        toCurrency = to,
        rate = rate,
        lastUpdated = 1_700_000_001_000L,
        source = "api"
    )

    @Test
    fun `invalid stored rate maps to null on every single-row read`() = runTest {
        for (bad in invalidRates) {
            coEvery { exchangeRateDao.getRate("USD", "EUR") } returns entity(bad)
            coEvery { exchangeRateDao.getLatestRateForPair("USD", "EUR") } returns entity(bad)
            coEvery { exchangeRateDao.getRateAsOf("USD", "EUR", 5L) } returns entity(bad)
            coEvery { exchangeRateDao.getLatestRate() } returns entity(bad)

            assertEquals("getRate $bad", null, adapter.getRate("USD", "EUR"))
            assertEquals("getLatestRateForPair $bad", null, adapter.getLatestRateForPair("USD", "EUR"))
            assertEquals("getRateAsOf $bad", null, adapter.getRateAsOf("USD", "EUR", 5L))
            assertEquals("getLatestRate $bad", null, adapter.getLatestRate())
        }
    }

    @Test
    fun `valid stored rate is still returned unchanged`() = runTest {
        coEvery { exchangeRateDao.getLatestRateForPair("USD", "EUR") } returns entity(0.92)

        val result = adapter.getLatestRateForPair("USD", "EUR")

        assertNotNull(result)
        assertApproxEquals(0.92, result!!.rate, 0.0001)
    }

    @Test
    fun `rates-to-currency flow drops invalid rows and keeps valid rows`() = runTest {
        val rows = listOf(
            entity(Double.NaN, from = "USD"),
            entity(0.91, from = "GBP"),
            entity(0.0, from = "CHF"),
            entity(-1.0, from = "JPY"),
            entity(Double.POSITIVE_INFINITY, from = "SEK"),
            entity(1.03, from = "NOK")
        )
        every { exchangeRateDao.getRatesToCurrency("EUR") } returns flowOf(rows)

        val result = adapter.getRatesToCurrency("EUR").first()

        assertEquals(listOf("GBP", "NOK"), result.map { it.fromCurrency })
    }

    // ── Write barrier evidence (DB ownership policy) ───────────────

    @Test
    fun `every mutation checks write barrier before dao mutation`() = runTest {
        val rate = DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.92,
            lastUpdated = 1_700_000_001_000L,
            source = "api",
            validDate = 1_700_000_001_000L
        )
        val rates = listOf(
            DomainExchangeRate("USD", "EUR", 0.92, 1_700_000_001_000L, "api", 1_700_000_001_000L),
            DomainExchangeRate("GBP", "EUR", 1.17, 1_700_000_002_000L, "api", 1_700_000_002_000L)
        )
        val cutoff = 1_699_000_000_000L
        coEvery { exchangeRateDao.insertOrUpdate(any()) } returns 1L

        adapter.insertOrUpdate(rate)
        coVerifyOrder {
            writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdate")
            exchangeRateDao.insertOrUpdate(any())
        }

        adapter.insertOrUpdateAll(rates)
        coVerifyOrder {
            writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdateAll")
            exchangeRateDao.insertOrUpdateAll(any())
        }

        adapter.deleteOldRates(cutoff)
        coVerifyOrder {
            writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.deleteOldRates")
            exchangeRateDao.deleteOldRates(cutoff)
        }

        verify(exactly = 1) { writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdate") }
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdateAll") }
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.deleteOldRates") }
        coVerify(exactly = 1) { exchangeRateDao.insertOrUpdate(any()) }
        coVerify(exactly = 1) { exchangeRateDao.insertOrUpdateAll(any()) }
        coVerify(exactly = 1) { exchangeRateDao.deleteOldRates(cutoff) }
    }

    // ── MEC 1b S1: bulk history write (INV-1b-11, INV-1b-12, C12) ──────────

    private fun domainRows(count: Int) = List(count) { index ->
        DomainExchangeRate(
            fromCurrency = "C${index % 7}",
            toCurrency = "D${index / 7}",
            rate = 1.0 + index,
            lastUpdated = 1_700_000_000_000L,
            source = "ecb",
            validDate = 1_699_000_000_000L + index
        )
    }

    @Test
    fun `INV-1b-11 insertOrUpdateAll hands every row to the dao in one call without chunking`() = runTest {
        val rows = domainRows(52_920)
        val captured = slot<List<ExchangeRate>>()
        coEvery { exchangeRateDao.insertOrUpdateAll(capture(captured)) } returns Unit

        adapter.insertOrUpdateAll(rows)

        coVerify(exactly = 1) { exchangeRateDao.insertOrUpdateAll(any()) }
        assertEquals(52_920, captured.captured.size)
        assertEquals(rows.map { it.fromCurrency }, captured.captured.map { it.fromCurrency })
        assertEquals(rows.map { it.toCurrency }, captured.captured.map { it.toCurrency })
        assertEquals(rows.map { it.validDate }, captured.captured.map { it.validDate })
        assertEquals(rows.map { it.rate }, captured.captured.map { it.rate })
        assertEquals(setOf("ecb"), captured.captured.map { it.source }.toSet())
        assertEquals(setOf(0L), captured.captured.map { it.id }.toSet())
    }

    @Test
    fun `INV-1b-12 insertOrUpdateAll never deletes other rows`() = runTest {
        adapter.insertOrUpdateAll(domainRows(10))

        coVerify(exactly = 0) { exchangeRateDao.deleteOldRates(any()) }
        coVerify(exactly = 0) { exchangeRateDao.deleteAllRates() }
    }

    @Test
    fun `C12 bulk write is blocked during restore and touches no dao row`() = runTest {
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        every { maintenanceMode.isWritesAllowed() } returns false
        val blockingAdapter = ExchangeRateStoreAdapter(exchangeRateDao, DatabaseWriteBarrier(maintenanceMode))

        val exception = runCatching { blockingAdapter.insertOrUpdateAll(domainRows(5)) }.exceptionOrNull()

        assertNotNull("bulk write must throw during restore mode", exception)
        assertEquals(DatabaseAccessBlockedException::class, exception!!::class)
        coVerify(exactly = 0) { exchangeRateDao.insertOrUpdateAll(any()) }
    }

    @Test
    fun `INV-1b-11 a dao failure propagates so the repository can report STORE_FAILED`() = runTest {
        coEvery { exchangeRateDao.insertOrUpdateAll(any()) } throws IllegalStateException("insert failed")

        val exception = runCatching { adapter.insertOrUpdateAll(domainRows(3)) }.exceptionOrNull()

        assertEquals(IllegalStateException::class, exception!!::class)
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

        val exception = runCatching { blockingAdapter.insertOrUpdate(rate) }.exceptionOrNull()
        assertNotNull("insertOrUpdate must throw during restore mode", exception)
        assertEquals(DatabaseAccessBlockedException::class, exception!!::class)
        coVerify(exactly = 0) { exchangeRateDao.insertOrUpdate(any()) }
    }
}
