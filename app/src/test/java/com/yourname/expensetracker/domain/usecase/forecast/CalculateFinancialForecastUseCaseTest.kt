package com.yourname.expensetracker.domain.usecase.forecast

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.ForecastComponents
import com.yourname.expensetracker.domain.model.ForecastHorizon
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Calendar

class CalculateFinancialForecastUseCaseTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var plannedExpenseRepository: PlannedExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var synthesisEngine: SynthesisEngine
    private lateinit var timeProvider: TimeProvider

    private lateinit var useCase: CalculateFinancialForecastUseCase

    @Before
    fun setup() {
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        plannedExpenseRepository = mockk()
        savingsGoalRepository = mockk()
        budgetRepository = mockk()
        synthesisEngine = mockk()
        timeProvider = mockk()

        useCase = CalculateFinancialForecastUseCase(
            expenseRepository = expenseRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            budgetRepository = budgetRepository,
            synthesisEngine = synthesisEngine,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `invoke currentMonthSpent includes only month-to-date purchases owned by user`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val monthStart = ms(2026, Calendar.JANUARY, 1, 0)

        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(
                expense(amount = 50.0, type = TransactionType.PURCHASE, date = monthStart + 2 * DAY_MS),
                expense(amount = 30.0, type = TransactionType.PURCHASE, date = now),
                expense(amount = 1_000.0, type = TransactionType.DEPOSIT, date = monthStart + 3 * DAY_MS),
                expense(amount = 200.0, type = TransactionType.TRANSFER, date = monthStart + 4 * DAY_MS),
                expense(
                    amount = 80.0,
                    type = TransactionType.PURCHASE,
                    date = monthStart + 5 * DAY_MS,
                    isNotMine = true
                ),
                expense(amount = 999.0, type = TransactionType.PURCHASE, date = monthStart - DAY_MS),
                expense(amount = 123.0, type = TransactionType.PURCHASE, date = now + DAY_MS)
            )
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val capturedPace = slot<SpendingPace>()
        every {
            synthesisEngine.synthesize(
                pastSumDaily = any(),
                recurringPatterns = any<List<RecurringPattern>>(),
                plannedExpenses = any(),
                savingsGoals = any(),
                budgetStatuses = any<List<BudgetStatusSnapshot>>(),
                spendingPace = capture(capturedPace)
            )
        } returns dummyForecast(now)

        useCase.invoke().first()

        assertEquals(80.0, capturedPace.captured.currentMonthSpent, 0.0001)
    }

    @Test
    fun `invoke preserves mixed planned expense priorities in synthesis input`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)

        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val must = PlannedExpense(
            id = 1,
            description = "Rent",
            amount = 1200.0,
            date = now + DAY_MS,
            categoryId = null,
            isRecurring = false,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST
        )
        val likely = PlannedExpense(
            id = 2,
            description = "Groceries",
            amount = 200.0,
            date = now + 2 * DAY_MS,
            categoryId = null,
            isRecurring = false,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY
        )
        val optional = PlannedExpense(
            id = 3,
            description = "Headphones",
            amount = 80.0,
            date = now + 3 * DAY_MS,
            categoryId = null,
            isRecurring = false,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(listOf(must, likely, optional))

        val capturedPlanned = slot<List<com.yourname.expensetracker.domain.model.PlannedExpense>>()
        every {
            synthesisEngine.synthesize(
                pastSumDaily = any(),
                recurringPatterns = any<List<RecurringPattern>>(),
                plannedExpenses = capture(capturedPlanned),
                savingsGoals = any(),
                budgetStatuses = any<List<BudgetStatusSnapshot>>(),
                spendingPace = any<SpendingPace>()
            )
        } returns dummyForecast(now)

        useCase.invoke().first()

        val prioritiesById = capturedPlanned.captured.associate { it.id to it.priority }
        assertEquals(PlannedExpensePriority.MUST, prioritiesById[must.id])
        assertEquals(PlannedExpensePriority.LIKELY, prioritiesById[likely.id])
        assertEquals(PlannedExpensePriority.OPTIONAL, prioritiesById[optional.id])
    }

    private fun expense(
        amount: Double,
        type: TransactionType,
        date: Long,
        isNotMine: Boolean = false
    ): Expense {
        return Expense(
            amount = amount,
            merchant = "M",
            transactionType = type,
            date = date,
            isNotMine = isNotMine
        )
    }

    private fun dummyForecast(now: Long): FinancialForecast {
        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.ofEpochMilli(now),
            confidence = 1.0,
            components = ForecastComponents(
                recurringExpenses = emptyList(),
                pastSpendingPoints = emptyList(),
                projectedSpendingPoints = emptyList(),
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0,
                riskLevel = RiskLevel.LOW
            ),
            actionableInsights = emptyList()
        )
    }

    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
