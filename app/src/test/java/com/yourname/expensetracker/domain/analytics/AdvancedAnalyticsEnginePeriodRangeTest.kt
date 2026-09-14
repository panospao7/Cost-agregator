package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * GR-14g behavior-characterization tests for the period-range recursion
 * pair (`getPeriodRange` <-> `getPreviousPeriodRange`).  These pin the
 * exact observable behavior that must survive breaking the mutual
 * recursion (leaf extraction):
 *  - base window (start/end/label) never depends on computeComparison;
 *  - computeComparison=true attaches the previous window with its own
 *    comparisonRange null (exactly one comparison level, ever);
 *  - computeComparison=false attaches null;
 *  - previous-window arithmetic per period (week -7d, month -1m,
 *    quarter -3m, year -1y).
 */
class AdvancedAnalyticsEnginePeriodRangeTest {

    private lateinit var engine: AdvancedAnalyticsEngine
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1705320000000L // Jan 15, 2024
        engine = AdvancedAnalyticsEngine(
            mockk(),
            mockk(),
            mockk(),
            TestCurrencySettingsRepository(),
            testAnalyticsCurrencyNormalizer(),
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )
    }

    private fun cal(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance().apply { set(year, month, day, 12, 0) }

    @Test
    fun `base window is independent of computeComparison flag`() {
        val ref = cal(2023, Calendar.OCTOBER, 26).timeInMillis
        for (period in listOf(
            AnalyticsPeriod.WEEK,
            AnalyticsPeriod.MONTH,
            AnalyticsPeriod.QUARTER,
            AnalyticsPeriod.YEAR
        )) {
            val withComparison = engine.getPeriodRange(period, ref, computeComparison = true)
            val withoutComparison = engine.getPeriodRange(period, ref, computeComparison = false)
            assertEquals(period, withComparison.period)
            assertEquals(withComparison.startMs, withoutComparison.startMs)
            assertEquals(withComparison.endMs, withoutComparison.endMs)
            assertEquals(withComparison.label, withoutComparison.label)
        }
    }

    @Test
    fun `comparison attaches exactly one level and is null when disabled`() {
        val ref = cal(2023, Calendar.OCTOBER, 26).timeInMillis
        for (period in listOf(
            AnalyticsPeriod.WEEK,
            AnalyticsPeriod.MONTH,
            AnalyticsPeriod.QUARTER,
            AnalyticsPeriod.YEAR
        )) {
            val range = engine.getPeriodRange(period, ref, computeComparison = true)
            val comparison = range.comparisonRange
            assertNotNull("comparison missing for $period", comparison)
            assertNull(
                "comparison must never nest for $period",
                comparison?.comparisonRange
            )
            assertTrue(
                "previous window must end at or before current start for $period",
                comparison!!.endMs <= range.startMs
            )

            val disabled = engine.getPeriodRange(period, ref, computeComparison = false)
            assertNull("comparison must be null when disabled for $period", disabled.comparisonRange)
        }
    }

    @Test
    fun `week comparison is previous week`() {
        val ref = cal(2023, Calendar.OCTOBER, 26).timeInMillis // Thursday Oct 26
        val range = engine.getPeriodRange(AnalyticsPeriod.WEEK, ref)
        val comparison = range.comparisonRange!!
        assertEquals(range.startMs - 7L * 24 * 3600 * 1000, comparison.startMs)
        val startCal = Calendar.getInstance().apply { timeInMillis = comparison.startMs }
        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `month comparison is previous month`() {
        val ref = cal(2023, Calendar.OCTOBER, 26).timeInMillis
        val range = engine.getPeriodRange(AnalyticsPeriod.MONTH, ref)
        val comparison = range.comparisonRange!!
        val startCal = Calendar.getInstance().apply { timeInMillis = comparison.startMs }
        // base month is October 2023; previous must be September 2023
        assertEquals(Calendar.SEPTEMBER, startCal.get(Calendar.MONTH))
        assertEquals(2023, startCal.get(Calendar.YEAR))
        assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `quarter and year comparisons step one full unit`() {
        val qRef = cal(2023, Calendar.OCTOBER, 26).timeInMillis // Q4 2023
        val quarter = engine.getPeriodRange(AnalyticsPeriod.QUARTER, qRef)
        val qComp = quarter.comparisonRange!!
        assertEquals(Calendar.JULY, Calendar.getInstance().apply { timeInMillis = qComp.startMs }.get(Calendar.MONTH))

        val yRef = cal(2023, Calendar.MAY, 10).timeInMillis
        val year = engine.getPeriodRange(AnalyticsPeriod.YEAR, yRef)
        val yComp = year.comparisonRange!!
        assertEquals(2022, Calendar.getInstance().apply { timeInMillis = yComp.startMs }.get(Calendar.YEAR))
    }
}
