package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.ForecastHorizon
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import io.mockk.every
import org.junit.Before
import org.junit.Test

class SynthesisEngineGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var engine: SynthesisEngine

    @Before
    override fun setUp() {
        super.setUp()
        engine = SynthesisEngine(timeProvider)
    }

    @Test
    fun `confidence band thresholds classify recurring patterns into committed likely and excluded with base confidence 0 85`() {
        every { timeProvider.now() } returns atTime("2026-03-01", 12, 0, 0)

        val recurring = listOf(
            recurring(amount = 120.0, confidence = 0.95f, date = atTime("2026-03-10", 0, 0, 0), frequency = RecurrenceFrequency.MONTHLY),
            recurring(amount = 80.0, confidence = 0.85f, date = atTime("2026-03-11", 0, 0, 0), frequency = RecurrenceFrequency.MONTHLY),
            recurring(amount = 60.0, confidence = 0.65f, date = atTime("2026-03-12", 0, 0, 0), frequency = RecurrenceFrequency.MONTHLY)
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = recurring,
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(overallBudget(2000.0)),
            spendingPace = pace(averageMonthlyTotal = 1400.0)
        )

        assertApproxEquals(120.0, forecast.components.totalCommitted, 0.01)
        assertApproxEquals(80.0, forecast.components.totalLikely, 0.01)
        assertApproxEquals(0.85, forecast.confidence, 0.0001)
        assertApproxEquals(ForecastHorizon.REST_OF_MONTH.days.toDouble(), forecast.horizon.days.toDouble(), 0.0)
    }

    @Test
    fun `biweekly recurrence matches on days 14 and 16 but not on day 17 with plus minus 2 tolerance`() {
        every { timeProvider.now() } returns atTime("2026-03-01", 12, 0, 0)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                recurring(
                    amount = 50.0,
                    confidence = 0.95f,
                    date = atTime("2026-03-01", 0, 0, 0),
                    frequency = RecurrenceFrequency.BIWEEKLY
                )
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(overallBudget(2000.0)),
            spendingPace = pace(averageMonthlyTotal = 1200.0)
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day15 = blockParty.first { it.dayOfMonth == 15 }
        val day17 = blockParty.first { it.dayOfMonth == 17 }
        val day18 = blockParty.first { it.dayOfMonth == 18 }

        assertApproxEquals(50.0, day15.recurringImpact, 0.01)
        assertApproxEquals(50.0, day17.recurringImpact, 0.01)
        assertApproxEquals(0.0, day18.recurringImpact, 0.01)
    }

    @Test
    fun `block party discretionary base rate follows budget minus recurring planned and strict goal reserves`() {
        every { timeProvider.now() } returns atTime("2026-04-01", 12, 0, 0)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                recurring(
                    amount = 800.0,
                    confidence = 0.95f,
                    date = atTime("2026-04-10", 0, 0, 0),
                    frequency = RecurrenceFrequency.MONTHLY
                )
            ),
            plannedExpenses = listOf(
                PlannedExpense(
                    id = 1L,
                    description = "Must planned",
                    amount = 200.0,
                    date = atTime("2026-04-12", 0, 0, 0),
                    categoryId = null,
                    isRecurring = false,
                    priority = PlannedExpensePriority.MUST
                )
            ),
            savingsGoals = listOf(
                SavingsGoal(
                    id = 1L,
                    name = "Strict reserve",
                    targetAmount = 1000.0,
                    currentAmount = 900.0,
                    targetDate = null,
                    protectionLevel = GoalProtectionLevel.STRICT
                )
            ),
            budgetStatuses = listOf(overallBudget(2000.0)),
            spendingPace = pace(averageMonthlyTotal = 1200.0)
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(30) { 0f },
            budgetLimit = 2000.0
        )

        val day2 = blockParty.first { it.dayOfMonth == 2 }
        val day10 = blockParty.first { it.dayOfMonth == 10 }
        val day12 = blockParty.first { it.dayOfMonth == 12 }

        assertApproxEquals(30.0, day2.targetBudget, 0.01)
        assertApproxEquals(830.0, day10.targetBudget, 0.01)
        assertApproxEquals(230.0, day12.targetBudget, 0.01)
    }

    private fun recurring(
        amount: Double,
        confidence: Float,
        date: Long,
        frequency: RecurrenceFrequency
    ): RecurringPattern {
        return RecurringPattern(
            merchantName = "Recurring",
            averageAmount = amount,
            currency = "EUR",
            frequency = frequency,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = date,
            confidence = confidence,
            previousDates = emptyList()
        )
    }

    private fun overallBudget(amount: Double): BudgetStatusSnapshot {
        return BudgetStatusSnapshot(
            budgetCategoryId = null,
            budgetAmount = amount,
            categoryName = null,
            spentAmount = 0.0,
            remainingAmount = amount,
            percentUsed = 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = 0L,
            periodEnd = 0L
        )
    }

    private fun pace(averageMonthlyTotal: Double?): SpendingPace {
        return SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 1,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = 1200.0,
            averageMonthlyTotal = averageMonthlyTotal,
            pacePercentage = 100.0f,
            paceStatus = PaceStatus.ON_PACE
        )
    }

    private fun atTime(date: String, hour: Int, minute: Int, second: Int): Long {
        val start = com.yourname.expensetracker.dateToMillis(date)
        return java.util.Calendar.getInstance().apply {
            timeInMillis = start
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
