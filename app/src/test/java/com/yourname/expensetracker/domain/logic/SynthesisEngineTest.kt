package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.model.TransactionSummary
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class SynthesisEngineTest : AnalyticsEngineTestBase() {

    private lateinit var engine: SynthesisEngine

    @Before
    override fun setUp() {
        super.setUp()
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
        val expenseOnDay10 = TransactionSummary(
            id = 0,
            amount = 42.0,
            effectiveAmount = 42.0,
            merchant = "Test Merchant",
            date = calendar.timeInMillis,
            categoryId = null
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

    @Test
    fun `synthesize on last day projects zero discretionary days`() {
        every { timeProvider.now() } returns millis(2024, Calendar.JANUARY, 31)
        val engine = SynthesisEngine(timeProvider)

        val pace = SpendingPace(
            currentMonthSpent = 1000.0,
            daysElapsed = 31,
            daysInMonth = 31,
            projectedTotal = 1100.0,
            previousMonthTotal = null,
            averageMonthlyTotal = 310.0,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE
        )

        val forecast = engine.synthesize(
            pastSumDaily = listOf(100.0, 200.0),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = emptyList(),
            spendingPace = pace
        )

        assertEquals(0.0, forecast.components.predictedDiscretionary, 0.0001)
    }

    @Test
    fun `calculateBlockPartyData BIWEEKLY rejects weekly plus seven and matches plus fourteen`() {
        every { timeProvider.now() } returns millis(2024, Calendar.JANUARY, 1)
        val engine = SynthesisEngine(timeProvider)

        val biweekly = createRecurringPattern(
            amount = 100.0,
            confidence = 0.95f,
            date = millis(2024, Calendar.JANUARY, 3),
            frequency = RecurrenceFrequency.BIWEEKLY
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(biweekly),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 2000.0)),
            spendingPace = SpendingPace(
                currentMonthSpent = 0.0,
                daysElapsed = 1,
                daysInMonth = 31,
                projectedTotal = 0.0,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                pacePercentage = 0.0f,
                paceStatus = PaceStatus.ON_PACE
            )
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 } // +7 from Jan 3
        val day17 = blockParty.first { it.dayOfMonth == 17 } // +14 from Jan 3

        assertEquals(0.0, day10.recurringImpact, 0.0001)
        assertEquals(100.0, day17.recurringImpact, 0.0001)
    }

    @Test
    fun `calculateBlockPartyData BIWEEKLY matches across month boundary`() {
        every { timeProvider.now() } returns millis(2024, Calendar.FEBRUARY, 1)
        val engine = SynthesisEngine(timeProvider)

        val biweekly = createRecurringPattern(
            amount = 75.0,
            confidence = 0.95f,
            date = millis(2024, Calendar.JANUARY, 25),
            frequency = RecurrenceFrequency.BIWEEKLY
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(biweekly),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(createBudgetStatus(limit = 1800.0)),
            spendingPace = SpendingPace(
                currentMonthSpent = 0.0,
                daysElapsed = 1,
                daysInMonth = 29,
                projectedTotal = 0.0,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                pacePercentage = 0.0f,
                paceStatus = PaceStatus.ON_PACE
            )
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(29) { 0f },
            budgetLimit = 1800.0
        )

        val day8 = blockParty.first { it.dayOfMonth == 8 } // 14 days after Jan 25
        assertEquals(75.0, day8.recurringImpact, 0.0001)
    }

    @Test
    fun `calculateBlockPartyData fallback actual spend filters to PURCHASE mine-only`() {
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

        val day10Ts = millis(2024, Calendar.JANUARY, 10)
        // Only pass valid PURCHASE mine-only transactions (callers filter before calling calculateBlockPartyData)
        val mixedTransactions = listOf(
            expense(amount = 40.0, date = day10Ts, merchant = "Valid Purchase", isSharedExpense = false)
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = mixedTransactions,
            dailySpending = emptyList(),
            budgetLimit = 1000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        assertEquals(40.0, day10.actualSpent, 0.01)
        assertEquals(1, day10.topTransactions.size)
        assertTrue(day10.status != BlockPartyStatus.NO_DATA)
    }

    private fun createRecurringPattern(
        amount: Double,
        confidence: Float,
        date: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        merchantName: String = "Test"
    ) = RecurringPattern(
        merchantName = merchantName,
        averageAmount = amount,
        currency = "EUR",
        frequency = frequency,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = date,
        confidence = confidence,
        previousDates = emptyList()
    )

    private fun expense(
        amount: Double,
        date: Long,
        merchant: String,
        isSharedExpense: Boolean = false
    ) = TransactionSummary(
        id = 0,
        amount = amount,
        effectiveAmount = amount,
        merchant = merchant,
        date = date,
        categoryId = null,
        isSharedExpense = isSharedExpense
    )

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

    private fun createBudgetStatus(health: BudgetHealthStatus = BudgetHealthStatus.ON_TRACK, limit: Double = 1000.0, categoryId: Long? = null) = BudgetStatusSnapshot(
        budgetCategoryId = categoryId,
        budgetAmount = limit,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = limit,
        percentUsed = 0.0,
        healthStatus = health,
        periodStart = 0,
        periodEnd = 0
    )
}
