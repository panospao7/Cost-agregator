package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.analytics.AnalyticsPeriod
import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsEngine
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Ensures TimePeriodUtils, BudgetCalculator, and AdvancedAnalyticsEngine
 * use consistent boundaries for month, week, and year periods.
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
            timeProvider
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
    fun `alignment - TimePeriodUtils getMonthRange and AdvancedAnalyticsEngine month match`() {
        val ref = ts(2024, 5, 15)
        val (utilsStart, utilsEnd) = TimePeriodUtils.getMonthRange(ref)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH, ref)
        assertEquals("Month start must match", utilsStart, advRange.startMs)
        assertEquals("Month end: Adv uses endOfMonth+1", utilsEnd + 1, advRange.endMs)
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
    fun `alignment - week is 7 days`() {
        val ref = ts(2024, 5, 15)
        val advRange = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK, ref)
        val durationMs = advRange.endMs - advRange.startMs
        assertEquals("Week should be 7 days", 7 * 24 * 60 * 60 * 1000L, durationMs)
    }

    // ============================================================================
    // EDGE CASES - Month boundaries
    // ============================================================================

    @Test
    fun `edge - first day of month`() {
        val ref = ts(2024, 0, 1)
        val start = TimePeriodUtils.getStartOfMonth(ref)
        val end = TimePeriodUtils.getEndOfMonth(ref)
        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        val calEnd = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(1, calStart.get(Calendar.DAY_OF_MONTH))
        assertEquals(31, calEnd.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `edge - last day of February 2024 (leap year)`() {
        val ref = ts(2024, 1, 29)
        val end = TimePeriodUtils.getEndOfMonth(ref)
        val cal = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH))
    }

    @Test
    fun `edge - last day of February 2023 (non-leap)`() {
        val ref = ts(2023, 1, 28)
        val end = TimePeriodUtils.getEndOfMonth(ref)
        val cal = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `edge - December 31 year boundary`() {
        val ref = ts(2024, 11, 31)
        val (start, end) = TimePeriodUtils.getMonthRange(ref)
        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        val calEnd = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(2024, calStart.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, calStart.get(Calendar.MONTH))
        assertEquals(2024, calEnd.get(Calendar.YEAR))
        assertEquals(31, calEnd.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `edge - January 1 year boundary`() {
        val ref = ts(2024, 0, 1)
        val (start, end) = TimePeriodUtils.getMonthRange(ref)
        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2024, calStart.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, calStart.get(Calendar.MONTH))
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
            val durationMs = end - start
            val durationDays = durationMs / (24 * 60 * 60 * 1000)
            assertTrue("Month should be 28-31 days (got $durationDays for offset $offset)", durationDays in 26..32)
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
        val (currStart, currEnd) = TimePeriodUtils.getMonthRange(ref, 0)
        val (prevStart, prevEnd) = TimePeriodUtils.getMonthRange(ref, -1)
        assertTrue("Previous end <= current start", prevEnd <= currStart)
        assertTrue("No overlap", prevEnd <= currStart)
    }
}
