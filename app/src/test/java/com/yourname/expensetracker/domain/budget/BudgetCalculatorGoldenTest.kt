package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import io.mockk.every
import org.junit.Before
import org.junit.Test

class BudgetCalculatorGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var calculator: BudgetCalculator

    @Before
    override fun setUp() {
        super.setUp()
        calculator = BudgetCalculator(timeProvider)
    }

    @Test
    fun `monthly calendar mode on march 15 returns march 1 to april 1 exclusive range`() {
        val now = atTime("2026-03-15", 14, 0, 0)
        every { timeProvider.now() } returns now
        val budget = budget(periodMode = "CALENDAR", period = BudgetPeriod.MONTHLY, startDate = atTime("2026-02-10", 0, 0, 0))

        val (start, end) = calculator.calculatePeriodRange(budget)

        assertApproxEquals(com.yourname.expensetracker.startOfMonth(2026, 3).toDouble(), start.toDouble(), 0.0)
        assertApproxEquals(com.yourname.expensetracker.startOfMonth(2026, 4).toDouble(), end.toDouble(), 0.0)
    }

    @Test
    fun `rolling monthly mode resolves active anchored cycle via calendar month math`() {
        val now = atTime("2026-03-05", 12, 0, 0)
        every { timeProvider.now() } returns now
        val startDate = atTime("2026-02-10", 0, 0, 0)
        val budget = budget(periodMode = "ROLLING", period = BudgetPeriod.MONTHLY, startDate = startDate)

        val (start, end) = calculator.calculatePeriodRange(budget)

        // Anchor day is 10. March 5 < 10, so period started in previous month: Feb 10.
        // Period end is March 10 (anchor day in next month).
        assertApproxEquals(atTime("2026-02-10", 0, 0, 0).toDouble(), start.toDouble(), 0.0)
        assertApproxEquals(atTime("2026-03-10", 0, 0, 0).toDouble(), end.toDouble(), 0.0)
    }

    @Test
    fun `yearly anniversary on march 15 advances window to current year start`() {
        val now = atTime("2026-03-15", 12, 0, 0)
        every { timeProvider.now() } returns now
        val anchor = atTime("2025-03-15", 0, 0, 0)
        val budget = budget(periodMode = "CALENDAR", period = BudgetPeriod.YEARLY, startDate = anchor)

        val (start, end) = calculator.calculatePeriodRange(budget)

        assertApproxEquals(atTime("2026-03-15", 0, 0, 0).toDouble(), start.toDouble(), 0.0)
        assertApproxEquals(atTime("2027-03-15", 0, 0, 0).toDouble(), end.toDouble(), 0.0)
    }

    private fun budget(periodMode: String, period: BudgetPeriod, startDate: Long): Budget {
        return Budget(
            id = 1L,
            categoryId = null,
            amount = 1000.0,
            period = period,
            periodMode = periodMode,
            startDate = startDate
        )
    }

    private fun atTime(date: String, hour: Int, minute: Int, second: Int): Long {
        val start = com.yourname.expensetracker.dateToMillis(date)
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = start
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
