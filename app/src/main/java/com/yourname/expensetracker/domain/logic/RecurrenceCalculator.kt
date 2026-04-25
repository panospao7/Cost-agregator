package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Utility class for all recurrence-related calculations.
 * Single source of truth for monthly cost conversions and next date calculations.
 */
object RecurrenceCalculator {

    /**
     * Normalize a due date to date-only semantics at local midnight.
     */
    fun normalizeToDateOnly(timestamp: Long): Long {
        return TimePeriodUtils.getStartOfDay(timestamp)
    }

    /**
     * Canonical multiplier for converting a recurrence period into its monthly equivalent.
     */
    fun monthlyMultiplier(frequency: RecurrenceFrequency): Double {
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> 4.33
            RecurrenceFrequency.BIWEEKLY -> 2.17
            RecurrenceFrequency.MONTHLY -> 1.0
            RecurrenceFrequency.QUARTERLY -> 1.0 / 3.0
            RecurrenceFrequency.SEMI_ANNUALLY -> 1.0 / 6.0
            RecurrenceFrequency.ANNUALLY -> 1.0 / 12.0
            RecurrenceFrequency.IRREGULAR -> 1.0
        }
    }
    
    /**
     * Convert any frequency amount to monthly equivalent.
     * 
     * @param amount The amount per period
     * @param frequency The recurrence frequency
     * @return Monthly equivalent amount
     */
    fun toMonthlyAmount(amount: Double, frequency: RecurrenceFrequency): Double {
        return amount * monthlyMultiplier(frequency)
    }
    
    /**
     * Convert monthly amount back to frequency equivalent.
     * Useful for displaying "per week" costs from monthly budgets.
     * 
     * @param monthlyAmount The monthly amount
     * @param frequency The target recurrence frequency
     * @return Amount per period for the given frequency
     */
    fun fromMonthlyAmount(monthlyAmount: Double, frequency: RecurrenceFrequency): Double {
        val multiplier = monthlyMultiplier(frequency)
        return if (multiplier > 0) monthlyAmount / multiplier else monthlyAmount
    }
    
    /**
     * Calculate the next due date based on current date and frequency.
     * 
     * @param currentDate Current due date in milliseconds
     * @param frequency Recurrence frequency
     * @return Next due date in milliseconds
     */
    fun calculateNextDate(currentDate: Long, frequency: RecurrenceFrequency): Long {
        val normalizedCurrentDate = normalizeToDateOnly(currentDate)
        val nextDate = when (frequency) {
            RecurrenceFrequency.WEEKLY,
            RecurrenceFrequency.BIWEEKLY -> addFrequencyInterval(normalizedCurrentDate, frequency)
            RecurrenceFrequency.MONTHLY,
            RecurrenceFrequency.QUARTERLY,
            RecurrenceFrequency.SEMI_ANNUALLY,
            RecurrenceFrequency.ANNUALLY -> addFrequencyInterval(normalizedCurrentDate, frequency)
            RecurrenceFrequency.IRREGULAR -> normalizedCurrentDate
        }
        return normalizeToDateOnly(nextDate)
    }
    
    /**
     * Calculate previous due date (useful for history/tracking).
     * 
     * @param currentDate Current due date in milliseconds
     * @param frequency Recurrence frequency
     * @return Previous due date in milliseconds
     */
    fun calculatePreviousDate(currentDate: Long, frequency: RecurrenceFrequency): Long {
        val normalizedCurrentDate = normalizeToDateOnly(currentDate)
        val previousDate = when (frequency) {
            RecurrenceFrequency.WEEKLY,
            RecurrenceFrequency.BIWEEKLY,
            RecurrenceFrequency.MONTHLY,
            RecurrenceFrequency.QUARTERLY,
            RecurrenceFrequency.SEMI_ANNUALLY,
            RecurrenceFrequency.ANNUALLY -> addFrequencyInterval(normalizedCurrentDate, frequency, forward = false)
            RecurrenceFrequency.IRREGULAR -> normalizedCurrentDate
        }
        return normalizeToDateOnly(previousDate)
    }

    fun addFrequencyInterval(baseDate: Long, frequency: RecurrenceFrequency, forward: Boolean = true): Long {
        val direction = if (forward) 1 else -1
        frequency.fixedIntervalDays?.let { fixedDays ->
            return TimePeriodUtils.addDays(baseDate, fixedDays * direction)
        }

        frequency.calendarMonths?.let { months ->
            return TimePeriodUtils.addMonths(baseDate, months * direction)
        }

        return baseDate
    }
    
    /**
     * Check if a date is due or overdue.
     * 
     * @param nextDueDate Next due date in milliseconds
     * @param referenceDate Reference date to check against (default: now)
     * @return True if due or overdue
     */
    fun isDue(nextDueDate: Long, referenceDate: Long): Boolean {
        return nextDueDate <= referenceDate
    }
    
    /**
     * Check if a date is upcoming within specified days.
     * 
     * @param nextDueDate Next due date in milliseconds
     * @param daysWithin Number of days to consider "upcoming"
     * @param referenceDate Reference date (default: now)
     * @return True if due within the specified days
     */
    fun isUpcoming(
        nextDueDate: Long, 
        daysWithin: Int = 7, 
        referenceDate: Long
    ): Boolean {
        val windowEnd = TimePeriodUtils.addDays(referenceDate, daysWithin)
        return nextDueDate in referenceDate..windowEnd
    }
    
    /**
     * Get a human-readable label for the frequency.
     * 
     * @param frequency The recurrence frequency
     * @return Display label (e.g., "Weekly", "Every 2 weeks")
     */
    fun getFrequencyLabel(frequency: RecurrenceFrequency): String {
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> "Weekly"
            RecurrenceFrequency.BIWEEKLY -> "Every 2 weeks"
            RecurrenceFrequency.MONTHLY -> "Monthly"
            RecurrenceFrequency.QUARTERLY -> "Quarterly"
            RecurrenceFrequency.SEMI_ANNUALLY -> "Every 6 months"
            RecurrenceFrequency.ANNUALLY -> "Annually"
            RecurrenceFrequency.IRREGULAR -> "Irregular"
        }
    }
    
    /**
     * Calculate total annual cost from monthly amount.
     * 
     * @param monthlyAmount Monthly cost
     * @return Annual cost
     */
    fun toAnnualAmount(monthlyAmount: Double): Double {
        return monthlyAmount * 12
    }
}
