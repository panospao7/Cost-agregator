package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExchangeRateDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ExchangeRateDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.exchangeRateDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeRate(
        from: String,
        to: String,
        rate: Double,
        lastUpdated: Long,
        source: String = "manual"
    ) = ExchangeRate(
        fromCurrency = from,
        toCurrency = to,
        rate = rate,
        lastUpdated = lastUpdated,
        source = source
    )

    @Test
    fun insertExchangeRate_retrieveByCurrencyPair() = runBlocking {
        val now = 1_700_000_000_000L
        dao.insertOrUpdate(makeRate("USD", "EUR", 0.92, now, "api"))

        val fetched = dao.getRate("USD", "EUR")
        assertNotNull(fetched)
        assertEquals(0.92, fetched!!.rate, 0.0001)
        assertEquals("api", fetched.source)
    }

    @Test
    fun upsertRate_updatesExistingRateForSamePair() = runBlocking {
        val now = 1_700_000_000_000L
        dao.insertOrUpdate(makeRate("GBP", "EUR", 1.15, now, "manual"))
        dao.insertOrUpdate(makeRate("GBP", "EUR", 1.18, now + 1_000, "api"))

        val updated = dao.getRate("GBP", "EUR")
        assertNotNull(updated)
        assertEquals(1.18, updated!!.rate, 0.0001)
        assertEquals("api", updated.source)
        assertEquals(1, dao.getRateCount())
    }

    @Test
    fun staleRatesOlderThanThreshold_areRemoved() = runBlocking {
        val threshold = 1_700_000_000_000L
        dao.insertOrUpdate(makeRate("USD", "EUR", 0.90, threshold - 10_000))
        dao.insertOrUpdate(makeRate("JPY", "EUR", 0.0065, threshold + 10_000))

        dao.deleteOldRates(threshold)

        assertNull(dao.getRate("USD", "EUR"))
        assertNotNull(dao.getRate("JPY", "EUR"))
        assertEquals(1, dao.getRateCount())
    }

    @Test
    fun queryAllRatesForBaseCurrency_returnsOnlyMatchingBaseOrderedByFromCurrency() = runBlocking {
        val now = 1_700_000_000_000L
        dao.insertOrUpdateAll(
            listOf(
                makeRate("USD", "EUR", 0.91, now),
                makeRate("AUD", "EUR", 0.60, now),
                makeRate("JPY", "EUR", 0.0064, now),
                makeRate("EUR", "USD", 1.08, now) // unrelated base
            )
        )

        val ratesForEurBase = dao.getAllRatesForBase("EUR").first()

        assertEquals(3, ratesForEurBase.size)
        assertEquals(listOf("AUD", "JPY", "USD"), ratesForEurBase.map { it.fromCurrency })
    }
}
