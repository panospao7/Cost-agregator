package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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
        val zone = ZoneId.systemDefault()

        // Use TimePeriodUtils for consistent start-of-day logic.
        val startOfDay = TimePeriodUtils.getStartOfDay(evaluationTime)
        // T4B-3: The evaluation date is derived once from the caller-provided
        // evaluation time (never the wall clock) in the system-default timezone.
        val evalDate = Instant.ofEpochMilli(startOfDay).atZone(zone).toLocalDate()

        return when (period) {
            BudgetPeriod.DAILY -> {
                val start = startOfDay
                val end = evalDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
            }
            BudgetPeriod.WEEKLY -> {
                // NEW-P6-016: Use ISO week fields instead of Calendar.WEEK_OF_YEAR,
                // which is locale-dependent.  Converting to java.time.LocalDate for
                // ISO-aware week arithmetic ensures consistent week boundaries.
                val anchorDayOfWeek = Instant.ofEpochMilli(anchorDate)
                    .atZone(zone).toLocalDate().dayOfWeek
                var weekDate = evalDate
                while (weekDate.dayOfWeek != anchorDayOfWeek) {
                    weekDate = weekDate.minusDays(1)
                }
                val start = weekDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = weekDate.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
            }
            BudgetPeriod.MONTHLY -> {
                // T4B-3: java.time month-anchoring preserving the previous
                // java.util.Calendar semantics exactly:
                // - the anchor day comes from the budget's anchor date;
                // - if the evaluation day is before the (coerced) anchor day, the
                //   cycle started in the previous month;
                // - the anchor day is clamped to the target month length on both
                //   the start and the exclusive end boundary.
                val anchorDay = Instant.ofEpochMilli(anchorDate).atZone(zone).toLocalDate().dayOfMonth
                val evalDay = evalDate.dayOfMonth

                // Coerce the anchor day by the max days of the evaluated month.
                val adjustedAnchorDay = anchorDay.coerceAtMost(evalDate.lengthOfMonth())
                val hasPassedAnchorThisMonth = evalDay >= adjustedAnchorDay

                val startMonthDate = if (hasPassedAnchorThisMonth) evalDate else evalDate.minusMonths(1)
                val start = startMonthDate.withDayOfMonth(anchorDay.coerceAtMost(startMonthDate.lengthOfMonth()))
                    .atStartOfDay(zone).toInstant().toEpochMilli()

                val endMonthDate = startMonthDate.plusMonths(1)
                val end = endMonthDate.withDayOfMonth(anchorDay.coerceAtMost(endMonthDate.lengthOfMonth()))
                    .atStartOfDay(zone).toInstant().toEpochMilli()

                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
            }
            BudgetPeriod.YEARLY -> {
                // T4B-3: java.time anniversary anchoring preserving the previous
                // java.util.Calendar semantics exactly (a Feb 29 anchor clamps to
                // Feb 28 in non-leap years on both boundaries).
                val anchorDateInZone = Instant.ofEpochMilli(anchorDate).atZone(zone).toLocalDate()
                val anchorMonth = anchorDateInZone.monthValue
                val anchorDay = anchorDateInZone.dayOfMonth

                val currentMonth = evalDate.monthValue
                val currentDay = evalDate.dayOfMonth
                val adjustedDay = anchorDay.coerceAtMost(evalDate.lengthOfMonth())

                // Check if we passed the anniversary this year.
                val passed = currentMonth > anchorMonth ||
                    (currentMonth == anchorMonth && currentDay >= adjustedDay)

                val startYear = if (passed) evalDate.year else evalDate.year - 1
                val startDate = LocalDate.of(
                    startYear,
                    anchorMonth,
                    anchorDay.coerceAtMost(YearMonth.of(startYear, anchorMonth).lengthOfMonth())
                )
                val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()

                val end = startDate.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
                PeriodRange(kind = PeriodKind.CUSTOM, startInclusiveMillis = start, endExclusiveMillis = end, label = "Budget")
            }
        }
    }
}
