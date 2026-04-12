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

        // average = 200, trend INCREASING => *1.1, no seasonal (<6 months) => 220
        assertApproxEquals(220.0, forecast.predictedSpending, 0.01)
        // stddev(100,200,300)=100, cv=0.5 => confidence=0.5 + 0.25 - 0.1 = 0.65
        assertApproxEquals(0.65, forecast.confidenceScore, 0.01)
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
        assertApproxEquals(0.0325, forecast.overspendProbability, 0.01) // 0.05 * 0.65
    }

    @Test
    fun `single month history yields stable trend and zero stddev path`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-03", 120.0, 1)
        )

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(120.0, forecast.predictedSpending, 0.01)
        assertTrue(forecast.confidenceScore in 0.0..1.0)
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

        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
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

        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
        assertEquals(ForecastRiskLevel.CRITICAL, forecast.riskLevel)
        assertTrue(forecast.overspendProbability in 0.0..1.0)
    }

    @Test
    fun `seasonal_adjustment_uses_timeprovider_not_system_clock`() = runTest {
        // December path must be driven by injected timeProvider.
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

        // STABLE trend + >=6 months history => seasonal factor applies.
        assertApproxEquals(120.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history increasing trend applies increasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 130.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=115, increasing trend => *1.1
        assertApproxEquals(126.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history decreasing trend applies decreasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 130.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=115, decreasing trend => *0.9
        assertApproxEquals(103.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history stable trend keeps base prediction`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 105.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=102.5, stable trend => unchanged
        assertApproxEquals(102.5, forecast.predictedSpending, 0.01)
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

        // Monthly totals: Jan=100, Feb=80, Mar=120 -> avg=100
        // Trend: recent 2-month avg = (80+120)/2 = 100, older avg = 100, ratio = 1.0 -> STABLE
        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
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

        // Monthly totals: Jan=100, Feb=50, Mar=100 -> avg ~ 83.33
        // Trend: recent = (50+100)/2=75, older=100 -> 75 < 100*0.9=90 -> DECREASING -> *0.9
        assertApproxEquals(75.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `historical data excludes isNotMine expenses from totals`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Aggregate SQL already excludes isNotMine (WHERE isNotMine = 0): Jan=60, Mar=60
        // Note: Feb has zero because the isNotMine SQL filter excludes those rows entirely.
        // If only isNotMine rows existed in Feb, SQL returns no row for Feb.
        // Sparse-history parity: no gap-month infill — only 2 data points.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 60.0, 1),
            MonthlySpendingTotal("2026-03", 60.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // 2 months (sparse): Jan=60, Mar=60 -> avg=60
        // Trend (2-month): older=60, recent=60 -> ratio=1.0 -> STABLE
        assertApproxEquals(60.0, forecast.predictedSpending, 0.01)
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

        // Monthly totals: Jan=100, Feb=40, Mar=100 -> avg=80
        // Trend: recent=(40+100)/2=70, older=100 -> 70 < 90 -> DECREASING -> *0.9
        assertApproxEquals(72.0, forecast.predictedSpending, 0.01)
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

        // avg=200, STABLE trend
        assertApproxEquals(200.0, forecast.predictedSpending, 0.01)
    }

    // =========================================================================
    // Sparse-history parity (A.9 regression coverage)
    // Gap months with no qualifying rows are NOT synthesized — only months
    // that the SQL aggregate returns produce buckets, matching pre-A.9
    // grouped-row semantics.
    // =========================================================================

    @Test
    fun `sparse months are used directly without gap infill`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // SQL returns Jan and Mar only; Feb has no qualifying rows — no infill.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 90.0, 1),
            MonthlySpendingTotal("2026-03", 90.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // 2 months (sparse): Jan=90, Mar=90 -> avg=90
        // Trend (2-month): older=90, recent=90 -> ratio=1.0 -> STABLE
        assertApproxEquals(90.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `multi-month gap uses only returned months without infill`() = runTest {
        val budget = Budget(categoryId = null, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // SQL returns Jan and Jun only; Feb-May have no qualifying rows — no infill.
        coEvery { expenseDao.getMonthlySpendingTotalsBetween(any(), any()) } returns listOf(
            MonthlySpendingTotal("2025-01", 120.0, 2),
            MonthlySpendingTotal("2025-06", 120.0, 2)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // 2 months (sparse): Jan=120, Jun=120 -> avg=120
        // Trend (2-month): older=120, recent=120 -> ratio=1.0 -> STABLE
        // <6 months so no seasonal adjustment
        assertApproxEquals(120.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `no gap months leaves existing test semantics unchanged`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        // Contiguous month keys — no infill needed.
        coEvery { expenseDao.getMonthlySpendingTotalsByCategoryBetween(1L, any(), any()) } returns listOf(
            MonthlySpendingTotal("2026-01", 100.0, 1),
            MonthlySpendingTotal("2026-02", 100.0, 1),
            MonthlySpendingTotal("2026-03", 100.0, 1)
        )

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=100, STABLE -> prediction=100 (same as before infill logic)
        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
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
