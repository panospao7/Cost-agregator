package com.yourname.expensetracker.domain.core.time

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.time.ZoneId

// M04 OPEN: PeriodKind.toPeriodRange() records caller zoneId but delegates
// to TimePeriodUtils which uses system-default Calendar internally.
// TODO: migrate TimePeriodUtils to java.time + ZoneId.
// TODO (M12): Rename LAST_7_DAYS semantics explicitly.
// LAST_7_CALENDAR_DAYS_INCLUDING_TODAY vs TRAILING_7_DAYS_TO_NOW.

/**
 * Semantic classification of time periods used throughout the app.
 *
 * Each kind explicitly declares whether it represents a **calendar period**
 * (bounded by calendar boundaries like month-start) or a **rolling window**
 * (a fixed number of days back from the current moment).
 *
 * ## Half-open interval contract
 *
 * All ranges follow the **`[startInclusive, endExclusive)`** convention.
 * A timestamp `t` belongs to a period when:
 * ```
 *   t >= periodStart && t < periodEnd
 * ```
 *
 * ## 🌍 DST-aware timezone math (M04)
 *
 * Calendar boundaries (day start, month start, etc.) are computed in the
 * **system-default timezone** (see [ZoneId.systemDefault]). On DST transition
 * days the length of a calendar day is **23 or 25 hours** instead of 24.
 *
 * - **Spring-forward days** (e.g. 2025-03-30 in Europe): clock jumps from
 *   02:59:59 to 04:00:00. The day contains only **23 hours**.
 * - **Fall-back days** (e.g. 2025-10-26 in Europe): clock falls back from
 *   03:59:59 to 03:00:00. The day contains **25 hours**.
 *
 * Current computation uses `getStartOfDay(now)`/`getEndOfDay(now)` which call
 * `java.time.LocalDate.atStartOfDay(zoneId).toInstant()`. This correctly accounts
 * for zone offsets but callers should be aware that:
 * - `THIS_MONTH` / `LAST_MONTH` ranges on DST transition dates span 23 or 25 hours.
 * - `LAST_7_DAYS` / `LAST_30_DAYS` rolling windows may include one short or long day.
 * - `THIS_YEAR` / `LAST_YEAR` are unaffected by DST (annual boundaries are midnight Jan 1).
 *
 * For UTC-based analytics or export, pass [ZoneId.of("UTC")] explicitly.
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

    /** ISO week (Monday-start, locale-independent) for the current calendar week. */
    THIS_WEEK,

    /** Monday-start previous calendar week. */
    LAST_WEEK,

    /**
     * Last 7 calendar days including today.
     *
     * Example: If today is Wednesday May 7, shows Wed May 7 through Thu May 1
     * (7 calendar days including today). For trailing 7 complete days ending at
     * midnight, use CUSTOM.
     *
     * // M12 OPEN: LAST_7_DAYS includes the full current calendar day,
     * // meaning the range includes the future remainder until midnight.
     * // For "last 7 complete days" semantics, use endExclusive = start of today.
     */
    LAST_7_DAYS,

    /**
     * Calendar month in the given timezone (1st to 1st of next month).
     * February has 28 or 29 days depending on the year.
     */
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

// M04-FIXED: Zone-aware toPeriodRange using java.time.
fun PeriodKind.toPeriodRangeZoned(nowMillis: Long, zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()): com.yourname.expensetracker.domain.core.time.PeriodRange {
    val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val today = now.toLocalDate()
    return when (this) {
        PeriodKind.TODAY -> {
            val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Today")
        }
        PeriodKind.THIS_WEEK -> {
            val start = today.with(java.time.DayOfWeek.MONDAY).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.with(java.time.DayOfWeek.MONDAY).plusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "This week")
        }
        PeriodKind.LAST_WEEK -> {
            val start = today.with(java.time.DayOfWeek.MONDAY).minusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.with(java.time.DayOfWeek.MONDAY).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Last week")
        }
        PeriodKind.LAST_7_DAYS -> {
            val end = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val start = today.minusDays(7).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Last 7 days")
        }
        PeriodKind.THIS_MONTH -> {
            val start = today.withDayOfMonth(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "This month")
        }
        PeriodKind.LAST_MONTH -> {
            val firstOfThisMonth = today.withDayOfMonth(1)
            val start = firstOfThisMonth.minusMonths(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = firstOfThisMonth.atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Last month")
        }
        PeriodKind.LAST_30_DAYS -> {
            val end = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val start = today.minusDays(30).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Last 30 days")
        }
        PeriodKind.THIS_QUARTER -> {
            val quarterStartMonth = ((today.monthValue - 1) / 3) * 3 + 1
            val startDay = today.withMonth(quarterStartMonth).withDayOfMonth(1)
            val start = startDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = startDay.plusMonths(3).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "This quarter")
        }
        PeriodKind.LAST_QUARTER -> {
            val quarterStartMonth = ((today.monthValue - 1) / 3) * 3 + 1
            val thisQuarterStart = today.withMonth(quarterStartMonth).withDayOfMonth(1).atStartOfDay(zoneId)
            val start = thisQuarterStart.minusMonths(3).toInstant().toEpochMilli()
            val end = thisQuarterStart.toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Last quarter")
        }
        PeriodKind.THIS_YEAR -> {
            val start = today.withDayOfYear(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.withDayOfYear(1).plusYears(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "This year")
        }
        PeriodKind.LAST_YEAR -> {
            val start = today.withDayOfYear(1).minusYears(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.withDayOfYear(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, "Last year")
        }
        PeriodKind.CUSTOM -> {
            // CUSTOM without explicit bounds defaults to last 30 days from now
            val start = today.minusDays(30).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            PeriodRange(this, start, end, zoneId, this.name)
        }
    }
}
