package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class SynthesisEngineTest {

    private val timeProvider = mockk<TimeProvider>()
    private lateinit var engine: SynthesisEngine

    @Before
    fun setup() {
        // Fix time to Jan 15, 2024 (Leap year, 31 days)
        every { timeProvider.now() } returns 1705320000000L
        engine = SynthesisEngine(timeProvider)
    }

    @Test
    fun `synthesize calculates totalCommitted correctly from recurring and planned`() {
        // Arrange
        val recurring = listOf(
            createRecurringPattern(amount = 100.0, confidence = 0.95f, date = 1705392000000L), // Next day
            createRecurringPattern(amount = 50.0, confidence = 0.85f, date = 1705392000000L)   // Likely, not committed
        )
        val planned = listOf(
            createPlannedExpense(amount = 200.0, priority = PlannedExpensePriority.MUST, date = 1705478400000L), // Day 17
            createPlannedExpense(amount = 75.0, priority = PlannedExpensePriority.LIKELY, date = 1705478400000L) // Likely, not committed
        )
        val pace = SpendingPace(
            currentMonthSpent = 1000.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 2000.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE
        )

        // Act
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = recurring,
            plannedExpenses = planned,
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = pace
        )

        // Assert
        // 100 (committed recurring) + 200 (must planned) = 300
        assertEquals(300.0, forecast.components.totalCommitted, 0.01)
        // 50 (likely recurring) + 75 * 0.7 (likely planned weight) = 50 + 52.5 = 102.5
        assertEquals(102.5, forecast.components.totalLikely, 0.01)
    }

    @Test
    fun `synthesize respects strict goal reserves`() {
        // Arrange
        val goals = listOf(
            createSavingsGoal(target = 1000.0, current = 500.0, protection = GoalProtectionLevel.STRICT, targetDate = 1706697600000L), // Feb 1st
            createSavingsGoal(target = 2000.0, current = 1000.0, protection = GoalProtectionLevel.TRACKING, targetDate = 1706697600000L)
        )
        
        val pace = SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 0.0f,
            paceStatus = PaceStatus.ON_PACE
        )

        // Act
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = goals,
            budgetStatuses = emptyList(),
            spendingPace = pace
        )

        // Assert
        assertEquals(500.0, forecast.components.goalReserves, 1.0)
    }

    @Test
    fun `determineRiskLevel returns CRITICAL when budgets are exceeded`() {
        // Arrange
        val budgets = listOf(
            createBudgetStatus(health = BudgetHealthStatus.EXCEEDED)
        )
        val pace = SpendingPace(
            currentMonthSpent = 500.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 1000.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE
        )

        // Act
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = budgets,
            spendingPace = pace
        )

        // Assert
        assertEquals(RiskLevel.CRITICAL, forecast.components.riskLevel)
    }

    @Test
    fun `discretionaryBudget calculation factors in all obligations`() {
        // Arrange
        val budgets = listOf(
            createBudgetStatus(limit = 2000.0, categoryId = null) // Overall budget
        )
        
        val recurring = listOf(createRecurringPattern(100.0, 0.95f, 1705392000000L), createRecurringPattern(50.0, 0.85f, 1705392000000L))
        val planned = listOf(createPlannedExpense(200.0, PlannedExpensePriority.MUST, 1705478400000L), createPlannedExpense(75.0, PlannedExpensePriority.LIKELY, 1705478400000L))
        val goals = listOf(createSavingsGoal(1000.0, 500.0, GoalProtectionLevel.STRICT, 1706697600000L))
        val pace = SpendingPace(
            currentMonthSpent = 500.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 1000.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE
        )

        // Act
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = recurring,
            plannedExpenses = planned,
            savingsGoals = goals,
            budgetStatuses = budgets,
            spendingPace = pace
        )

        // Assert
        // budget: 2000 - spent: 500 - committed: 300 - likely: 102.5 (50 + 52.5) - goals: 500 = 597.5
        assertEquals(597.5, forecast.components.discretionaryBudget, 1.0)
    }

    @Test
    fun `calculateBlockPartyData falls back to expenses when daily history is empty`() {
        val pace = SpendingPace(
            currentMonthSpent = 100.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 200.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 1000.0)),
            spendingPace = pace
        )

        val calendar = Calendar.getInstance().apply { timeInMillis = 1705320000000L } // Jan 15, 2024
        calendar.set(Calendar.DAY_OF_MONTH, 10)
        calendar.set(Calendar.HOUR_OF_DAY, 12)
        val expenseOnDay10 = com.yourname.expensetracker.data.database.entity.Expense(
            amount = 42.0,
            merchant = "Test Merchant",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = calendar.timeInMillis
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(expenseOnDay10),
            dailySpending = emptyList(),
            budgetLimit = 1000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        assertEquals(42.0, day10.actualSpent, 0.01)
        assertTrue(day10.status != BlockPartyStatus.NO_DATA)
    }

    private fun createRecurringPattern(amount: Double, confidence: Float, date: Long) = RecurringPattern(
        merchantName = "Test",
        averageAmount = amount,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = date,
        confidence = confidence,
        previousDates = emptyList()
    )

    private fun createPlannedExpense(amount: Double, priority: PlannedExpensePriority, date: Long) = PlannedExpense(
        id = 0,
        description = "Planned",
        amount = amount,
        date = date,
        categoryId = null,
        isRecurring = false,
        priority = priority
    )

    private fun createSavingsGoal(target: Double, current: Double, protection: GoalProtectionLevel, targetDate: Long?) = SavingsGoal(
        id = 0,
        name = "Goal",
        targetAmount = target,
        currentAmount = current,
        targetDate = targetDate,
        protectionLevel = protection
    )

    private fun createBudgetStatus(health: BudgetHealthStatus = BudgetHealthStatus.ON_TRACK, limit: Double = 1000.0, categoryId: Long? = null) = BudgetStatus(
        budget = com.yourname.expensetracker.data.database.entity.Budget(
            amount = limit,
            categoryId = categoryId,
            period = com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis()
        ),
        category = null,
        spentAmount = 0.0,
        remainingAmount = limit,
        percentUsed = 0.0f,
        healthStatus = health,
        periodStart = 0,
        periodEnd = 0
    )
}
