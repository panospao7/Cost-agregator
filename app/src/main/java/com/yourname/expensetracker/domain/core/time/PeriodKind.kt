package com.yourname.expensetracker.domain.core.time

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.time.ZoneId

/**
 * Semantic classification of time periods used throughout the app.
 *
 * Each kind explicitly declares whether it represents a **calendar period**
 * (bounded by calendar boundaries like month-start) or a **rolling window**
 * (a fixed number of days back from the current moment).
 *
 * ## Calendar periods (use calendar-aware helpers)
 *
 * | Kind | Typical helper |
 * |------|---------------|
 * | `TODAY` | `getDayRange(now)` |
 * | `THIS_WEEK` | `getWeekRange(now)` |
 * | `THIS_MONTH` | `getMonthRange(now)` |
 * | `THIS_QUARTER` | `getQuarterRange(now)` |
 * | `THIS_YEAR` | `getYearRange(now)` |
 * | `LAST_WEEK` | `getWeekRange(now, weekOffset = -1)` |
 * | `LAST_MONTH` | `getMonthRange(now, monthOffset = -1)` |
 * | `LAST_QUARTER` | `getQuarterRange(now, quarterOffset = -1)` |
 * | `LAST_YEAR` | `getYearRange(now, yearOffset = -1)` |
 *
 * ## Rolling windows (use explicit rolling helpers)
 *
 * | Kind | Typical helper |
 * |------|---------------|
 * | `LAST_7_DAYS` | `getLastNCalendarDaysRange(now, 7)` or `getLastNCompleteDaysRange(now, 7)` |
 * | `LAST_30_DAYS` | `getLastNCalendarDaysRange(now, 30)` or `getLastNCompleteDaysRange(now, 30)` |
 *
 * ## Important rules
 *
 * 1. **Calendar labels must use calendar ranges.** "This Month" = calendar month,
 *    not 30 rolling days.
 * 2. **Rolling labels must use rolling helpers.** "Last 30 Days" = explicit
 *    lookback window.
 * 3. **Never use `getLastNDaysRange(now, 30)` for "This Month".** This is the
 *    single most common bug found in the time usage audit.
 */
enum class PeriodKind {
    /** Current calendar day (midnight to next midnight). */
    TODAY,

    /** Monday-start current calendar week. */
    THIS_WEEK,

    /** Monday-start previous calendar week. */
    LAST_WEEK,

    /** Last 7 calendar days including today. */
    LAST_7_DAYS,

    /** Current calendar month (1st to 1st of next month). */
    THIS_MONTH,

    /** Previous calendar month. */
    LAST_MONTH,

    /** Last 30 calendar days including today. */
    LAST_30_DAYS,

    /** Current calendar quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec). */
    THIS_QUARTER,

    /** Previous calendar quarter. */
    LAST_QUARTER,

    /** Current calendar year (Jan 1 to Jan 1 of next year). */
    THIS_YEAR,

    /** Previous calendar year. */
    LAST_YEAR,

    /** User-defined or unclassified period. */
    CUSTOM
}

/**
 * Converts this [PeriodKind] to a concrete [PeriodRange] anchored at [now].
 *
 * Delegates to [TimePeriodUtils] for calendar-aware boundary computation.
 * [PeriodKind.CUSTOM] requires explicit [customStart]/[customEnd] parameters
 * and will throw [IllegalArgumentException] if they are not provided.
 *
 * @param now Anchor timestamp (epoch millis) for calendar boundary computation.
 * @param zoneId The timezone for the range (defaults to system default).
 * @param customStart Explicit start for [CUSTOM] periods (optional).
 * @param customEnd Explicit end for [CUSTOM] periods (optional).
 * @throws IllegalArgumentException for [CUSTOM] without explicit bounds.
 */
fun PeriodKind.toPeriodRange(
    now: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    customStart: Long? = null,
    customEnd: Long? = null
): PeriodRange {
    val (start, end) = when (this) {
        PeriodKind.TODAY -> {
            val dayStart = TimePeriodUtils.getStartOfDay(now)
            dayStart to TimePeriodUtils.getEndOfDay(now)
        }
        PeriodKind.THIS_WEEK -> TimePeriodUtils.getWeekRange(now, 0)
        PeriodKind.LAST_WEEK -> TimePeriodUtils.getWeekRange(now, -1)
        PeriodKind.LAST_7_DAYS -> TimePeriodUtils.getLastNCalendarDaysRange(now, 7)
        PeriodKind.THIS_MONTH -> TimePeriodUtils.getMonthRange(now)
        PeriodKind.LAST_MONTH -> TimePeriodUtils.getMonthRange(
            TimePeriodUtils.addMonths(now, -1)
        )
        PeriodKind.LAST_30_DAYS -> TimePeriodUtils.getLastNCalendarDaysRange(now, 30)
        PeriodKind.THIS_QUARTER -> TimePeriodUtils.getQuarterRange(now)
        PeriodKind.LAST_QUARTER -> {
            val lastQuarterStart = TimePeriodUtils.getStartOfQuarter(
                TimePeriodUtils.addMonths(now, -3)
            )
            val lastQuarterEnd = TimePeriodUtils.getEndOfQuarter(
                TimePeriodUtils.addMonths(now, -3)
            )
            lastQuarterStart to lastQuarterEnd
        }
        PeriodKind.THIS_YEAR -> TimePeriodUtils.getYearRange(now)
        PeriodKind.LAST_YEAR -> {
            val lastYearStart = TimePeriodUtils.getStartOfYear(
                TimePeriodUtils.addYears(now, -1)
            )
            val lastYearEnd = TimePeriodUtils.getStartOfYear(now)
            lastYearStart to lastYearEnd
        }
        PeriodKind.CUSTOM -> {
            val s = customStart ?: throw IllegalArgumentException(
                "PeriodKind.CUSTOM requires explicit customStart"
            )
            val e = customEnd ?: throw IllegalArgumentException(
                "PeriodKind.CUSTOM requires explicit customEnd"
            )
            s to e
        }
    }
    return PeriodRange(
        kind = this,
        startInclusiveMillis = start,
        endExclusiveMillis = end,
        zoneId = zoneId,
        label = name
    )
}
