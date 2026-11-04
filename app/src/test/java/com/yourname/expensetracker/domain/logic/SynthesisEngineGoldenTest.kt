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
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SynthesisEngineGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var engine: SynthesisEngine

    @Before
    override fun setUp() {
        super.setUp()
        engine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true))
    }

    @Test
    fun `confidence band thresholds classify recurring patterns into committed likely and excluded with base confidence 0 85`() = runTest {
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
            spendingPace = pace(averageMonthlyTotal = 1400.0),
            displayCurrency = "EUR"
        )

        assertApproxEquals(120.0, forecast.components.totalCommitted, 0.01)
        assertApproxEquals(80.0, forecast.components.totalLikely, 0.01)
        assertApproxEquals(0.85, forecast.confidence, 0.0001)
        assertApproxEquals(ForecastHorizon.REST_OF_MONTH.days.toDouble(), forecast.horizon.days.toDouble(), 0.0)
    }

    @Test
    fun `biweekly recurrence matches exactly 14 days from the anchor and not at 12 13 15 or 16 days`() = runTest {
        // BL-02: the former plus minus 2 day tolerance was removed on purpose (it placed one
        // bill on about 5 days per cycle). Anchor is Sunday 2026-03-01, so offset N is day 1 + N:
        // offset 14 is day 15 (matches); offsets 12, 13, 15, 16 are days 13, 14, 16, 17 (do not).
        // Offsets 0 and 28 (days 1 and 29) match too. Fails on 92346ad0's predecessor: it matched
        // offsets 12 to 16, so days 13, 14, 16 and 17 carried 50.
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

        assertApproxEquals(50.0, blockParty.first { it.dayOfMonth == 15 }.recurringImpact, 0.01)
        listOf(13, 14, 16, 17).forEach { day ->
            assertApproxEquals(0.0, blockParty.first { it.dayOfMonth == day }.recurringImpact, 0.01)
        }
        assertApproxEquals(0.0, blockParty.first { it.dayOfMonth == 18 }.recurringImpact, 0.01)
        // Committed total counts the three remaining occurrences (days 1, 15, 29), unchanged.
        assertApproxEquals(150.0, forecast.components.totalCommitted, 0.01)
    }

    @Test
    fun `biweekly 100 bill is placed once per 14 day cycle in a 31 day month`() = runTest {
        every { timeProvider.now() } returns atTime("2026-03-01", 12, 0, 0)

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                recurring(
                    amount = 100.0,
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

        // Exactly days 1, 15 and 29 carry the bill: 3 cycles x 100. With the tolerance it sat on
        // 13 days (1300), leaving a pool of 700 instead of 1700.
        assertEquals(listOf(1, 15, 29), blockParty.filter { it.recurringImpact > 0.0 }.map { it.dayOfMonth })
        blockParty.filter { it.recurringImpact > 0.0 }.forEach {
            assertApproxEquals(100.0, it.recurringImpact, 0.01)
        }
        assertApproxEquals(300.0, blockParty.sumOf { it.recurringImpact }, 0.01)
        blockParty.forEach { assertApproxEquals(1700.0 / 31.0, it.baseTarget, 0.001) }
        // Targets sum to limit (no reserves, no PAID occurrences).
        assertApproxEquals(2000.0, blockParty.sumOf { it.targetBudget }, 0.01)
        assertApproxEquals(300.0, forecast.components.totalCommitted, 0.01)
    }

    @Test
    fun `block party discretionary base rate follows budget minus recurring planned and strict goal reserves`() = runTest {
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
                    protectionLevel = GoalProtectionLevel.STRICT,
                    currencyAssumption = "EXPLICIT",
                    createdAt = 0L,
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
            percentUsed = 0.0,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = 0L,
            periodEnd = 0L,
            currency = "EUR",
            currencyAssumption = "USER_CONFIRMED",
            effectiveSpend = 0.0,
            effectiveHealthStatus = BudgetHealthStatus.ON_TRACK
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
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
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
