package com.yourname.expensetracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringPatternModelTest {

    @Test
    fun `irregular exposes explicit non-interval semantics`() {
        val frequency = RecurrenceFrequency.IRREGULAR

        assertTrue(frequency.isIrregular)
        assertNull(frequency.fixedIntervalDays)
        assertNull(frequency.calendarMonths)
        assertNull(frequency.intervalInMs)
    }

    @Test
    fun `calendar frequencies expose month semantics without fixed intervals`() {
        assertEquals(1, RecurrenceFrequency.MONTHLY.calendarMonths)
        assertEquals(3, RecurrenceFrequency.QUARTERLY.calendarMonths)
        assertEquals(12, RecurrenceFrequency.ANNUALLY.calendarMonths)
        assertNull(RecurrenceFrequency.MONTHLY.fixedIntervalDays)
    }
}
