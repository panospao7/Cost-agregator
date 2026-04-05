package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BudgetTrendBoundaryTest {

    private val expenseDao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var engine: BudgetForecastingEngine

    private val now = ms(2026, 4, 15)

    @Before
    fun setUp() {
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
    fun `budget_trend_boundary_exactly_10_percent_is_stable`() = runTest {
        val budget = Budget(categoryId = 1L, amount = 10_000.0, period = BudgetPeriod.MONTHLY, startDate = now)

        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns listOf(
            exp("2026-01-10", 100.0),
            exp("2026-02-10", 110.0),
            exp("2026-03-10", 110.0)
        )
        val exactlyPlus10 = engine.generateForecast(budget)

        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns listOf(
            exp("2026-01-10", 100.0),
            exp("2026-02-10", 90.0),
            exp("2026-03-10", 90.0)
        )
        val exactlyMinus10 = engine.generateForecast(budget)

        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns listOf(
            exp("2026-01-10", 100.0),
            exp("2026-02-10", 110.01),
            exp("2026-03-10", 110.01)
        )
        val plus1001 = engine.generateForecast(budget)

        coEvery { expenseDao.getExpensesByCategory(1L, any(), any()) } returns listOf(
            exp("2026-01-10", 100.0),
            exp("2026-02-10", 89.99),
            exp("2026-03-10", 89.99)
        )
        val minus1001 = engine.generateForecast(budget)

        // exactly ±10% should remain STABLE (no 1.1/0.9 multiplier)
        assertApproxEquals((100.0 + 110.0 + 110.0) / 3.0, exactlyPlus10.predictedSpending, 0.0001)
        assertApproxEquals((100.0 + 90.0 + 90.0) / 3.0, exactlyMinus10.predictedSpending, 0.0001)

        // ±10.01% should trigger INCREASING/DECREASING multipliers
        val plus1001Average = (100.0 + 110.01 + 110.01) / 3.0
        val minus1001Average = (100.0 + 89.99 + 89.99) / 3.0
        assertApproxEquals(plus1001Average * 1.1, plus1001.predictedSpending, 0.0001)
        assertApproxEquals(minus1001Average * 0.9, minus1001.predictedSpending, 0.0001)

        assertTrue(plus1001.predictedSpending > plus1001Average)
        assertTrue(minus1001.predictedSpending < minus1001Average)
    }

    private fun exp(isoDate: String, amount: Double) =
        com.yourname.expensetracker.data.database.entity.Expense(
            amount = amount,
            merchant = "M",
            transactionType = TransactionType.PURCHASE,
            categoryId = 1L,
            date = LocalDate.parse(isoDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
