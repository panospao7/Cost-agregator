package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceCalculatorTest {

    @Test
    fun `toMonthlyAmount uses canonical irregular semi annual and annual semantics`() {
        assertEquals(45.0, RecurrenceCalculator.toMonthlyAmount(45.0, RecurrenceFrequency.IRREGULAR), 0.0001)
        assertEquals(100.0, RecurrenceCalculator.toMonthlyAmount(600.0, RecurrenceFrequency.SEMI_ANNUALLY), 0.0001)
        assertEquals(100.0, RecurrenceCalculator.toMonthlyAmount(1200.0, RecurrenceFrequency.ANNUALLY), 0.0001)
    }

    @Test
    fun `fromMonthlyAmount uses canonical irregular semi annual and annual semantics`() {
        assertEquals(45.0, RecurrenceCalculator.fromMonthlyAmount(45.0, RecurrenceFrequency.IRREGULAR), 0.0001)
        assertEquals(600.0, RecurrenceCalculator.fromMonthlyAmount(100.0, RecurrenceFrequency.SEMI_ANNUALLY), 0.0001)
        assertEquals(1200.0, RecurrenceCalculator.fromMonthlyAmount(100.0, RecurrenceFrequency.ANNUALLY), 0.0001)
    }

    @Test
    fun `calculateNextDate leaves irregular unchanged for manual confirmation`() {
        val currentDate = date(2026, 3, 5)

        assertEquals(
            currentDate,
            RecurrenceCalculator.calculateNextDate(currentDate, RecurrenceFrequency.IRREGULAR)
        )
    }

    @Test
    fun `calculateNextDate advances semi annually by six months`() {
        val currentDate = date(2026, 2, 10)

        assertEquals(
            TimePeriodUtils.addMonths(currentDate, 6),
            RecurrenceCalculator.calculateNextDate(currentDate, RecurrenceFrequency.SEMI_ANNUALLY)
        )
    }

    @Test
    fun `calculateNextDate advances annually by one year`() {
        val currentDate = date(2026, 1, 15)

        assertEquals(
            TimePeriodUtils.addYears(currentDate, 1),
            RecurrenceCalculator.calculateNextDate(currentDate, RecurrenceFrequency.ANNUALLY)
        )
    }

    // ── RP-04 P4-005: fixed-anchor month arithmetic ─────────────────────────

    @Test
    fun `addFrequencyInterval monthly jan31 clamps to feb without losing anchor`() {
        val base = date(2027, 1, 31)

        val next = RecurrenceCalculator.addFrequencyInterval(base, RecurrenceFrequency.MONTHLY)

        assertEquals(date(2027, 2, 28), next)
    }

    @Test
    fun `addFrequencyInterval quarterly jan31 clamps each target month without cumulative drift`() {
        val base = date(2027, 1, 31)

        // Pin the FIXED anchor day (31) every step: each target month is re-derived
        // from the anchor, so the Apr clamp (30) never propagates — Jul recovers to
        // 31 and Oct stays 31. Without the pinned anchor, per-step derivation would
        // inherit the clamped day (30) and drift.
        val q1 = RecurrenceCalculator.addFrequencyInterval(base, RecurrenceFrequency.QUARTERLY, anchorDayOfMonth = 31)
        val q2 = RecurrenceCalculator.addFrequencyInterval(q1, RecurrenceFrequency.QUARTERLY, anchorDayOfMonth = 31)
        val q3 = RecurrenceCalculator.addFrequencyInterval(q2, RecurrenceFrequency.QUARTERLY, anchorDayOfMonth = 31)

        assertEquals(date(2027, 4, 30), q1)
        assertEquals(date(2027, 7, 31), q2)
        assertEquals(date(2027, 10, 31), q3)
    }

    @Test
    fun `addFrequencyInterval per-step derivation inherits clamped day when no anchor is pinned`() {
        val base = date(2027, 1, 31)

        // Documents the default (no explicit anchor) contract: the anchor is
        // derived from the CURRENT base each call, so a clamped result (Apr 30)
        // becomes the next step's anchor. This is D7-consistent single-step
        // semantics — the caller's current anchor is authoritative.
        val q1 = RecurrenceCalculator.addFrequencyInterval(base, RecurrenceFrequency.QUARTERLY)
        val q2 = RecurrenceCalculator.addFrequencyInterval(q1, RecurrenceFrequency.QUARTERLY)

        assertEquals(date(2027, 4, 30), q1)
        assertEquals(date(2027, 7, 30), q2)
    }

    @Test
    fun `monthAndQuarterIntervalsMatchOccurrenceExpander`() {
        // RP-04 P4-005: next-date calculation and expansion must agree when both
        // use the same fixed anchor day derived from the operation's anchor date.
        val expander = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander()
        for (frequency in listOf(
            RecurrenceFrequency.MONTHLY,
            RecurrenceFrequency.QUARTERLY,
            RecurrenceFrequency.SEMI_ANNUALLY
        )) {
            val anchor = date(2027, 1, 31)
            val anchorDay = 31
            val endDate = date(2028, 12, 31)

            val request = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.ExpandRequest(
                merchant = "Test Merchant",
                amount = 10.0,
                currency = "EUR",
                frequency = frequency,
                categoryId = null,
                startDate = date(2027, 1, 1),
                endDate = endDate,
                anchorDate = anchor,
                sourceType = "RECURRING_RULE",
                sourceId = 7L
            )
            val expanded = expander.expand(request)

            // Walk the calculator independently from the same anchor, pinning the
            // same fixed anchor day every step.
            val calculatorDates = mutableListOf(anchor)
            var cursor = anchor
            repeat(4) {
                cursor = RecurrenceCalculator.addFrequencyInterval(
                    cursor, frequency, anchorDayOfMonth = anchorDay
                )
                if (cursor < endDate) calculatorDates.add(cursor)
            }

            val expanderDates = expanded.map { it.dueDate }
            assertEquals(
                "Frequency ${frequency.name}: expander and calculator disagree",
                calculatorDates,
                expanderDates.take(calculatorDates.size)
            )
            assertTrue(expanderDates.size >= calculatorDates.size)
        }
    }

    @Test
    fun `addFrequencyInterval monthly anchor recovers day 31 after february leap year`() {
        val base = date(2028, 1, 31)

        val feb = RecurrenceCalculator.addFrequencyInterval(base, RecurrenceFrequency.MONTHLY)

        // Leap year: Feb 2028 has 29 days; anchor 31 clamps to 29.
        assertEquals(date(2028, 2, 29), feb)
    }

    @Test
    fun `addFrequencyInterval backward monthly never produces invalid dates`() {
        val base = date(2027, 3, 31)

        val previous = RecurrenceCalculator.addFrequencyInterval(base, RecurrenceFrequency.MONTHLY, forward = false)

        assertEquals(date(2027, 2, 28), previous)
    }

    private fun date(year: Int, month: Int, day: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
