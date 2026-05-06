package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [ExchangeRateDao] covering insert, query by currency pair,
 * and ordering by timestamp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExchangeRateDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ExchangeRateDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.exchangeRateDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeRate(
        from: String = "USD",
        to: String = "EUR",
        rate: Double = 0.92,
        lastUpdated: Long = FIXED_NOW,
        source: String = "manual",
        validDate: Long = FIXED_NOW
    ): ExchangeRate = ExchangeRate(
        fromCurrency = from,
        toCurrency = to,
        rate = rate,
        lastUpdated = lastUpdated,
        source = source,
        validDate = validDate
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert exchange rate and retrieve by currency pair`() = runTest {
        dao.insertOrUpdate(makeRate("USD", "EUR", 0.92, FIXED_NOW, "api"))

        val fetched = dao.getRate("USD", "EUR")
        assertNotNull(fetched)
        assertEquals(0.92, fetched.rate, 0.0001)
        assertEquals("api", fetched.source)
    }

    @Test
    fun `query by non-existent currency pair returns null`() = runTest {
        dao.insertOrUpdate(makeRate("USD", "EUR"))

        val fetched = dao.getRate("GBP", "JPY")
        assertNull(fetched)
    }

    @Test
    fun `insertOrUpdate replaces existing rate for same currency pair on same validDate`() = runTest {
        dao.insertOrUpdate(makeRate("GBP", "EUR", 1.15, FIXED_NOW, "manual"))
        dao.insertOrUpdate(makeRate("GBP", "EUR", 1.18, FIXED_NOW + 1000, "api"))

        val updated = dao.getRate("GBP", "EUR")
        assertNotNull(updated)
        assertEquals(1.18, updated.rate, 0.0001)
        assertEquals("api", updated.source)
    }

    @Test
    fun `insertOrUpdateAll inserts multiple rates`() = runTest {
        dao.insertOrUpdateAll(
            listOf(
                makeRate("USD", "EUR", 0.91, FIXED_NOW),
                makeRate("JPY", "EUR", 0.0064, FIXED_NOW),
                makeRate("GBP", "EUR", 1.17, FIXED_NOW)
            )
        )

        assertEquals(3, dao.getRateCount())
    }

    @Test
    fun `query rate as of date returns correct historical rate`() = runTest {
        val earlyDate = FIXED_NOW - 100_000
        val lateDate = FIXED_NOW

        dao.insertOrUpdate(makeRate("USD", "EUR", 0.85, earlyDate, "manual", validDate = earlyDate))
        dao.insertOrUpdate(makeRate("USD", "EUR", 0.92, lateDate, "api", validDate = lateDate))

        // Query for a date after lateDate should return the latest rate on or before that date
        val historical = dao.getRateAsOf("USD", "EUR", lateDate)
        assertNotNull(historical)
        assertEquals(0.92, historical.rate, 0.0001)
    }

    @Test
    fun `getRatesToCurrency returns rates filtered by target currency ordered by fromCurrency`() = runTest {
        dao.insertOrUpdateAll(
            listOf(
                makeRate("USD", "EUR", 0.91, FIXED_NOW),
                makeRate("AUD", "EUR", 0.60, FIXED_NOW),
                makeRate("JPY", "EUR", 0.0064, FIXED_NOW),
                makeRate("EUR", "USD", 1.08, FIXED_NOW) // unrelated target
            )
        )

        val ratesForEur = dao.getRatesToCurrency("EUR")
        val rates = ratesForEur.first()

        assertEquals(3, rates.size)
        // Ordered alphabetically by fromCurrency
        assertEquals(listOf("AUD", "JPY", "USD"), rates.map { it.fromCurrency })
    }

    @Test
    fun `getLatestRate returns most recently updated rate`() = runTest {
        dao.insertOrUpdate(makeRate("USD", "EUR", 0.90, FIXED_NOW - 10_000))
        dao.insertOrUpdate(makeRate("GBP", "EUR", 1.15, FIXED_NOW))

        val latest = dao.getLatestRate()
        assertNotNull(latest)
        assertEquals(FIXED_NOW, latest.lastUpdated)
        assertEquals("GBP", latest.fromCurrency)
    }

    @Test
    fun `deleteOldRates removes rates older than threshold`() = runTest {
        val threshold = FIXED_NOW

        dao.insertOrUpdate(makeRate("USD", "EUR", 0.90, threshold - 10_000))
        dao.insertOrUpdate(makeRate("JPY", "EUR", 0.0065, threshold + 10_000))
        dao.insertOrUpdate(makeRate("GBP", "EUR", 1.15, threshold - 5000))

        dao.deleteOldRates(threshold)

        assertNull(dao.getRate("USD", "EUR"))
        assertNotNull(dao.getRate("JPY", "EUR"))
        assertNull(dao.getRate("GBP", "EUR"))
        assertEquals(1, dao.getRateCount())
    }

    @Test
    fun `getRateCount returns correct count`() = runTest {
        assertEquals(0, dao.getRateCount())

        dao.insertOrUpdate(makeRate("USD", "EUR"))
        assertEquals(1, dao.getRateCount())

        dao.insertOrUpdate(makeRate("GBP", "EUR"))
        assertEquals(2, dao.getRateCount())
    }

    @Test
    fun `deleteAllRates removes all exchange rates`() = runTest {
        dao.insertOrUpdate(makeRate("USD", "EUR"))
        dao.insertOrUpdate(makeRate("GBP", "EUR"))

        dao.deleteAllRates()

        assertEquals(0, dao.getRateCount())
    }
}
