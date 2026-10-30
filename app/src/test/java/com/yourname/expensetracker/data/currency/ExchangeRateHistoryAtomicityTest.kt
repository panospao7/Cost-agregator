package com.yourname.expensetracker.data.currency

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MEC 1b S1: the history bulk write is one Room transaction (INV-1b-11), REPLACE-idempotent
 * (INV-1b-12) and sized for the 70 x 28 x 27 = 52,920-row cap. Real in-memory Room; no timing asserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExchangeRateHistoryAtomicityTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ExchangeRateDao
    private lateinit var adapter: ExchangeRateStoreAdapter

    private val day = 24L * 60L * 60L * 1000L
    private val firstDay = 1_790_000_000_000L - (1_790_000_000_000L % day)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.exchangeRateDao()
        adapter = ExchangeRateStoreAdapter(dao, mockk<DatabaseWriteBarrier>(relaxed = true))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        from: String,
        to: String,
        validDate: Long,
        rate: Double = 1.5,
        lastUpdated: Long = 1_000L
    ) = DomainExchangeRate(from, to, rate, lastUpdated, "ecb", validDate)

    private fun fullHistory(days: Int, lastUpdated: Long = 2_000L): List<DomainExchangeRate> {
        val codes = SupportedCurrency.catalog.map { it.code }
        val rows = ArrayList<DomainExchangeRate>(days * codes.size * (codes.size - 1))
        for (d in 0 until days) {
            for (from in codes) {
                for (to in codes) {
                    if (from == to) continue
                    rows.add(row(from, to, firstDay + d * day, rate = 1.0 + d + codes.indexOf(to) * 0.001, lastUpdated = lastUpdated))
                }
            }
        }
        return rows
    }

    @Test
    fun `INV-1b-11 a failing row aborts the whole bulk insert and leaves no new rows`() = runTest {
        adapter.insertOrUpdateAll(listOf(row("USD", "EUR", firstDay - 5 * day, rate = 0.9)))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_one_row BEFORE INSERT ON exchange_rates " +
                "WHEN NEW.fromCurrency = 'ZAR' AND NEW.toCurrency = 'NOK' AND NEW.validDate = ${firstDay + 3 * day} " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
        )
        val history = fullHistory(days = 5)
        assertTrue(history.any { it.fromCurrency == "ZAR" && it.toCurrency == "NOK" && it.validDate == firstDay + 3 * day })

        val failure = runCatching { adapter.insertOrUpdateAll(history) }.exceptionOrNull()

        assertNotNull("the poisoned row must fail the insert", failure)
        assertEquals("only the pre-existing row remains", 1, dao.getRateCount())
        assertEquals(0.9, dao.getRateAsOf("USD", "EUR", firstDay)!!.rate, 0.0)
        assertNull(dao.getRateAsOf("EUR", "USD", firstDay + 10 * day))
    }

    @Test
    fun `INV-1b-11 a full 52920 row history commits completely in one write`() = runTest {
        val history = fullHistory(days = 70)
        assertEquals(52_920, history.size)

        adapter.insertOrUpdateAll(history)

        assertEquals(52_920, dao.getRateCount())
        assertNotNull(dao.getRateAsOf("EUR", "USD", firstDay))
        assertNotNull(dao.getRateAsOf("ISK", "HRK", firstDay + 69 * day))
    }

    @Test
    fun `INV-1b-12 repeating the same history keeps the row count and refreshes values`() = runTest {
        adapter.insertOrUpdateAll(fullHistory(days = 3, lastUpdated = 2_000L))
        val firstCount = dao.getRateCount()
        assertEquals(3 * 28 * 27, firstCount)

        adapter.insertOrUpdateAll(fullHistory(days = 3, lastUpdated = 9_000L))

        assertEquals(firstCount, dao.getRateCount())
        assertEquals(9_000L, dao.getRateAsOf("EUR", "USD", firstDay)!!.lastUpdated)
    }

    @Test
    fun `INV-1b-12 a history download never removes rows outside its window`() = runTest {
        val outside = listOf(
            row("USD", "EUR", firstDay - 200 * day, rate = 0.8),
            row("USD", "EUR", firstDay + 200 * day, rate = 0.7),
            row("USD", "EUR", 1L, rate = 0.6, lastUpdated = 1L)
        )
        adapter.insertOrUpdateAll(outside)

        adapter.insertOrUpdateAll(fullHistory(days = 2))

        assertEquals(3 + 2 * 28 * 27, dao.getRateCount())
        assertEquals(0.8, dao.getRateAsOf("USD", "EUR", firstDay - 100 * day)!!.rate, 0.0)
        assertEquals(0.6, dao.getRateAsOf("USD", "EUR", 1L)!!.rate, 0.0)
    }
}
