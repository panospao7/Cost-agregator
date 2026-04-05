package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import io.mockk.coEvery
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
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 0.0

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
        val expenses = listOf(
            exp("2026-01-10", 100.0),
            exp("2026-02-10", 200.0),
            exp("2026-03-10", 300.0)
        )
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns expenses

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
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns
            listOf(exp("2026-03-05", 120.0))

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(120.0, forecast.predictedSpending, 0.01)
        assertTrue(forecast.confidenceScore in 0.0..1.0)
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
    }

    @Test
    fun `all months same amount keeps stddev zero and confidence bounded`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 400.0, period = BudgetPeriod.MONTHLY, startDate = now)
        val expenses = listOf(
            exp("2026-01-05", 100.0),
            exp("2026-02-05", 100.0),
            exp("2026-03-05", 100.0)
        )
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns expenses

        val forecast = engine.generateForecast(budget)

        assertApproxEquals(100.0, forecast.predictedSpending, 0.01)
        assertTrue("confidence in [0,1]", forecast.confidenceScore in 0.0..1.0)
        assertEquals(ForecastRiskLevel.LOW, forecast.riskLevel)
    }

    @Test
    fun `budget zero still forecasts history and is critical risk`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 0.0, period = BudgetPeriod.MONTHLY, startDate = now)
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns
            listOf(exp("2026-03-05", 100.0), exp("2026-02-05", 100.0), exp("2026-01-05", 100.0))

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
        val sixMonthsFlat = listOf(
            exp("2026-06-10", 100.0),
            exp("2026-07-10", 100.0),
            exp("2026-08-10", 100.0),
            exp("2026-09-10", 100.0),
            exp("2026-10-10", 100.0),
            exp("2026-11-10", 100.0)
        )
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns sixMonthsFlat

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // STABLE trend + >=6 months history => seasonal factor applies.
        assertApproxEquals(120.0, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history increasing trend applies increasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        val expenses = listOf(
            exp("2026-02-10", 100.0),
            exp("2026-03-10", 130.0)
        )
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns expenses

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=115, increasing trend => *1.1
        assertApproxEquals(126.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history decreasing trend applies decreasing multiplier`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        val expenses = listOf(
            exp("2026-02-10", 130.0),
            exp("2026-03-10", 100.0)
        )
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns expenses

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=115, decreasing trend => *0.9
        assertApproxEquals(103.5, forecast.predictedSpending, 0.01)
    }

    @Test
    fun `two month history stable trend keeps base prediction`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = now)
        val expenses = listOf(
            exp("2026-02-10", 100.0),
            exp("2026-03-10", 105.0)
        )
        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns expenses

        val forecast = engine.generateForecast(budget, forecastPeriodDays = 30)

        // avg=102.5, stable trend => unchanged
        assertApproxEquals(102.5, forecast.predictedSpending, 0.01)
    }

    private fun exp(date: String, amount: Double) =
        com.yourname.expensetracker.data.database.entity.Expense(
            amount = amount,
            merchant = "M",
            transactionType = TransactionType.PURCHASE,
            categoryId = 1L,
            date = LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
}
