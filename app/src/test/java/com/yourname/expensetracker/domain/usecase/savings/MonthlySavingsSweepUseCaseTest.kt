package com.yourname.expensetracker.domain.usecase.savings

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class MonthlySavingsSweepUseCaseTest {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var plannedExpenseRepository: PlannedExpenseRepository
    private lateinit var monteCarloSimulator: MonteCarloSpendingSimulator
    private lateinit var timeProvider: TimeProvider
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository

    private lateinit var useCase: MonthlySavingsSweepUseCase

    private val dayMs = 24L * 60L * 60L * 1000L
    private val withinWindowNow = millis(2026, Calendar.JANUARY, 29) // last 5 days of Jan (31 days)

    @Before
    fun setup() {
        budgetRepository = mockk()
        savingsGoalRepository = mockk()
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        plannedExpenseRepository = mockk()
        monteCarloSimulator = mockk()
        timeProvider = mockk()
        analyticsCurrencyNormalizer = mockk()
        currencySettingsRepository = mockk()

        every { timeProvider.now() } returns withinWindowNow
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns monteCarlo(p50 = 100.0, p75 = 120.0, confidence = 0.9)

        useCase = MonthlySavingsSweepUseCase(
            budgetRepository = budgetRepository,
            savingsGoalRepository = savingsGoalRepository,
            expenseRepository = expenseRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository
        )
    }

    @Test
    fun `computeSweepRecommendation avoids double counting when overall budget exists`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 900.0), // remaining 100
                budgetStatus(budgetId = 2L, categoryId = 1L, amount = 600.0, spent = 520.0),     // remaining 80
                budgetStatus(budgetId = 3L, categoryId = 2L, amount = 500.0, spent = 440.0)      // remaining 60
            )
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(
            listOf(goal(1L, "Emergency", target = 1000.0, current = 100.0))
        )

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        // Must use only overall remaining (100), not 100+80+60.
        assertApproxEquals(100.0, result!!.totalUnderspend, 0.01)
        assertApproxEquals(20.0, result.riskBuffer, 0.01)
        assertApproxEquals(80.0, result.safeSweepAmount, 0.01)
    }

    @Test
    fun `computeSweepRecommendation filters invalid goals targetAmount less or equal zero preventing NaN`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 500.0, spent = 300.0))
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(
            listOf(
                goal(1L, "InvalidZero", target = 0.0, current = 0.0),
                goal(2L, "InvalidNegative", target = -100.0, current = 10.0)
            )
        )

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation allocates proportionally to urgency`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 880.0)) // underspend 120
        )
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns monteCarlo(
            p50 = 50.0,
            p75 = 70.0,
            confidence = 0.9
        ) // p75-p50=20 but capped at 30% of p50 => risk buffer 15, safe sweep 105
        every { savingsGoalRepository.getAllGoals() } returns flowOf(
            listOf(
                goal(1L, "GoalA", target = 1000.0, current = 400.0), // urgency 0.6
                goal(2L, "GoalB", target = 1000.0, current = 600.0)  // urgency 0.4
            )
        )

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        val byId = result!!.goalAllocations.associateBy { it.goalId }
        assertApproxEquals(105.0, result.safeSweepAmount, 0.01)
        assertApproxEquals(63.0, byId.getValue(1L).suggestedAllocation, 0.01)
        assertApproxEquals(42.0, byId.getValue(2L).suggestedAllocation, 0.01)
        assertApproxEquals(0.6, byId.getValue(1L).allocationPercentage, 0.01)
        assertApproxEquals(0.4, byId.getValue(2L).allocationPercentage, 0.01)
    }

    @Test
    fun `shouldShowSweepPrompt true only in last five days of month`() {
        every { timeProvider.now() } returns millis(2026, Calendar.JANUARY, 26)
        assertEquals(false, useCase.shouldShowSweepPrompt())

        every { timeProvider.now() } returns millis(2026, Calendar.JANUARY, 27)
        assertEquals(true, useCase.shouldShowSweepPrompt())
    }

    @Test
    fun `computeSweepRecommendation edge case no budgets returns null`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation edge case no goals returns null`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 800.0))
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation edge case negative underspend returns null`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(budgetId = 1L, categoryId = 1L, amount = 200.0, spent = 250.0),
                budgetStatus(budgetId = 2L, categoryId = 2L, amount = 300.0, spent = 330.0)
            )
        )

        val result = useCase.computeSweepRecommendation()

        assertNull(result)
    }

    @Test
    fun `computeSweepRecommendation passes real upcoming obligations into Monte Carlo`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 700.0))
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(listOf(goal(1L, "Emergency", 1000.0, 100.0)))
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(
            listOf(
                ManualRecurringExpense(
                    id = 1L,
                    merchant = "Rent",
                    amount = 120.0,
                    currency = "EUR",
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = withinWindowNow + dayMs,
                    note = null
                )
            )
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(
            listOf(
                PlannedExpense(
                    id = 2L,
                    description = "Insurance",
                    amount = 80.0,
                    date = withinWindowNow + 2 * dayMs,
                    categoryId = null,
                    isRecurring = false,
                    priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST
                )
            )
        )

        var knownUpcoming = -1.0
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } answers {
            knownUpcoming = secondArg()
            monteCarlo(p50 = 100.0, p75 = 120.0, confidence = 0.9)
        }

        useCase.computeSweepRecommendation()

        assertApproxEquals(200.0, knownUpcoming, 0.01)
    }

    @Test
    fun `computeSweepRecommendation derives deterministic fallback risk buffer when Monte Carlo unavailable`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 700.0))
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(
            listOf(goal(1L, "Emergency", target = 1000.0, current = 100.0))
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            com.yourname.expensetracker.data.database.entity.Expense(
                amount = 300.0,
                merchant = "Groceries",
                transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                date = withinWindowNow - dayMs
            )
        )
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(
            listOf(
                ManualRecurringExpense(
                    id = 1L,
                    merchant = "Rent",
                    amount = 80.0,
                    currency = "EUR",
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = withinWindowNow + dayMs,
                    note = null
                )
            )
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns null

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        assertApproxEquals(120.0, result!!.riskBuffer, 0.01)
        assertApproxEquals(180.0, result.safeSweepAmount, 0.01)
    }

    @Test
    fun `computeSweepRecommendation caps allocations by remaining goal gap before concentration cap`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(budgetId = 1L, categoryId = null, amount = 1000.0, spent = 800.0))
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(
            listOf(
                goal(1L, "Almost Done", target = 100.0, current = 90.0),
                goal(2L, "Large Goal", target = 1000.0, current = 100.0)
            )
        )
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns monteCarlo(
            p50 = 50.0,
            p75 = 50.0,
            confidence = 0.9
        )

        val result = useCase.computeSweepRecommendation()

        assertNotNull(result)
        val byId = result!!.goalAllocations.associateBy { it.goalId }
        assertApproxEquals(10.0, byId.getValue(1L).suggestedAllocation, 0.01)
        assertTrue(byId.getValue(2L).suggestedAllocation <= result.safeSweepAmount * MonthlySavingsSweepUseCase.MAX_SINGLE_ALLOCATION_PERCENT + 0.01)
        assertTrue(byId.getValue(2L).suggestedAllocation <= 900.0)
    }

    private fun budgetStatus(
        budgetId: Long,
        categoryId: Long?,
        amount: Double,
        spent: Double
    ): BudgetStatus {
        val budget = Budget(
            id = budgetId,
            categoryId = categoryId,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = withinWindowNow - 20 * dayMs
        )
        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = spent,
            remainingAmount = amount - spent,
            percentUsed = if (amount > 0) (spent / amount).toFloat() else 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = withinWindowNow - 20 * dayMs,
            periodEnd = withinWindowNow + 10 * dayMs
        )
    }

    private fun goal(id: Long, name: String, target: Double, current: Double): SavingsGoal {
        return SavingsGoal(
            id = id,
            name = name,
            targetAmount = target,
            currentAmount = current,
            targetDate = null,
            protectionLevel = GoalProtectionLevel.WARNING,
            createdAt = withinWindowNow - dayMs
        )
    }

    private fun monteCarlo(p50: Double, p75: Double, confidence: Double): MonteCarloResult {
        return MonteCarloResult(
            percentile10 = p50 - 20,
            percentile25 = p50 - 10,
            percentile50 = p50,
            percentile75 = p75,
            percentile90 = p75 + 20,
            probabilityUnderBudget = 0.7,
            budgetAmount = 1000.0,
            spentToDate = 0.0,
            knownUpcoming = 0.0,
            confidence = SimulationConfidence(
                score = confidence,
                level = ConfidenceLevel.HIGH,
                reason = "test"
            ),
            metadata = SimulationMetadata(
                qualifyingWeeks = 10,
                totalWeeksExamined = 12,
                iterations = 1000,
                logNormalMu = 0.0,
                logNormalSigma = 1.0,
                daysRemaining = 2,
                computedAt = withinWindowNow
            ),
            displayCurrency = "EUR"
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
