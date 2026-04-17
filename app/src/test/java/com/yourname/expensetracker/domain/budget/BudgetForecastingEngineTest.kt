package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.repository.BudgetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class BudgetForecastingEngineTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetForecastDao: BudgetForecastDao
    private lateinit var engine: BudgetForecastingEngine

    private val now = LocalDate.of(2026, 4, 15)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk(relaxed = true)
        budgetForecastDao = mockk(relaxed = true)
        every { timeProvider.now() } returns now
        coEvery { budgetForecastDao.insert(any()) } returns 1L
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 0.0

        // Default: no monthly aggregate data
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(any(), any(), any()) } returns emptyList()
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns emptyList()

        engine = BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `historical average stddev trend and prediction are calculated correctly`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Monthly totals: Jan=100, Feb=200, Mar=300
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 200.0, 1),
            MonthlySpendingTotal("2026-03", 300.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Lookback window is zero-filled through the current month: Jan=100, Feb=200, Mar=300, Apr=0
        // avg=150, older avg=150, recent avg=150 => STABLE, seasonal factor remains neutral
        assertApproxEquals(150.0, forecast.predictedSpending, 0.01)
        // Confidence is driven by completeness (3 observed months out of desired 4) plus variance adjustment
        assertApproxEquals(0.50, forecast.confidenceScore, 0.01)
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
        assertApproxEquals(0.025, forecast.overspendProbability, 0.01) // 0.05 * 0.50
    }

    @Test
    fun `single month history yields stable trend and zero stddev path`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-03", 120.0, 1)
        )

        val forecast = engine.generateForecast(budget)

        // Zero-filled history: Jan=0, Feb=0, Mar=120, Apr=0 => avg=30
        assertApproxEquals(30.0, forecast.predictedSpending, 0.01)
        assertApproxEquals(0.10, forecast.confidenceScore, 0.01)
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
    }

    @Test
    fun `all months same amount keeps stddev zero and confidence bounded`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 400.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget)

        // Zero-filled Apr bucket lowers the average and creates a decreasing recent-vs-older comparison.
        assertApproxEquals(67.5, forecast.predictedSpending, 0.01)
        assertTrue("confidence in [0,1]", forecast.confidenceScore in 0.0..1.0)
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
    }

    @Test
    fun `budget zero still forecasts history and is critical risk`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 0.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(67.5, forecast.predictedSpending, 0.01)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
        assertTrue(forecast.overspendProbability in 0.0..1.0)
    }

    @Test
    fun `seasonal adjustment stays neutral in december`() = runTest {
        // December path must be driven by injected timeProvider, but no month gets a special uplift.
        val decemberNow = LocalDate.of(2026, 12, 15)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        every { timeProvider.now() } returns decemberNow

        val budget = Budget(categoryId = 1L, amount = 2000.0, period = BudgetPeriod.MONTHLY, startDate = decemberNow)
        // 6 months of flat 100 each
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-06", 100.0, 1),
            MonthlySpendingTotal("2026-07", 100.0, 1),
            MonthlySpendingTotal("2026-08", 100.0, 1),
            MonthlySpendingTotal("2026-09", 100.0, 1),
            MonthlySpendingTotal("2026-10", 100.0, 1),
            MonthlySpendingTotal("2026-11", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Lookback is zero-filled through December: Sep=100, Oct=100, Nov=100, Dec=0.
        // The result should not receive any December-specific seasonal multiplier.
        assertApproxEquals(67.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history increasing trend applies increasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 130.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled history: Jan=0, Feb=100, Mar=130, Apr=0 => avg=57.5, increasing trend => *1.1
        assertApproxEquals(63.25, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history decreasing trend applies decreasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 130.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled history: Jan=0, Feb=130, Mar=100, Apr=0 => avg=57.5, decreasing trend => *0.9
        assertApproxEquals(51.75, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history stable trend keeps base prediction`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 105.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled history: Jan=0, Feb=100, Mar=105, Apr=0 => avg=51.25, stable trend => unchanged
        assertApproxEquals(51.25, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `forecast uses remaining active period duration instead of requested approximation`() = runTest {
        val budget = Budget(
            categoryId = 1L,
            amount = 1000.0,
            period = BudgetPeriod.MONTHLY,
            startDate = LocalDate.of(2026, 4, 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 300.0, 1),
            MonthlySpendingTotal("2026-02", 300.0, 1),
            MonthlySpendingTotal("2026-03", 300.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 60)
        val remainingDays = ChronoUnit.DAYS.between(
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2026, 5, 1)
        ).toDouble()

        // Historical months are Jan=300, Feb=300, Mar=300, Apr=0 => avg=225, decreasing trend => *0.9
        assertApproxEquals(225.0 * 0.9 * (remainingDays / 30.0), forecast.predictedSpending, 0.01)
        assertEquals(
            LocalDate.of(2026, 4, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            forecast.targetPeriodStart
        )
        assertEquals(
            LocalDate.of(2026, 5, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            forecast.targetPeriodEnd
        )
    }

    @Test
    fun `projected overspend stays deterministic even with subunit confidence`() = runTest {
        val budget = Budget(
            categoryId = null,
            amount = 100.0,
            period = BudgetPeriod.MONTHLY,
            startDate = LocalDate.of(2026, 4, 1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 50.0, 1),
            MonthlySpendingTotal("2026-03", 200.0, 1)
        )
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 50.0

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(0.30, forecast.confidenceScore, 0.01)
        assertApproxEquals(36.6667, forecast.predictedSpending, 0.01)
        assertTrue(forecast.predictedSpending + 50.0 < budget.amount)
        assertApproxEquals(0.15, forecast.overspendProbability, 0.01)
    }

    @Test
    fun `calendar yearly budgets forecast against remaining calendar year window`() = runTest {
        val budget = Budget(
            categoryId = 1L,
            amount = 5_000.0,
            period = BudgetPeriod.YEARLY,
            periodMode = "CALENDAR",
            startDate = LocalDate.of(2023, 8, 20)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget)
        val yearStart = LocalDate.of(2026, 1, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val yearEnd = LocalDate.of(2027, 1, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val remainingDays = ChronoUnit.DAYS.between(
            LocalDate.of(2026, 4, 15),
            LocalDate.of(2027, 1, 1)
        ).toDouble()

        assertEquals(yearStart, forecast.targetPeriodStart)
        assertEquals(yearEnd, forecast.targetPeriodEnd)
        // Historical months are Jan=100, Feb=100, Mar=100, Apr=0 => avg=75, decreasing trend => *0.9
        assertApproxEquals(75.0 * 0.9 * (remainingDays / 30.0), forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // A.1 effectiveAmount regression — aggregate SQL now handles effective-amount
    // computation in the DAO layer.  These tests verify the engine correctly
    // consumes pre-aggregated monthly totals that already reflect effective-amount
    // semantics (shared expenses, isNotMine, percentage shares).
    // =========================================================================

    @Test
    fun `historical data uses effectiveAmount for shared expenses not raw amount`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Aggregate SQL returns effective amounts: Jan=100, Feb=80 (not raw 200), Mar=120
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 80.0, 1),
            MonthlySpendingTotal("2026-03", 120.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=100, Feb=80, Mar=120, Apr=0 -> avg=75
        // older=90, recent=60 -> DECREASING -> *0.9
        assertApproxEquals(67.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `historical data uses effectiveAmount for percentage shared expenses`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Aggregate SQL returns effective amounts: Jan=100, Feb=50 (not raw 100), Mar=100
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 50.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=100, Feb=50, Mar=100, Apr=0 -> avg=62.5
        // older=75, recent=50 -> DECREASING -> *0.9
        assertApproxEquals(56.25, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `historical data excludes isNotMine expenses and zero fills missing months`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Aggregate SQL already excludes isNotMine (WHERE isNotMine = 0): Jan=60, Mar=60
        // Missing months are now synthesized as zero-spend buckets.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 60.0, 1),
            MonthlySpendingTotal("2026-03", 60.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Jan=60, Feb=0, Mar=60, Apr=0 -> avg=30, stable trend
        assertApproxEquals(30.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `mixed shared and isNotMine with regular expenses forecast correctly`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Aggregate SQL computes: Jan=100 (regular 100 + isNotMine excluded), Feb=40 (shared), Mar=100
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 40.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=100, Feb=40, Mar=100, Apr=0 -> avg=60
        // older=70, recent=50 -> DECREASING -> *0.9
        assertApproxEquals(54.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // Null-category budget path (uses getMonthlySpendingTotalsBetween)
    // =========================================================================

    @Test
    fun `null category budget uses uncapped monthly aggregate without category filter`() = runTest {
        val budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 200.0, 5),
            MonthlySpendingTotal("2026-02", 200.0, 5),
            MonthlySpendingTotal("2026-03", 200.0, 5)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Zero-filled Apr bucket: Jan=200, Feb=200, Mar=200, Apr=0 -> avg=150, decreasing trend => *0.9
        assertApproxEquals(135.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // Sparse-history gap filling coverage
    // Gap months with no qualifying rows are synthesized as explicit zero-spend
    // buckets before averages and trends are calculated.
    // =========================================================================

    @Test
    fun `sparse months are zero filled before averaging`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // SQL returns Jan and Mar only; Feb and Apr are synthesized as zeros.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 90.0, 1),
            MonthlySpendingTotal("2026-03", 90.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Jan=90, Feb=0, Mar=90, Apr=0 -> avg=45, stable trend
        assertApproxEquals(45.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `multi-month gaps outside returned data collapse to zero-filled lookback window`() = runTest {
        val budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Returned months outside the Jan-Apr lookback do not contribute; the full lookback is zero-filled.
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns listOf(
            MonthlySpendingTotal("2025-01", 120.0, 2),
            MonthlySpendingTotal("2025-06", 120.0, 2)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        assertApproxEquals(0.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `contiguous observed months still include zero filled current month`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Contiguous month keys still get the zero-filled current-month bucket.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // Jan=100, Feb=100, Mar=100, Apr=0 -> avg=75, decreasing trend => *0.9
        assertApproxEquals(67.5, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // B7: Transactional deactivate+insert (unique-index safety)
    // =========================================================================

    @Test
    fun `generateForecast calls insertWithDeactivation not plain insert`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        engine.generateForecast(budget)

        coVerify(exactly = 1) { budgetForecastDao.insertWithDeactivation(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
    }

    @Test
    fun `regenerating forecast for same period deactivates previous via DAO`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        // Generate twice for the same period
        engine.generateForecast(budget)
        engine.generateForecast(budget)

        coVerify(exactly = 2) { budgetForecastDao.insertWithDeactivation(any()) }
        coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
    }
}
