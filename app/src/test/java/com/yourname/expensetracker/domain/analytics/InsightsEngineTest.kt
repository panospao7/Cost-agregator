package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException

class InsightsEngineTest {
    private lateinit var engine: InsightsEngine
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val recurringEngine = mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
        val spendingPaceCalculator = mockk<SpendingPaceCalculator>(relaxed = true)
        val anomalyDetector = mockk<AnomalyDetector>(relaxed = true)
        val monthlyComparisonCalculator = mockk<MonthlyComparisonCalculator>(relaxed = true)
        val categoryInsightEngine = mockk<CategoryInsightEngine>(relaxed = true)
        val merchantInsightEngine = mockk<MerchantInsightEngine>(relaxed = true)
        val dayOfWeekAnalyzer = mockk<DayOfWeekAnalyzer>(relaxed = true)
        
        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        every { timeProvider.now() } returns System.currentTimeMillis()
        
        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = anomalyDetector,
            monthlyComparisonCalculator = monthlyComparisonCalculator,
            categoryInsightEngine = categoryInsightEngine,
            merchantInsightEngine = merchantInsightEngine,
            dayOfWeekAnalyzer = dayOfWeekAnalyzer
        )
    }

    private val dayMs = 86_400_000L

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = 0,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * dayMs
    )

    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense("Shop", 10.00, 0),
            makeExpense("Shop", 20.00, 1)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 7)
        assertEquals(7, totals.size)
    }

    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = now),
            Expense(id = 2, amount = 20.0, currency = "EUR", merchant = "B", transactionType = TransactionType.PURCHASE, date = now)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)
        val todayTotal = totals.values.last()
        assertEquals(30.0, todayTotal, 0.01)
    }

    @Test
    fun `buildDailyTotals ignores non-purchase types`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = now),
            Expense(id = 2, amount = 100.0, currency = "EUR", merchant = "B", transactionType = TransactionType.DEPOSIT, date = now)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)
        val todayTotal = totals.values.last()
        assertEquals(10.0, todayTotal, 0.01)
    }

    @Test
    fun `generateInsights propagates CancellationException instead of returning degraded snapshot`() = runTest {
        // Arrange: make a repository call throw CancellationException
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } throws CancellationException("test cancellation")

        val cancelEngine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = mockk(relaxed = true),
            timeProvider = timeProvider,
            spendingPaceCalculator = mockk(relaxed = true),
            anomalyDetector = mockk(relaxed = true),
            monthlyComparisonCalculator = mockk(relaxed = true),
            categoryInsightEngine = mockk(relaxed = true),
            merchantInsightEngine = mockk(relaxed = true),
            dayOfWeekAnalyzer = mockk(relaxed = true)
        )

        // Act + Assert: CancellationException must propagate, not be swallowed
        try {
            cancelEngine.generateInsights(emptyList(), emptyList(), "EUR")
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — cancellation was correctly rethrown
        }
    }
}
