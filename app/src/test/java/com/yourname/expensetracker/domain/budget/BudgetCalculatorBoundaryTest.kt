package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BudgetCalculatorBoundaryTest : AnalyticsEngineTestBase() {

    private lateinit var calculator: BudgetCalculator

    @Before
    override fun setUp() {
        super.setUp()
        calculator = BudgetCalculator(timeProvider)
    }

    @Test
    fun `monthly anchor day 31 coerces february boundary for current cycle calculation`() {
        val anchorDate = atDateTime(2026, 1, 31, 9, 0)
        val evaluationTime = atDateTime(2026, 2, 15, 10, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, evaluationTime)

        val start = Calendar.getInstance().apply { timeInMillis = window.start }
        val end = Calendar.getInstance().apply { timeInMillis = window.end }

        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, start.get(Calendar.MONTH))
        assertEquals(31, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2026, end.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, end.get(Calendar.MONTH))
        assertEquals(28, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `monthly leap anchor day 29 coerces to february 28 in non leap year then returns to 29`() {
        val anchorDate = atDateTime(2024, 3, 29, 8, 0)
        val evaluationTime = atDateTime(2025, 2, 28, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, evaluationTime)

        val start = Calendar.getInstance().apply { timeInMillis = window.start }
        val end = Calendar.getInstance().apply { timeInMillis = window.end }

        assertEquals(2025, start.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, start.get(Calendar.MONTH))
        assertEquals(28, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2025, end.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, end.get(Calendar.MONTH))
        assertEquals(29, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `weekly period aligns to anchor weekday and returns monday to monday window`() {
        val anchorDate = atDateTime(2026, 1, 5, 9, 0)
        val evaluationTime = atDateTime(2026, 3, 11, 14, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.WEEKLY, anchorDate, evaluationTime)

        val start = Calendar.getInstance().apply { timeInMillis = window.start }
        val end = Calendar.getInstance().apply { timeInMillis = window.end }

        assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK))
        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, start.get(Calendar.MONTH))
        assertEquals(9, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(Calendar.MONDAY, end.get(Calendar.DAY_OF_WEEK))
        assertEquals(2026, end.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, end.get(Calendar.MONTH))
        assertEquals(16, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `daily period across athens dst spring forward has 23 hour duration`() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
            val fakeTimeProvider = FakeTimeProvider.forDate(2026, 3, 29, 3, 0)
            val dstCalculator = BudgetCalculator(fakeTimeProvider)

            val window = dstCalculator.calculatePeriodWindow(BudgetPeriod.DAILY, fakeTimeProvider.now())
            val duration = window.end - window.start
            val expectedDuration = 23L * 60L * 60L * 1000L

            assertEquals(expectedDuration, duration)

            val start = Calendar.getInstance().apply { timeInMillis = window.start }
            val end = Calendar.getInstance().apply { timeInMillis = window.end }

            assertEquals(29, start.get(Calendar.DAY_OF_MONTH))
            assertEquals(30, end.get(Calendar.DAY_OF_MONTH))
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `empty period mode falls back to calendar mode and returns valid monthly range`() {
        val now = atDateTime(2026, 3, 15, 14, 0)
        every { timeProvider.now() } returns now
        val budget = budget(periodMode = "", period = BudgetPeriod.MONTHLY, startDate = atDateTime(2026, 2, 10, 0, 0))

        val result = calculator.calculatePeriodRange(budget)
        val expected = TimePeriodUtils.getMonthRange(now)

        assertEquals(expected.first, result.first)
        assertEquals(expected.second, result.second)
        assertTrue(result.first < result.second)
    }

    @Test
    fun `rolling monthly mode resolves active anchored cycle containing now`() {
        val now = atDateTime(2026, 3, 5, 12, 0)
        every { timeProvider.now() } returns now

        val startDate = atDateTime(2026, 1, 31, 0, 0)
        val budget = budget(periodMode = "ROLLING", period = BudgetPeriod.MONTHLY, startDate = startDate)

        val result = calculator.calculatePeriodRange(budget)

        // The active anchored cycle containing March 5 with anchor day 31:
        // Since anchor day is 31 and we're on March 5 (before the 31st),
        // the current cycle started on Feb 28 (31st coerced to max days in Feb)
        // and ends on March 31 (anchor day in next month).
        val start = Calendar.getInstance().apply { timeInMillis = result.first }
        val end = Calendar.getInstance().apply { timeInMillis = result.second }

        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, start.get(Calendar.MONTH))
        assertEquals(28, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2026, end.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, end.get(Calendar.MONTH))
        assertEquals(31, end.get(Calendar.DAY_OF_MONTH))
        assertTrue(result.first < result.second)
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

    private fun atDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
