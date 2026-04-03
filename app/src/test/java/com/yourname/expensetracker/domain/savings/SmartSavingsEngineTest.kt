package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SmartSavingsEngineTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var engine: SmartSavingsEngine

    private val now = LocalDate.of(2026, 4, 15)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        budgetCalculator = mockk(relaxed = true)
        monteCarloSimulator = mockk(relaxed = true)
        savingsGoalRepository = mockk(relaxed = true)

        io.mockk.every { timeProvider.now() } returns now

        engine = SmartSavingsEngine(
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            budgetCalculator = budgetCalculator,
            monteCarloSimulator = monteCarloSimulator,
            savingsGoalRepository = savingsGoalRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `safeToSaveAmount combines weighted surplus pace and monteCarlo`() = runTest {
        val budgetStatuses = listOf(
            budgetStatus(remaining = 100.0),
            budgetStatus(remaining = 200.0),
            budgetStatus(remaining = -20.0)
        )
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(budgetStatuses)

        val monthExpenses = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(
                amount = 50.0,
                merchant = "A",
                transactionType = TransactionType.PURCHASE,
                date = now
            ),
            com.yourname.expensetracker.data.database.entity.Expense(
                amount = 100.0,
                merchant = "B",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        val historicalExpenses = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(amount = 600.0, merchant = "H1", transactionType = TransactionType.PURCHASE, date = now),
            com.yourname.expensetracker.data.database.entity.Expense(amount = 600.0, merchant = "H2", transactionType = TransactionType.PURCHASE, date = now),
            com.yourname.expensetracker.data.database.entity.Expense(amount = 600.0, merchant = "H3", transactionType = TransactionType.PURCHASE, date = now)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            if (start < now - (60L * 24 * 60 * 60 * 1000)) historicalExpenses else monthExpenses
        }

        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns monteCarloResult(400.0)

        val goal = SavingsGoal(name = "Emergency", targetAmount = 5000.0, currentAmount = 1000.0)
        val result = engine.calculateSafeToSaveAmount(goal)

        // surplus = (100 + 200) * 0.5 = 150
        // pace: totalSpent=150, day=15, projected=300, avgMonthly=1800/3=600 => (600-300)*0.3 = 90
        // mc: (500 - 400*0.3) * 0.2 = 76
        // safe = 150*0.4 + 90*0.3 + 76*0.3 = 109.8
        assertApproxEquals(109.8, result.safeAmount, 0.01)
        assertApproxEquals(0.95, result.confidence, 0.001)
    }

    @Test
    fun `no budgets and no spending history return zero safe amount`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns null

        val result = engine.calculateSafeToSaveAmount(
            SavingsGoal(name = "Trip", targetAmount = 1000.0, currentAmount = 0.0)
        )

        assertApproxEquals(0.0, result.safeAmount)
        assertApproxEquals(0.40, result.confidence, 0.001)
    }

    @Test
    fun `very high spending is clamped and never returns negative savings`() = runTest {
        io.mockk.every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(budgetStatus(remaining = -100.0)))
        val veryHigh = listOf(
            com.yourname.expensetracker.data.database.entity.Expense(
                amount = 2000.0,
                merchant = "Big",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns veryHigh
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns monteCarloResult(5000.0)

        val result = engine.calculateSafeToSaveAmount(
            SavingsGoal(name = "Car", targetAmount = 7000.0, currentAmount = 1500.0)
        )

        assertEquals(0.0, result.safeAmount, 0.0)
        assertTrue(result.safeAmount >= 0.0)
    }

    private fun budgetStatus(remaining: Double): BudgetStatus {
        val budgetAmount = if (remaining > 0) remaining + 100 else 100.0
        return BudgetStatus(
            budget = Budget(categoryId = null, amount = budgetAmount, period = BudgetPeriod.MONTHLY, startDate = now),
            category = null,
            spentAmount = (budgetAmount - remaining).coerceAtLeast(0.0),
            remainingAmount = remaining,
            percentUsed = 0.0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = now,
            periodEnd = now
        )
    }

    private fun monteCarloResult(p50: Double) = MonteCarloResult(
        percentile10 = p50,
        percentile25 = p50,
        percentile50 = p50,
        percentile75 = p50,
        percentile90 = p50,
        probabilityUnderBudget = null,
        budgetAmount = null,
        spentToDate = 0.0,
        knownUpcoming = 0.0,
        confidence = SimulationConfidence(0.8, ConfidenceLevel.HIGH, "test"),
        metadata = SimulationMetadata(0, 0, 0, 0.0, 0.0, 0, now)
    )
}
