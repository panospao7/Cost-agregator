package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.assertEquals
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
    fun `calculateNextDate advances irregular by one month`() {
        val currentDate = date(2026, 3, 5)

        assertEquals(
            TimePeriodUtils.addMonths(currentDate, 1),
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

    private fun date(year: Int, month: Int, day: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
