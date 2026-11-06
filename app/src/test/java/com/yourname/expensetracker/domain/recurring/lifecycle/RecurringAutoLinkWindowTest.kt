package com.yourname.expensetracker.domain.recurring.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DR-11: the auto-link window is at most 1 day early and 3 days late, shrunk (late first, then early)
 * until `2*(early+late) < periodDays` so the windows of two neighbouring occurrences never overlap.
 * Expected values below are worked out by hand from that rule, not computed by the production helper.
 */
class RecurringAutoLinkWindowTest {

    private fun window(periodDays: Int) = RecurringLifecycleCoordinator.autoLinkWindow(periodDays)

    @Test
    fun `weekly period gives one day early and two days late`() {
        assertEquals(AutoLinkWindow(1, 2), window(7))
    }

    @Test
    fun `one day period gives the same-day window`() {
        assertEquals(AutoLinkWindow(0, 0), window(1))
        assertEquals(AutoLinkWindow.SAME_DAY, window(1))
    }

    @Test
    fun `biweekly period gives the full one early three late window`() {
        assertEquals(AutoLinkWindow(1, 3), window(14))
    }

    @Test
    fun `monthly period of 28 shortest-month days gives the full window`() {
        assertEquals(AutoLinkWindow(1, 3), window(28))
    }

    @Test
    fun `zero or negative period gives the same-day window`() {
        assertEquals(AutoLinkWindow(0, 0), window(0))
        assertEquals(AutoLinkWindow(0, 0), window(-1))
        assertEquals(AutoLinkWindow(0, 0), window(Int.MIN_VALUE))
    }

    @Test
    fun `two day period is still same-day only`() {
        // 2*(1+0) = 2 is not < 2, so even the one-day early allowance is dropped.
        assertEquals(AutoLinkWindow(0, 0), window(2))
    }

    @Test
    fun `boundary between shrunk and full window is nine days`() {
        // 8: 2*(1+3)=8 is not < 8, late shrinks to 2 (6 < 8).
        assertEquals(AutoLinkWindow(1, 2), window(8))
        // 9: 2*(1+3)=8 < 9, no shrink.
        assertEquals(AutoLinkWindow(1, 3), window(9))
    }

    @Test
    fun `late allowance is shrunk before the early allowance`() {
        // 5: (1,3)=8, (1,2)=6 both >= 5; (1,1)=4 < 5 keeps the early day.
        assertEquals(AutoLinkWindow(1, 1), window(5))
        // 3: only the early day survives (2 < 3).
        assertEquals(AutoLinkWindow(1, 0), window(3))
    }

    @Test
    fun `very long periods never exceed the upper bounds`() {
        assertEquals(AutoLinkWindow(1, 3), window(365))
        assertEquals(AutoLinkWindow(1, 3), window(Int.MAX_VALUE))
    }
}
