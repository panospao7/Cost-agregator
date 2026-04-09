package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Ensures TimePeriodUtils, BudgetCalculator, and AdvancedAnalyticsEngine
 * use consistent [startInclusive, endExclusive) boundaries for month, week,
 * and year periods.
 */
class TimePeriodAlignmentTest {

    private val timeProvider = mockk<TimeProvider>()
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var advancedAnalyticsEngine: AdvancedAnalyticsEngine

    private fun ts(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Before
    fun setup() {
        every { timeProvider.now() } returns ts(2024, 5, 15)
        budgetCalculator = BudgetCalculator(timeProvider)
        advancedAnalyticsEngine = AdvancedAnalyticsEngine(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )
    }

    // ============================================================================
    // MONTH ALIGNMENT
    // ============================================================================

    @Test
    fun `alignment - TimePeriodUtils and AdvancedAnalyticsEngine month start match`() {
        val ref = ts(2024, 5, 15)
        val utilsStart = TimePeriodUtils.getStartOfMonth(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, ref)
        assertEquals("Month start must match", utilsStart, advRange.startMs)
    }

    @Test
    fun `alignment - TimePeriodUtils getMonthRange and AdvancedAnalyticsEngine month end match`() {
        val ref = ts(2024, 5, 15)
        val (utilsStart, utilsEnd) = TimePeriodUtils.getMonthRange(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, ref)
        assertEquals("Month start must match", utilsStart, advRange.startMs)
        // Both must use the same exclusive end boundary (start of next month)
        assertEquals("Month end must match (half-open exclusive)", utilsEnd, advRange.endMs)
    }

    @Test
    fun `alignment - BudgetCalculator MONTHLY with anchor day 1 matches calendar month`() {
        val ref = ts(2024, 5, 15)
        val anchorFirstOfMonth = TimePeriodUtils.getStartOfMonth(ref)
        val budgetRange = budgetCalculator.calculatePeriodWindow(
            BudgetPeriod.MONTHLY,
            anchorFirstOfMonth
        )
        val utilsStart = TimePeriodUtils.getStartOfMonth(ref)
        val utilsEnd = TimePeriodUtils.getEndOfMonth(ref)
        assertEquals("Budget MONTHLY start should match", utilsStart, budgetRange.start)
        assertTrue("Budget MONTHLY end should be start of next month",
            budgetRange.end >= utilsEnd && budgetRange.end <= utilsEnd + 86400000)
    }

    @Test
    fun `alignment - week start TimePeriodUtils and AdvancedAnalyticsEngine match`() {
        val ref = ts(2024, 5, 15)
        val utilsStart = TimePeriodUtils.getStartOfWeek(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK, ref)
        assertEquals("Week start must match", utilsStart, advRange.startMs)
    }

    @Test
    fun `alignment - week duration is approximately 7 days`() {
        // During DST transitions a week can span 7*24h ± 1h, so we allow a small tolerance.
        val ref = ts(2024, 5, 15)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK, ref)
        val durationMs = advRange.endMs - advRange.startMs
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        // Allow ±1h tolerance for DST-boundary weeks
        assertTrue("Week should be ~7 days (got ${durationMs}ms)",
            durationMs in (sevenDaysMs - 3_600_000)..(sevenDaysMs + 3_600_000))
    }

    // ============================================================================
    // EDGE CASES - Month boundaries (half-open: endExclusive = 1st of next month)
    // ============================================================================

    @Test
    fun `edge - first day of month - end is exclusive start of next month`() {
        val ref = ts(2024, 0, 1) // Jan 1
        val start = TimePeriodUtils.getStartOfMonth(ref)
        val end = TimePeriodUtils.getEndOfMonth(ref)
        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        val calEnd = Calendar.getInstance().apply { timeInMillis = end }
        // Start: Jan 1
        assertEquals(1, calStart.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.JANUARY, calStart.get(Calendar.MONTH))
        // End: Feb 1 (exclusive upper bound)
        assertEquals(1, calEnd.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, calEnd.get(Calendar.MONTH))
    }

    @Test
    fun `edge - last day of February 2024 (leap year) - end is exclusive Mar 1`() {
        val ref = ts(2024, 1, 29) // Feb 29
        val end = TimePeriodUtils.getEndOfMonth(ref)
        val cal = Calendar.getInstance().apply { timeInMillis = end }
        // Exclusive end: March 1
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
    }

    @Test
    fun `edge - last day of February 2023 (non-leap) - end is exclusive Mar 1`() {
        val ref = ts(2023, 1, 28) // Feb 28
        val end = TimePeriodUtils.getEndOfMonth(ref)
        val cal = Calendar.getInstance().apply { timeInMillis = end }
        // Exclusive end: March 1
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
    }

