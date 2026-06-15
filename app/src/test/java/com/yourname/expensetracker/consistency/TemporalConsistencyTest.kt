package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class TemporalConsistencyTest : AnalyticsEngineTestBase() {

    @Test
    fun `budget and spending pace share same March 2026 boundaries`() {
        withDefaultTimeZone("Europe/Athens") {
            val fakeTime = FakeTimeProvider.forDate(2026, 3, 15, 12, 0)
            val budgetCalculator = BudgetCalculator(fakeTime)
            val spendingPaceCalculator = SpendingPaceCalculator(fakeTime)

            val marchAnchor = FakeTimeProvider.forDate(2026, 3, 1).now()
            val budgetWindow = budgetCalculator.calculatePeriodWindowForTime(
                period = BudgetPeriod.MONTHLY,
                anchorDate = marchAnchor,
                evaluationTime = fakeTime.now()
            )

            val currentMonthStart = TimePeriodUtils.getStartOfMonth(fakeTime.now())
            val currentMonthEnd = TimePeriodUtils.getEndOfMonth(currentMonthStart)
            val previousMonthStart = TimePeriodUtils.getStartOfMonth(FakeTimeProvider.forDate(2026, 2, 1).now())
            val previousMonthEnd = TimePeriodUtils.getEndOfMonth(previousMonthStart)

            assertEquals(currentMonthStart, budgetWindow.startInclusiveMillis)
            assertEquals(currentMonthEnd, budgetWindow.endExclusiveMillis)

            val pace = spendingPaceCalculator.calculate(
                currentMonthStart = currentMonthStart,
                previousMonthStart = previousMonthStart,
                previousMonthEnd = previousMonthEnd,
                allExpenses = listOf(
                    createExpense("2026-03-01", 100.0),
                    createExpense("2026-03-15", 50.0),
                    createExpense("2026-02-10", 280.0)
                ).toExpenseSnapshots(),
                displayCurrency = "EUR"
            )

            assertEquals(15, pace.daysElapsed)
            assertEquals(31, pace.daysInMonth)
            assertApproxEquals(150.0, pace.currentMonthSpent, 0.01)
        }
    }

    @Test
    fun `DST transition Athens March 29 2026 keeps monthly period boundaries correct`() {
        withDefaultTimeZone("Europe/Athens") {
            val fakeTime = FakeTimeProvider.forDate(2026, 3, 29, 12, 0)
            val budgetCalculator = BudgetCalculator(fakeTime)
            val spendingPaceCalculator = SpendingPaceCalculator(fakeTime)

            val marchAnchor = FakeTimeProvider.forDate(2026, 3, 1).now()
            val budgetWindow = budgetCalculator.calculatePeriodWindowForTime(
                period = BudgetPeriod.MONTHLY,
                anchorDate = marchAnchor,
                evaluationTime = fakeTime.now()
            )

            val currentMonthStart = TimePeriodUtils.getStartOfMonth(fakeTime.now())
            val currentMonthEnd = TimePeriodUtils.getEndOfMonth(currentMonthStart)
            val previousMonthStart = TimePeriodUtils.getStartOfMonth(FakeTimeProvider.forDate(2026, 2, 1).now())

            assertEquals(currentMonthStart, budgetWindow.startInclusiveMillis)
            assertEquals(currentMonthEnd, budgetWindow.endExclusiveMillis)
            assertEquals(31, TimePeriodUtils.daysBetween(budgetWindow.startInclusiveMillis, budgetWindow.endExclusiveMillis))

            val pace = spendingPaceCalculator.calculate(
                currentMonthStart = currentMonthStart,
                previousMonthStart = previousMonthStart,
                previousMonthEnd = currentMonthStart,
                allExpenses = listOf(
                    createExpense("2026-03-28", 40.0),
                    createExpense("2026-03-29", 60.0),
                    createExpense("2026-02-14", 280.0)
                ).toExpenseSnapshots(),
                displayCurrency = "EUR"
            )

            val expectedDaysElapsed = TimePeriodUtils.daysBetween(currentMonthStart, fakeTime.now()) + 1
            assertEquals(expectedDaysElapsed, pace.daysElapsed)
            assertEquals(31, pace.daysInMonth)
            assertApproxEquals(100.0, pace.currentMonthSpent, 0.01)
        }
    }

    @Test
    fun `leap year February 2024 period calculations stay correct`() {
        withDefaultTimeZone("Europe/Athens") {
            val fakeTime = FakeTimeProvider.forDate(2024, 2, 29, 12, 0)
            val budgetCalculator = BudgetCalculator(fakeTime)
            val spendingPaceCalculator = SpendingPaceCalculator(fakeTime)

            val febAnchor = FakeTimeProvider.forDate(2024, 2, 1).now()
            val budgetWindow = budgetCalculator.calculatePeriodWindowForTime(
                period = BudgetPeriod.MONTHLY,
                anchorDate = febAnchor,
                evaluationTime = fakeTime.now()
            )

            val currentMonthStart = TimePeriodUtils.getStartOfMonth(fakeTime.now())
            val currentMonthEnd = TimePeriodUtils.getEndOfMonth(currentMonthStart)
            val previousMonthStart = TimePeriodUtils.getStartOfMonth(FakeTimeProvider.forDate(2024, 1, 1).now())

            assertEquals(currentMonthStart, budgetWindow.startInclusiveMillis)
            assertEquals(currentMonthEnd, budgetWindow.endExclusiveMillis)
            assertEquals(29, TimePeriodUtils.daysBetween(budgetWindow.startInclusiveMillis, budgetWindow.endExclusiveMillis))

            val pace = spendingPaceCalculator.calculate(
                currentMonthStart = currentMonthStart,
                previousMonthStart = previousMonthStart,
                previousMonthEnd = currentMonthStart,
                allExpenses = listOf(
                    createExpense("2024-02-01", 100.0),
                    createExpense("2024-02-29", 25.0),
                    createExpense("2024-01-10", 310.0)
                ).toExpenseSnapshots(),
                displayCurrency = "EUR"
            )

            assertEquals(29, pace.daysInMonth)
            assertEquals(29, pace.daysElapsed)
            assertApproxEquals(125.0, pace.currentMonthSpent, 0.01)
        }
    }

    @Test
    fun `empty period mode fallback is consistent with empty spending baseline`() {
        withDefaultTimeZone("Europe/Athens") {
            val fakeTime = FakeTimeProvider.forDate(2026, 3, 10, 9, 0)
            val budgetCalculator = BudgetCalculator(fakeTime)
            val spendingPaceCalculator = SpendingPaceCalculator(fakeTime)

            val budget = Budget(
                id = 1L,
                categoryId = null,
                amount = 500.0,
                period = BudgetPeriod.MONTHLY,
                periodMode = "",
                startDate = FakeTimeProvider.forDate(2026, 1, 1).now()
            )

            val (budgetStart, budgetEnd) = budgetCalculator.calculatePeriodRange(budget, fakeTime.now())
            val expectedStart = TimePeriodUtils.getStartOfMonth(fakeTime.now())
            val expectedEnd = TimePeriodUtils.getEndOfMonth(expectedStart)
            val previousMonthStart = TimePeriodUtils.getStartOfMonth(FakeTimeProvider.forDate(2026, 2, 1).now())

            assertEquals(expectedStart, budgetStart)
            assertEquals(expectedEnd, budgetEnd)

            val pace = spendingPaceCalculator.calculate(
                currentMonthStart = expectedStart,
                previousMonthStart = previousMonthStart,
                previousMonthEnd = expectedStart,
                allExpenses = emptyList(),
                displayCurrency = "EUR"
            )

            assertApproxEquals(0.0, pace.currentMonthSpent, 0.0)
            assertApproxEquals(0.0, pace.projectedTotal, 0.0)
            assertEquals(PaceStatus.NO_BASELINE, pace.paceStatus)
            assertNull(pace.previousMonthTotal)
        }
    }

    private fun <T> withDefaultTimeZone(zoneId: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        return try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
