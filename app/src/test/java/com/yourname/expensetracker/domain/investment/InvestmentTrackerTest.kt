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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
        tracker = InvestmentTracker(
            database = mockk(relaxed = true),
            investmentDao = investmentDao,
            investmentValueDao = investmentValueDao,
            investmentTransactionDao = mockk(relaxed = true),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            currencySettingsRepository = mockk(relaxed = true),
            writeBarrier = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    // ---- All-time high/low correctness ----

    @Test
    fun `allTimeHigh and allTimeLow query from epoch 0, not 30-day window`() = runTest {
        val investment = makeInvestment(id = 1L, currentPrice = 150.0, purchasePrice = 100.0)
        coEvery { investmentDao.getById(1L) } returns investment
        coEvery { investmentValueDao.getLatestValueBefore(1L, any()) } returns null
        coEvery { investmentValueDao.getLatestValue(1L) } returns null
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
        coEvery { investmentValueDao.getLatestValueBefore(2L, any()) } returns null
        coEvery { investmentValueDao.getLatestValue(2L) } returns null
        coEvery { investmentValueDao.getMaxPrice(2L, 0L) } returns null
        coEvery { investmentValueDao.getMinPrice(2L, 0L) } returns null

        val perf = tracker.getInvestmentPerformance(2L)!!

        assertThat(perf.allTimeHigh).isNull()
        assertThat(perf.allTimeLow).isNull()
    }

    @Test
    fun `dayChange uses previous day close snapshot`() = runTest {
        val investment = makeInvestment(id = 3L, currentPrice = 120.0, purchasePrice = 100.0)
        coEvery { investmentDao.getById(3L) } returns investment

        val now = timeProvider.now()
        val previousDayClose = InvestmentValue(
            id = 12,
            investmentId = 3L,
            price = 118.0,
            totalValue = 118.0,
            timestamp = now - 86_400_000L,
            dayChange = 2.0,
            dayChangePercent = 1.7
        )
        coEvery { investmentValueDao.getLatestValueBefore(3L, any()) } returns previousDayClose
        coEvery { investmentValueDao.getLatestValue(3L) } returns previousDayClose
        coEvery { investmentValueDao.getMaxPrice(3L, 0L) } returns 130.0
        coEvery { investmentValueDao.getMinPrice(3L, 0L) } returns 80.0

        val perf = tracker.getInvestmentPerformance(3L)!!

        assertThat(perf.dayChange).isEqualTo(2.0)
        assertThat(perf.dayChangePercent).isWithin(0.0001).of((2.0 / 118.0) * 100)
        assertThat(perf.allTimeHigh).isEqualTo(130.0)
        assertThat(perf.allTimeLow).isEqualTo(80.0)
        coVerify { investmentValueDao.getLatestValueBefore(3L, any()) }
    }

    @Test
    fun `dayChange is null when no in-window values exist`() = runTest {
        val investment = makeInvestment(id = 5L, currentPrice = 120.0, purchasePrice = 100.0)
        coEvery { investmentDao.getById(5L) } returns investment
        coEvery { investmentValueDao.getLatestValueBefore(5L, any()) } returns null
        coEvery { investmentValueDao.getLatestValue(5L) } returns null
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
        coEvery { investmentValueDao.getLatestValueBefore(4L, any()) } returns null
        coEvery { investmentValueDao.getLatestValue(4L) } returns null
        coEvery { investmentValueDao.getMaxPrice(4L, 0L) } returns null
        coEvery { investmentValueDao.getMinPrice(4L, 0L) } returns null

        val perf = tracker.getInvestmentPerformance(4L)!!

        assertThat(perf.currentValue).isEqualTo(300.0)
        assertThat(perf.gainLoss).isEqualTo(100.0) // (150-100)*2
        assertThat(perf.gainLossPercent).isEqualTo(50.0)
    }

    @Test
    fun `gainLoss includes purchase fees in investment performance`() = runTest {
        val investment = makeInvestment(
            id = 6L,
            currentPrice = 150.0,
            purchasePrice = 100.0,
            quantity = 2.0,
            purchaseFees = 10.0
        )
        coEvery { investmentDao.getById(6L) } returns investment
        coEvery { investmentValueDao.getLatestValueBefore(6L, any()) } returns null
        coEvery { investmentValueDao.getLatestValue(6L) } returns null
        coEvery { investmentValueDao.getMaxPrice(6L, 0L) } returns null
        coEvery { investmentValueDao.getMinPrice(6L, 0L) } returns null

        val perf = tracker.getInvestmentPerformance(6L)!!

        assertThat(perf.currentValue).isEqualTo(300.0)
        assertThat(perf.gainLoss).isEqualTo(90.0)
        assertThat(perf.gainLossPercent).isWithin(0.0001).of(42.857142857142854)
    }

    @Test
    fun `portfolio summary includes purchase fees in total invested and gain loss`() = runTest {
        val investments = listOf(
            makeInvestment(
                id = 7L,
                currentPrice = 150.0,
                purchasePrice = 100.0,
                quantity = 2.0,
                purchaseFees = 10.0
            ),
            makeInvestment(
                id = 8L,
                currentPrice = 75.0,
                purchasePrice = 50.0,
                quantity = 1.0,
                purchaseFees = 5.0
            )
        )
        every { investmentDao.getAllActiveInvestments() } returns flowOf(investments)

        val summary = tracker.getPortfolioSummary()

        assertThat(summary.totalValue).isEqualTo(375.0)
        assertThat(summary.totalInvested).isEqualTo(265.0)
        assertThat(summary.totalGainLoss).isEqualTo(110.0)
        assertThat(summary.totalGainLossPercent).isWithin(0.0001).of(41.509433962264154)
    }

    @Test
    fun `portfolio history collapses same-day snapshots to latest value per investment`() = runTest {
        val investmentA = makeInvestment(id = 9L)
        val investmentB = makeInvestment(id = 10L)
        coEvery { investmentDao.getAllInvestments() } returns listOf(investmentA, investmentB)

        coEvery { investmentValueDao.getPortfolioHistoryBatch(listOf(9L, 10L), any(), any()) } returns listOf(
            InvestmentValue(
                id = 20L,
                investmentId = 9L,
                price = 100.0,
                totalValue = 100.0,
                timestamp = 1_700_000_000_000L,
                dayChange = 0.0,
                dayChangePercent = 0.0
            ),
            InvestmentValue(
                id = 21L,
                investmentId = 9L,
                price = 120.0,
                totalValue = 120.0,
                timestamp = 1_700_000_000_000L + 3_600_000L,
                dayChange = 20.0,
                dayChangePercent = 20.0
            ),
            InvestmentValue(
                id = 22L,
                investmentId = 10L,
                price = 50.0,
                totalValue = 50.0,
                timestamp = 1_700_000_000_000L + 1_800_000L,
                dayChange = 0.0,
                dayChangePercent = 0.0
            )
        )

        val history = tracker.getPortfolioValueHistory(days = 30)

        assertThat(history.values.size).isEqualTo(1)
        assertThat(history.values.single().totalValue).isEqualTo(170.0)
        coVerify(exactly = 1) { investmentValueDao.getPortfolioHistoryBatch(listOf(9L, 10L), any(), any()) }
    }

    // ---- Helpers ----

    private fun makeInvestment(
        id: Long = 1L,
        currentPrice: Double = 100.0,
        purchasePrice: Double = 100.0,
        quantity: Double = 1.0,
        purchaseFees: Double = 0.0
    ) = Investment(
        id = id,
        name = "Test",
        symbol = "TST",
        type = InvestmentType.STOCK,
        purchasePrice = purchasePrice,
        quantity = quantity,
        purchaseDate = timeProvider.now() - 365L * 24 * 60 * 60 * 1000,
        purchaseFees = purchaseFees,
        currentPrice = currentPrice
    )
}
