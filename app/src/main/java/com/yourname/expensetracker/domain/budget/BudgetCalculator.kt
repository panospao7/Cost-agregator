package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BudgetCalculator — Canonical authority for budget-period boundaries.
 *
 * ## Two modes
 *
 * | `periodMode` | Semantics |
 * |---|---|
 * | `ROLLING` | Anchor-based cycle containing `now`. Window boundaries depend on the budget's `startDate` anchor. |
 * | `CALENDAR` (or any non-ROLLING value) | Natural calendar boundaries via [TimePeriodUtils]. DAILY → today, WEEKLY → Mon–Mon, MONTHLY → 1st–1st, YEARLY → Jan 1 – Jan 1. |
 *
 * ## Key concepts
 *
 * - **Anchor Date** (`Budget.startDate`): reference date for ROLLING period arithmetic.
 * - **Evaluation Time**: the point-in-time used to determine which cycle is "active" (defaults to `timeProvider.now()`).
 * - **Period Window**: half-open `[start, end)` timestamps in milliseconds.
 *
 * ## Convenience vs. explicit API
 *
 * - [calculatePeriodWindow] is a convenience wrapper that reads `timeProvider.now()` implicitly.
 *   **Do not use it** when the caller needs a historical or next-window derivation; use
 *   [calculatePeriodWindowForTime] with an explicit evaluation time instead.
 *
 * ## Edge cases handled
 *
 * - Month-end dates (31st, 30th, February)
 * - Leap years
 * - Year boundaries
 * - DST transitions
 */
@Singleton
class BudgetCalculator @Inject constructor(
    private val timeProvider: TimeProvider
) {

    /**
     * BUD-10: Valid periodMode values. Any unrecognized value will throw
     * an IllegalArgumentException instead of silently defaulting to CALENDAR.
     */
    enum class PeriodMode {
        ROLLING,
        CALENDAR;

        companion object {
            /**
             * Safe parser: returns the matching enum or throws for invalid values.
             * BUD-10: Previously, any non-ROLLING string silently became CALENDAR,
             * masking data-integrity issues.
             */
            fun fromString(value: String): PeriodMode {
                return try {
                    valueOf(value.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Invalid periodMode '$value'. Valid values: ${entries.joinToString(", ")}"
                    )
                }
            }
        }
    }

    fun calculatePeriodRange(budget: Budget, now: Long = timeProvider.now()): Pair<Long, Long> {
        // BUD-10: Validate periodMode explicitly instead of silently defaulting.
        val mode = PeriodMode.fromString(budget.periodMode)
        return when (mode) {
            PeriodMode.ROLLING -> {
                // Resolve the active anchored cycle containing `now` instead of
                // pinning start = budget.startDate forever.
                val window = calculatePeriodWindowForTime(budget.period, budget.startDate, now)
                window.startInclusiveMillis to window.endExclusiveMillis
            }
            PeriodMode.CALENDAR -> {
                // Use natural calendar boundaries via TimePeriodUtils.
                // The anchor date is irrelevant for calendar windows.
                when (budget.period) {
                    BudgetPeriod.DAILY -> TimePeriodUtils.getDayRange(now)
                    BudgetPeriod.WEEKLY -> TimePeriodUtils.getWeekRange(now)
                    BudgetPeriod.MONTHLY -> TimePeriodUtils.getMonthRange(now)
                    BudgetPeriod.YEARLY -> TimePeriodUtils.getYearRange(now)
                }
            }
        }
    }

    /**
     * Convenience wrapper: computes an anchor-based period window using `timeProvider.now()`
     * as the evaluation time.
     *
     * **Important:** this method reads `now()` implicitly.
     * If you need a historical or next-window derivation, call
     * [calculatePeriodWindowForTime] with an explicit `evaluationTime` instead.
     */
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
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = cal.timeInMillis, label = "Budget")
            }
            BudgetPeriod.WEEKLY -> {
                // NEW-P6-016: Use ISO week fields instead of Calendar.WEEK_OF_YEAR,
                // which is locale-dependent.  Converting to java.time.LocalDate for
                // ISO-aware week arithmetic ensures consistent week boundaries.
                val zone = java.time.ZoneId.systemDefault()
                val anchorDayOfWeek = java.time.Instant.ofEpochMilli(anchorDate)
                    .atZone(zone).toLocalDate().dayOfWeek
                var evalDate = java.time.Instant.ofEpochMilli(startOfDay)
                    .atZone(zone).toLocalDate()
                while (evalDate.dayOfWeek != anchorDayOfWeek) {
                    evalDate = evalDate.minusDays(1)
                }
                val start = evalDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = evalDate.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
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
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
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
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
            }
        }
    }
}
