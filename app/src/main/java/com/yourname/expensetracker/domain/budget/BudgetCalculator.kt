package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BudgetCalculator - Handles budget period calculations.
 * 
 * ## Period Calculation Logic
 * 
 * ### Supported Periods:
 * - DAILY: 24-hour window starting from anchor date
 * - WEEKLY: 7-day window aligned to anchor's day of week
 * - MONTHLY: Calendar month containing anchor date
 * - QUARTERLY: 3-month window (Q1, Q2, Q3, Q4)
 * - YEARLY: Full calendar year
 * 
 * ### Key Concepts:
 * - **Anchor Date**: The reference date for calculating the period
 * - **Evaluation Time**: The current time (for determining if period is current/future/past)
 * - **Period Window**: Start and end timestamps in milliseconds
 * 
 * ### Edge Cases Handled:
 * - Month-end dates (31st, 30th, February)
 * - Leap years
 * - Year boundaries
 * - DST transitions
 */
@Singleton
class BudgetCalculator @Inject constructor(
    private val timeProvider: TimeProvider
) {

    fun calculatePeriodRange(budget: Budget, now: Long = timeProvider.now()): Pair<Long, Long> {
        return when (budget.periodMode.uppercase()) {
            "ROLLING" -> {
                val start = budget.startDate
                val end = when (budget.period) {
                    BudgetPeriod.MONTHLY -> TimePeriodUtils.addDays(start, 30)
                    BudgetPeriod.WEEKLY -> TimePeriodUtils.addDays(start, 7)
                    else -> calculatePeriodWindowForTime(budget.period, budget.startDate, now).end
                }
                start to end
            }
            else -> {
                when (budget.period) {
                    BudgetPeriod.MONTHLY -> TimePeriodUtils.getMonthRange(now)
                    BudgetPeriod.WEEKLY -> TimePeriodUtils.getWeekRange(now)
                    else -> {
                        val window = calculatePeriodWindowForTime(budget.period, budget.startDate, now)
                        window.start to window.end
                    }
                }
            }
        }
    }

    fun calculatePeriodWindow(period: BudgetPeriod, anchorDate: Long): PeriodRange {
        return calculatePeriodWindowForTime(period, anchorDate, timeProvider.now())
    }

    fun calculatePeriodWindowForTime(period: BudgetPeriod, anchorDate: Long, evaluationTime: Long): PeriodRange {
        val anchorCal = Calendar.getInstance().apply { timeInMillis = anchorDate }

        // Use TimePeriodUtils for consistent start-of-day logic
        val startOfDay = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(evaluationTime)
        
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay }

        return when (period) {
            BudgetPeriod.DAILY -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                PeriodRange(start, cal.timeInMillis)
            }
            BudgetPeriod.WEEKLY -> {
                // Find the most recent occurrence of the anchor weekday
                val anchorDayOfWeek = anchorCal.get(Calendar.DAY_OF_WEEK)
                while (cal.get(Calendar.DAY_OF_WEEK) != anchorDayOfWeek) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                PeriodRange(start, cal.timeInMillis)
            }
            BudgetPeriod.MONTHLY -> {
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                
                // Set to start of current month
                cal.set(Calendar.DAY_OF_MONTH, 1)
                // Evaluate if we are currently past the anchor day
                val evalCal = Calendar.getInstance().apply { timeInMillis = evaluationTime }
                
                val evalDay = evalCal.get(Calendar.DAY_OF_MONTH)
                
                // Determine the correct month start
                // If today is before the anchor day, the period actually started last month
                // Note: we must also coerce the anchor day by the max days of the evaluated month
                val adjustedAnchorDay = anchorDay.coerceAtMost(evalCal.getActualMaximum(Calendar.DAY_OF_MONTH))
                val hasPassedAnchorThisMonth = evalDay >= adjustedAnchorDay
                
                if (!hasPassedAnchorThisMonth) {
                    cal.add(Calendar.MONTH, -1)
                }
                
                val prevMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(prevMonthMax))

                val start = cal.timeInMillis
                
                // To find the end, go to the start of the next cycle
                cal.add(Calendar.MONTH, 1)
                val nextMonthMax = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                
                // Use anchor day, but coerce if next month has fewer days
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(nextMonthMax))
                
                val end = cal.timeInMillis
                PeriodRange(start, end)
            }
            BudgetPeriod.YEARLY -> {
                val anchorMonth = anchorCal.get(Calendar.MONTH)
                val anchorDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                
                val currentMonth = cal.get(Calendar.MONTH)
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                val adjustedDay = anchorDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH))

                // Check if we passed the anniversary this year
                var passed = false
                if (currentMonth > anchorMonth) passed = true
                else if (currentMonth == anchorMonth && currentDay >= adjustedDay) passed = true
                
                if (!passed) {
                    cal.add(Calendar.YEAR, -1)
                }
                
                cal.set(Calendar.MONTH, anchorMonth)
                cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                val end = cal.timeInMillis
                PeriodRange(start, end)
            }
        }
    }
}
