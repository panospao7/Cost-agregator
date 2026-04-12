package com.yourname.expensetracker.domain.investment

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.InvestmentDao
import com.yourname.expensetracker.data.database.dao.InvestmentValueDao
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * B.4-10: Verifies InvestmentTracker all-time high/low uses the full
 * historical range (epoch 0) instead of a fixed 30-day window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentTrackerTest {

    private val investmentDao = mockk<InvestmentDao>(relaxed = true)
    private val investmentValueDao = mockk<InvestmentValueDao>(relaxed = true)
    private val timeProvider = FakeTimeProvider(fixedTime = 1_700_000_000_000L) // ~Nov 2023

    private lateinit var tracker: InvestmentTracker

    @Before
    fun setup() {
        tracker = InvestmentTracker(investmentDao, investmentValueDao, timeProvider)
    }

    // ---- All-time high/low correctness ----

    @Test
    fun `allTimeHigh and allTimeLow query from epoch 0, not 30-day window`() = runTest {
        val investment = makeInvestment(id = 1L, currentPrice = 150.0, purchasePrice = 100.0)
        coEvery { investmentDao.getById(1L) } returns investment
        // Recent 30-day window returns an empty list (no recent history)
        coEvery { investmentValueDao.getValuesBetween(1L, any(), any()) } returns emptyList()
        // All-time queries return values from months ago
        coEvery { investmentValueDao.getMaxPrice(1L, 0L) } returns 200.0
        coEvery { investmentValueDao.getMinPrice(1L, 0L) } returns 50.0

        val perf = tracker.getInvestmentPerformance(1L)!!

        assertThat(perf.allTimeHigh).isEqualTo(200.0)
        assertThat(perf.allTimeLow).isEqualTo(50.0)
        // Verify the DAO was called with startDate = 0 (true all-time)
        coVerify { investmentValueDao.getMaxPrice(1L, 0L) }
        coVerify { investmentValueDao.getMinPrice(1L, 0L) }
    }

    @Test
    fun `allTimeHigh is null when no historical values exist`() = runTest {
        val investment = makeInvestment(id = 2L)
        coEvery { investmentDao.getById(2L) } returns investment
        coEvery { investmentValueDao.getValuesBetween(2L, any(), any()) } returns emptyList()
        coEvery { investmentValueDao.getMaxPrice(2L, 0L) } returns null
        coEvery { investmentValueDao.getMinPrice(2L, 0L) } returns null

        val perf = tracker.getInvestmentPerformance(2L)!!

        assertThat(perf.allTimeHigh).isNull()
        assertThat(perf.allTimeLow).isNull()
    }

    /**
     * B.4 ISSUE-2: getValuesBetween is ORDER BY timestamp ASC, so the last element
     * is the most-recent sample.  This test uses THREE in-window values at distinct
     * timestamps and verifies that dayChange comes from the LAST (newest) one, not
     * the first (oldest) one.
     */
    @Test
    fun `dayChange uses the LATEST in-window value when multiple samples exist`() = runTest {
        val investment = makeInvestment(id = 3L, currentPrice = 120.0, purchasePrice = 100.0)
        coEvery { investmentDao.getById(3L) } returns investment

        val now = timeProvider.now()
        // ASC order: oldest → middle → newest (as the DAO returns them)
        val oldestValue = InvestmentValue(
            id = 10,
            investmentId = 3L,
            price = 110.0,
            totalValue = 110.0,
            timestamp = now - 72_000_000L,   // 20 hours ago
            dayChange = -5.0,                // wrong value – must NOT be chosen
            dayChangePercent = -4.3
        )
        val middleValue = InvestmentValue(
            id = 11,
            investmentId = 3L,
            price = 115.0,
            totalValue = 115.0,
            timestamp = now - 36_000_000L,   // 10 hours ago
            dayChange = 3.0,                 // wrong value – must NOT be chosen
            dayChangePercent = 2.7
        )
        val newestValue = InvestmentValue(
            id = 12,
            investmentId = 3L,
            price = 118.0,
            totalValue = 118.0,
            timestamp = now - 3_600_000L,    // 1 hour ago – the latest sample
            dayChange = 2.0,
            dayChangePercent = 1.7
        )
        // DAO returns values in ASC timestamp order (oldest first, newest last)
        coEvery { investmentValueDao.getValuesBetween(3L, any(), any()) } returns
                listOf(oldestValue, middleValue, newestValue)
        coEvery { investmentValueDao.getMaxPrice(3L, 0L) } returns 130.0
        coEvery { investmentValueDao.getMinPrice(3L, 0L) } returns 80.0

        val perf = tracker.getInvestmentPerformance(3L)!!

        // Must reflect the NEWEST entry, not the oldest
        assertThat(perf.dayChange).isEqualTo(2.0)
        assertThat(perf.dayChangePercent).isEqualTo(1.7)
        // All-time values must still be from full history
        assertThat(perf.allTimeHigh).isEqualTo(130.0)
        assertThat(perf.allTimeLow).isEqualTo(80.0)
    }

    @Test
    fun `dayChange is null when no in-window values exist`() = runTest {
        val investment = makeInvestment(id = 5L, currentPrice = 120.0, purchasePrice = 100.0)
        coEvery { investmentDao.getById(5L) } returns investment
        coEvery { investmentValueDao.getValuesBetween(5L, any(), any()) } returns emptyList()
        coEvery { investmentValueDao.getMaxPrice(5L, 0L) } returns null
        coEvery { investmentValueDao.getMinPrice(5L, 0L) } returns null

        val perf = tracker.getInvestmentPerformance(5L)!!

        assertThat(perf.dayChange).isNull()
        assertThat(perf.dayChangePercent).isNull()
    }

    @Test
    fun `returns null for nonexistent investment`() = runTest {
        coEvery { investmentDao.getById(999L) } returns null

        val perf = tracker.getInvestmentPerformance(999L)

        assertThat(perf).isNull()
    }

    @Test
    fun `gainLoss calculated correctly`() = runTest {
        val investment = makeInvestment(id = 4L, currentPrice = 150.0, purchasePrice = 100.0, quantity = 2.0)
        coEvery { investmentDao.getById(4L) } returns investment
        coEvery { investmentValueDao.getValuesBetween(4L, any(), any()) } returns emptyList()
        coEvery { investmentValueDao.getMaxPrice(4L, 0L) } returns null
        coEvery { investmentValueDao.getMinPrice(4L, 0L) } returns null

        val perf = tracker.getInvestmentPerformance(4L)!!

        assertThat(perf.currentValue).isEqualTo(300.0)
        assertThat(perf.gainLoss).isEqualTo(100.0) // (150-100)*2
        assertThat(perf.gainLossPercent).isEqualTo(50.0)
    }

    // ---- Helpers ----

    private fun makeInvestment(
        id: Long = 1L,
        currentPrice: Double = 100.0,
        purchasePrice: Double = 100.0,
        quantity: Double = 1.0
    ) = Investment(
        id = id,
        name = "Test",
        symbol = "TST",
        type = InvestmentType.STOCK,
        purchasePrice = purchasePrice,
        quantity = quantity,
        purchaseDate = timeProvider.now() - 365L * 24 * 60 * 60 * 1000,
        currentPrice = currentPrice
    )
}
