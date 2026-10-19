package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Golden budget-range values. These three tests are intentionally kept separate and exact:
 * each pins one canonical (mode, period, now, anchor) -> [start, end) mapping.
 *
 * The JVM default zone is pinned to UTC for the class ([PinnedUtcTimeZoneRule]); every
 * expected instant is built in an explicit UTC zone, so the goldens never depend on the host zone.
 */
class BudgetCalculatorGoldenTest {

    companion object {
        // Zone pinned to UTC for the whole class: BudgetCalculator reads ZoneId.systemDefault().
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val UTC: ZoneId = ZoneId.of("UTC")
    }

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var calculator: BudgetCalculator

    @Before
    fun setUp() {
        every { timeProvider.now() } returns 0L
        calculator = BudgetCalculator(timeProvider)
    }

    @Test
    fun `monthly calendar mode on march 15 returns march 1 to april 1 exclusive range`() {
        val now = atTime(2026, 3, 15, 14)
        every { timeProvider.now() } returns now
        val budget = budget(periodMode = "CALENDAR", period = BudgetPeriod.MONTHLY, startDate = atTime(2026, 2, 10, 0))

        val (start, end) = calculator.calculatePeriodRange(budget)

        assertEquals("calendar monthly start", atTime(2026, 3, 1, 0), start)
        assertEquals("calendar monthly end (exclusive)", atTime(2026, 4, 1, 0), end)
    }

    @Test
    fun `rolling monthly mode resolves active anchored cycle via calendar month math`() {
        val now = atTime(2026, 3, 5, 12)
        every { timeProvider.now() } returns now
        val startDate = atTime(2026, 2, 10, 0)
        val budget = budget(periodMode = "ROLLING", period = BudgetPeriod.MONTHLY, startDate = startDate)

        val (start, end) = calculator.calculatePeriodRange(budget)

        // Anchor day is 10. March 5 < 10, so period started in previous month: Feb 10.
        // Period end is March 10 (anchor day in next month).
        assertEquals("rolling monthly start", atTime(2026, 2, 10, 0), start)
        assertEquals("rolling monthly end (exclusive)", atTime(2026, 3, 10, 0), end)
    }

    @Test
    fun `rolling yearly anniversary on march 15 advances window to current anniversary`() {
        val now = atTime(2026, 3, 15, 12)
        every { timeProvider.now() } returns now
        val anchor = atTime(2025, 3, 15, 0)
        val budget = budget(periodMode = "ROLLING", period = BudgetPeriod.YEARLY, startDate = anchor)

        val (start, end) = calculator.calculatePeriodRange(budget)

        assertEquals("rolling yearly start", atTime(2026, 3, 15, 0), start)
        assertEquals("rolling yearly end (exclusive)", atTime(2027, 3, 15, 0), end)
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

    /** Explicit-UTC instant for the given date and hour (minutes/seconds/millis are zero). */
    private fun atTime(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(UTC).toInstant().toEpochMilli()
}