    @Test
    fun `edge - December 31 year boundary - month end is exclusive Jan 1 next year`() {
        val ref = ts(2024, 11, 31) // Dec 31
        val (start, end) = TimePeriodUtils.getMonthRange(ref)
        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        val calEnd = Calendar.getInstance().apply { timeInMillis = end }
        // Start: Dec 1 2024
        assertEquals(2024, calStart.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, calStart.get(Calendar.MONTH))
        assertEquals(1, calStart.get(Calendar.DAY_OF_MONTH))
        // End: Jan 1 2025 (exclusive upper bound)
        assertEquals(2025, calEnd.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, calEnd.get(Calendar.MONTH))
        assertEquals(1, calEnd.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `edge - January 1 year boundary`() {
        val ref = ts(2024, 0, 1) // Jan 1
        val (start, end) = TimePeriodUtils.getMonthRange(ref)
        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        val calEnd = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(2024, calStart.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, calStart.get(Calendar.MONTH))
        // End: Feb 1 (exclusive)
        assertEquals(Calendar.FEBRUARY, calEnd.get(Calendar.MONTH))
        assertEquals(1, calEnd.get(Calendar.DAY_OF_MONTH))
    }

    // ============================================================================
    // HALF-OPEN CONTRACT TESTS
    // ============================================================================

    @Test
    fun `contract - month ranges are contiguous and non-overlapping`() {
        val ref = ts(2024, 5, 15)
        for (offset in -6..5) {
            val (_, currEnd) = TimePeriodUtils.getMonthRange(ref, offset)
            val (nextStart, _) = TimePeriodUtils.getMonthRange(ref, offset + 1)
            assertEquals("Month $offset end must equal month ${offset + 1} start (contiguous half-open)",
                currEnd, nextStart)
        }
    }

    @Test
    fun `contract - week ranges are contiguous and non-overlapping`() {
        val ref = ts(2024, 5, 15)
        for (offset in -4..3) {
            val (_, currEnd) = TimePeriodUtils.getWeekRange(ref, offset)
            val (nextStart, _) = TimePeriodUtils.getWeekRange(ref, offset + 1)
            assertEquals("Week $offset end must equal week ${offset + 1} start (contiguous half-open)",
                currEnd, nextStart)
        }
    }

    @Test
    fun `contract - year ranges are contiguous and non-overlapping`() {
        val ref = ts(2024, 5, 15)
        for (offset in -2..1) {
            val (_, currEnd) = TimePeriodUtils.getYearRange(ref, offset)
            val (nextStart, _) = TimePeriodUtils.getYearRange(ref, offset + 1)
            assertEquals("Year $offset end must equal year ${offset + 1} start (contiguous half-open)",
                currEnd, nextStart)
        }
    }

    @Test
    fun `contract - quarter ranges are contiguous and non-overlapping`() {
        val ref = ts(2024, 5, 15)
        for (offset in -4..3) {
            val (_, currEnd) = TimePeriodUtils.getQuarterRange(ref, offset)
            val (nextStart, _) = TimePeriodUtils.getQuarterRange(ref, offset + 1)
            assertEquals("Quarter $offset end must equal quarter ${offset + 1} start (contiguous half-open)",
                currEnd, nextStart)
        }
    }

    // ============================================================================
    // STRESS - Multiple months
    // ============================================================================

    @Test
    fun `stress - 24 months getMonthRange consistent`() {
        val ref = ts(2024, 5, 15)
        for (offset in -12..11) {
            val (start, end) = TimePeriodUtils.getMonthRange(ref, offset)
            assertTrue("Start < end for offset $offset", start < end)
            val durationDays = TimePeriodUtils.daysBetween(start, end)
            assertTrue("Month should be 28-31 days (got $durationDays for offset $offset)",
                durationDays in 28..31)
        }
    }

    @Test
    fun `stress - getStartOfMonth idempotent`() {
        val ref = ts(2024, 5, 15)
        val s1 = TimePeriodUtils.getStartOfMonth(ref)
        val s2 = TimePeriodUtils.getStartOfMonth(s1)
        assertEquals(s1, s2)
    }

    @Test
    fun `stress - previous month range does not overlap current`() {
        val ref = ts(2024, 5, 15)
        val (currStart, _) = TimePeriodUtils.getMonthRange(ref, 0)
        val (_, prevEnd) = TimePeriodUtils.getMonthRange(ref, -1)
        assertEquals("Previous month end must equal current month start (half-open)", prevEnd, currStart)
    }

    // ============================================================================
    // NEW HELPERS ALIGNMENT
    // ============================================================================

    @Test
    fun `alignment - getDayRange matches getStartOfDay and getEndOfDay`() {
        val ref = ts(2024, 5, 15)
        val (dayStart, dayEnd) = TimePeriodUtils.getDayRange(ref)
        assertEquals(TimePeriodUtils.getStartOfDay(ref), dayStart)
        assertEquals(TimePeriodUtils.getEndOfDay(ref), dayEnd)
    }

    @Test
    fun `alignment - getEndOfWeek matches getWeekRange end`() {
        val ref = ts(2024, 5, 15)
        val (_, weekRangeEnd) = TimePeriodUtils.getWeekRange(ref)
        val endOfWeek = TimePeriodUtils.getEndOfWeek(ref)
        assertEquals("getEndOfWeek must match getWeekRange end", weekRangeEnd, endOfWeek)
    }

    @Test
    fun `alignment - isInRange canonical containment for month boundary`() {
        val ref = ts(2024, 5, 15)
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(ref)

        // ref is within its own month range
        assertTrue("ref should be in its own month range",
            TimePeriodUtils.isInRange(ref, monthStart, monthEnd))

        // monthStart is included (half-open start-inclusive)
        assertTrue("monthStart should be in month range",
            TimePeriodUtils.isInRange(monthStart, monthStart, monthEnd))

        // monthEnd is excluded (half-open end-exclusive)
        assertTrue("monthEnd (exclusive) should NOT be in month range",
            !TimePeriodUtils.isInRange(monthEnd, monthStart, monthEnd))
    }

    @Test
    fun `alignment - isInRange canonical containment for week boundary`() {
        val ref = ts(2024, 5, 15)
        val weekStart = TimePeriodUtils.getStartOfWeek(ref)
        val weekEnd = TimePeriodUtils.getEndOfWeek(ref)

        assertTrue("ref should be in its own week range",
            TimePeriodUtils.isInRange(ref, weekStart, weekEnd))
        assertTrue("Monday midnight should be in week range",
            TimePeriodUtils.isInRange(weekStart, weekStart, weekEnd))
        assertTrue("Next Monday (exclusive) should NOT be in week range",
            !TimePeriodUtils.isInRange(weekEnd, weekStart, weekEnd))
    }
}
