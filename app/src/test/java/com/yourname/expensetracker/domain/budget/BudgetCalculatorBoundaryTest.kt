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

        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

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

        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

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

        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

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
            val duration = window.endExclusiveMillis - window.startInclusiveMillis
            val expectedDuration = 23L * 60L * 60L * 1000L

            assertEquals(expectedDuration, duration)

        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

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

    // ========================================================================
    // B.2 Batch 1 — Rolling anchored-cycle regressions
    // ========================================================================

    @Test
    fun `rolling yearly budget anchored July 1 resolves correct anniversary cycle`() {
        val now = atDateTime(2026, 9, 15, 10, 0)
        every { timeProvider.now() } returns now

        val anchorDate = atDateTime(2024, 7, 1, 0, 0)
        val budget = budget(periodMode = "ROLLING", period = BudgetPeriod.YEARLY, startDate = anchorDate)

        val result = calculator.calculatePeriodRange(budget)
        val start = Calendar.getInstance().apply { timeInMillis = result.first }
        val end = Calendar.getInstance().apply { timeInMillis = result.second }

        // Anchor is July 1; we're in Sep 2026 which is past July 1, 2026 →
        // active cycle is Jul 1, 2026 – Jul 1, 2027
        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2027, end.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))

        assertTrue(result.first < result.second)
    }

    @Test
    fun `rolling yearly budget before anniversary date returns previous cycle`() {
        val now = atDateTime(2026, 3, 1, 10, 0)
        every { timeProvider.now() } returns now

        val anchorDate = atDateTime(2024, 7, 1, 0, 0)
        val budget = budget(periodMode = "ROLLING", period = BudgetPeriod.YEARLY, startDate = anchorDate)

        val result = calculator.calculatePeriodRange(budget)
        val start = Calendar.getInstance().apply { timeInMillis = result.first }
        val end = Calendar.getInstance().apply { timeInMillis = result.second }

        // We're in March 2026, before July 1 anniversary →
        // active cycle is Jul 1, 2025 – Jul 1, 2026
        assertEquals(2025, start.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2026, end.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
    }

    // ========================================================================
    // B.2 Batch 1 — CALENDAR yearly Jan 1 → Jan 1 regressions
    // ========================================================================

    @Test
    fun `calendar yearly budget resolves Jan 1 to Jan 1 ignoring mid-year anchor`() {
        val now = atDateTime(2026, 9, 15, 10, 0)
        every { timeProvider.now() } returns now

        val anchorDate = atDateTime(2024, 7, 1, 0, 0) // anchor is July — irrelevant for CALENDAR
        val budget = budget(periodMode = "CALENDAR", period = BudgetPeriod.YEARLY, startDate = anchorDate)

        val result = calculator.calculatePeriodRange(budget)
        val start = Calendar.getInstance().apply { timeInMillis = result.first }
        val end = Calendar.getInstance().apply { timeInMillis = result.second }

        // CALENDAR YEARLY: Jan 1, 2026 → Jan 1, 2027
        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2027, end.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `calendar yearly budget on Jan 1 itself returns full year`() {
        val now = atDateTime(2026, 1, 1, 0, 0)
        every { timeProvider.now() } returns now

        val budget = budget(periodMode = "CALENDAR", period = BudgetPeriod.YEARLY, startDate = atDateTime(2025, 6, 15, 0, 0))

        val result = calculator.calculatePeriodRange(budget)
        val start = Calendar.getInstance().apply { timeInMillis = result.first }
        val end = Calendar.getInstance().apply { timeInMillis = result.second }

        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2027, end.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `calendar yearly budget on Dec 31 returns current year`() {
        val now = atDateTime(2026, 12, 31, 23, 59)
        every { timeProvider.now() } returns now

        val budget = budget(periodMode = "CALENDAR", period = BudgetPeriod.YEARLY, startDate = atDateTime(2025, 1, 1, 0, 0))

        val result = calculator.calculatePeriodRange(budget)
        val start = Calendar.getInstance().apply { timeInMillis = result.first }
        val end = Calendar.getInstance().apply { timeInMillis = result.second }

        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(2027, end.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
    }

    // ========================================================================
    // B.2 Batch 1 — Jan/Feb coercion lock-in
    // ========================================================================

    @Test
    fun `monthly anchor day 30 coerces to Feb 28 in non-leap year`() {
        val anchorDate = atDateTime(2025, 1, 30, 0, 0)
        val evaluationTime = atDateTime(2025, 2, 15, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, evaluationTime)
        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

        assertEquals(Calendar.JANUARY, start.get(Calendar.MONTH))
        assertEquals(30, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(Calendar.FEBRUARY, end.get(Calendar.MONTH))
        assertEquals(28, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `monthly anchor day 30 coerces to Feb 29 in leap year`() {
        val anchorDate = atDateTime(2024, 1, 30, 0, 0)
        val evaluationTime = atDateTime(2024, 2, 15, 12, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, evaluationTime)
        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

        assertEquals(Calendar.JANUARY, start.get(Calendar.MONTH))
        assertEquals(30, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(Calendar.FEBRUARY, end.get(Calendar.MONTH))
        assertEquals(29, end.get(Calendar.DAY_OF_MONTH))
    }

    // ========================================================================
    // B.2 Batch 1 — Explicit evaluation-time usage lock-in
    // ========================================================================

    @Test
    fun `calculatePeriodWindowForTime with explicit past evaluation time gives correct historical window`() {
        val anchorDate = atDateTime(2025, 6, 15, 0, 0)
        val pastEval = atDateTime(2025, 8, 20, 10, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, pastEval)
        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

        // Aug 20 >= anchor day 15, so cycle is Aug 15 – Sep 15
        assertEquals(Calendar.AUGUST, start.get(Calendar.MONTH))
        assertEquals(15, start.get(Calendar.DAY_OF_MONTH))

        assertEquals(Calendar.SEPTEMBER, end.get(Calendar.MONTH))
        assertEquals(15, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `calculatePeriodWindowForTime with explicit future evaluation time gives correct future window`() {
        val anchorDate = atDateTime(2025, 6, 15, 0, 0)
        val futureEval = atDateTime(2027, 1, 10, 10, 0)

        val window = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, futureEval)
        val start = Calendar.getInstance().apply { timeInMillis = window.startInclusiveMillis }
        val end = Calendar.getInstance().apply { timeInMillis = window.endExclusiveMillis }

        // Jan 10 < anchor day 15 → cycle started in previous month: Dec 15, 2026
        assertEquals(Calendar.DECEMBER, start.get(Calendar.MONTH))
        assertEquals(15, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(2026, start.get(Calendar.YEAR))

        assertEquals(Calendar.JANUARY, end.get(Calendar.MONTH))
        assertEquals(15, end.get(Calendar.DAY_OF_MONTH))
        assertEquals(2027, end.get(Calendar.YEAR))
    }

    @Test
    fun `convenience calculatePeriodWindow delegates to calculatePeriodWindowForTime with timeProvider now`() {
        val now = atDateTime(2026, 4, 10, 14, 0)
        every { timeProvider.now() } returns now
        val anchorDate = atDateTime(2026, 1, 5, 0, 0)

        val convenienceResult = calculator.calculatePeriodWindow(BudgetPeriod.MONTHLY, anchorDate)
        val explicitResult = calculator.calculatePeriodWindowForTime(BudgetPeriod.MONTHLY, anchorDate, now)

        assertEquals(explicitResult.startInclusiveMillis, convenienceResult.startInclusiveMillis)
        assertEquals(explicitResult.endExclusiveMillis, convenienceResult.endExclusiveMillis)
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
