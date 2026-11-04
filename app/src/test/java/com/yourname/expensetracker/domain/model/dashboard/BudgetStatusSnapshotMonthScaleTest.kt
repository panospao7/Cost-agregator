package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1: [BudgetStatusSnapshot.isMonthScalePeriod] is true only for a period of 28 to 31 whole days
 * (rounded, so a DST-shifted 23h or 25h day cannot push a month out of range). Weekly, yearly,
 * empty and inverted periods are not month-scale.
 */
class BudgetStatusSnapshotMonthScaleTest {

    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L
    private val start = 1_780_000_000_000L

    private fun snapshot(durationMs: Long) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = 100.0,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = 100.0,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = start,
        periodEnd = start + durationMs
    )

    @Test
    fun `isMonthScalePeriod is true for 28 30 and 31 day periods`() {
        assertTrue("28 days", snapshot(28 * dayMs).isMonthScalePeriod)
        assertTrue("29 days", snapshot(29 * dayMs).isMonthScalePeriod)
        assertTrue("30 days", snapshot(30 * dayMs).isMonthScalePeriod)
        assertTrue("31 days", snapshot(31 * dayMs).isMonthScalePeriod)
    }

    @Test
    fun `isMonthScalePeriod is false for 7 27 32 and 365 day periods`() {
        assertFalse("7 days", snapshot(7 * dayMs).isMonthScalePeriod)
        assertFalse("27 days", snapshot(27 * dayMs).isMonthScalePeriod)
        assertFalse("32 days", snapshot(32 * dayMs).isMonthScalePeriod)
        assertFalse("365 days", snapshot(365 * dayMs).isMonthScalePeriod)
    }

    @Test
    fun `isMonthScalePeriod tolerates a DST shifted hour at both boundaries`() {
        assertTrue("28 days minus 1h", snapshot(28 * dayMs - hourMs).isMonthScalePeriod)
        assertTrue("31 days plus 1h", snapshot(31 * dayMs + hourMs).isMonthScalePeriod)
        assertFalse("27 days plus 1h", snapshot(27 * dayMs + hourMs).isMonthScalePeriod)
        assertFalse("32 days minus 1h", snapshot(32 * dayMs - hourMs).isMonthScalePeriod)
    }

    @Test
    fun `isMonthScalePeriod is false for empty and inverted periods`() {
        assertFalse("zero length", snapshot(0L).isMonthScalePeriod)
        assertFalse("inverted", snapshot(-30 * dayMs).isMonthScalePeriod)
    }

    @Test
    fun `isMonthScalePeriod rounds half a day up`() {
        assertEquals(true, snapshot(27 * dayMs + dayMs / 2).isMonthScalePeriod)
        assertEquals(false, snapshot(31 * dayMs + dayMs / 2).isMonthScalePeriod)
    }
}
