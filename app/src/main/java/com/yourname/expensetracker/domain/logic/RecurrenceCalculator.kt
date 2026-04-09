package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Utility class for all recurrence-related calculations.
 * Single source of truth for monthly cost conversions and next date calculations.
 */
object RecurrenceCalculator {
    
    /**
     * Multipliers for converting various frequencies to monthly equivalent.
     * Based on standard approximations:
     * - WEEKLY: 52 weeks / 12 months = 4.33
     * - BIWEEKLY: 26 bi-weeks / 12 months = 2.17
     * - MONTHLY: 1.0
     * - QUARTERLY: 1/3 = 0.33
     * - SEMI_ANNUALLY: 1/6 = 0.17
     * - ANNUALLY: 1/12 = 0.08
     * - IRREGULAR: treated as monthly (1.0)
     */
    private val MONTHLY_MULTIPLIERS = mapOf(
        RecurrenceFrequency.WEEKLY to 4.33,
        RecurrenceFrequency.BIWEEKLY to 2.17,
        RecurrenceFrequency.MONTHLY to 1.0,
        RecurrenceFrequency.QUARTERLY to 1.0 / 3.0,
        RecurrenceFrequency.SEMI_ANNUALLY to 1.0 / 6.0,
        RecurrenceFrequency.ANNUALLY to 1.0 / 12.0,
        RecurrenceFrequency.IRREGULAR to 1.0
    )
    
    /**
     * Convert any frequency amount to monthly equivalent.
     * 
     * @param amount The amount per period
     * @param frequency The recurrence frequency
     * @return Monthly equivalent amount
     */
    fun toMonthlyAmount(amount: Double, frequency: RecurrenceFrequency): Double {
        val multiplier = MONTHLY_MULTIPLIERS[frequency] ?: 1.0
        return amount * multiplier
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
        val multiplier = MONTHLY_MULTIPLIERS[frequency] ?: 1.0
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
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> TimePeriodUtils.addDays(currentDate, 7)
            RecurrenceFrequency.BIWEEKLY -> TimePeriodUtils.addDays(currentDate, 14)
            RecurrenceFrequency.MONTHLY -> TimePeriodUtils.addMonths(currentDate, 1)
            RecurrenceFrequency.QUARTERLY -> TimePeriodUtils.addMonths(currentDate, 3)
            RecurrenceFrequency.SEMI_ANNUALLY -> TimePeriodUtils.addMonths(currentDate, 6)
            RecurrenceFrequency.ANNUALLY -> TimePeriodUtils.addYears(currentDate, 1)
            RecurrenceFrequency.IRREGULAR -> {
                // For irregular expenses, keep the same date or add 1 month as default
                TimePeriodUtils.addMonths(currentDate, 1)
            }
        }
    }
    
    /**
     * Calculate previous due date (useful for history/tracking).
     * 
     * @param currentDate Current due date in milliseconds
     * @param frequency Recurrence frequency
     * @return Previous due date in milliseconds
     */
    fun calculatePreviousDate(currentDate: Long, frequency: RecurrenceFrequency): Long {
        return when (frequency) {
            RecurrenceFrequency.WEEKLY -> TimePeriodUtils.addDays(currentDate, -7)
            RecurrenceFrequency.BIWEEKLY -> TimePeriodUtils.addDays(currentDate, -14)
            RecurrenceFrequency.MONTHLY -> TimePeriodUtils.addMonths(currentDate, -1)
            RecurrenceFrequency.QUARTERLY -> TimePeriodUtils.addMonths(currentDate, -3)
            RecurrenceFrequency.SEMI_ANNUALLY -> TimePeriodUtils.addMonths(currentDate, -6)
            RecurrenceFrequency.ANNUALLY -> TimePeriodUtils.addYears(currentDate, -1)
            RecurrenceFrequency.IRREGULAR -> {
                TimePeriodUtils.addMonths(currentDate, -1)
            }
        }
    }
    
    /**
     * Check if a date is due or overdue.
     * 
     * @param nextDueDate Next due date in milliseconds
     * @param referenceDate Reference date to check against (default: now)
     * @return True if due or overdue
     */
    fun isDue(nextDueDate: Long, referenceDate: Long = System.currentTimeMillis()): Boolean {
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
        referenceDate: Long = System.currentTimeMillis()
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
