package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Half-open `[startInclusive, endExclusive)` previous-period comparison window.
 */
data class PeriodComparisonWindow(
    val startInclusive: Long,
    val endExclusive: Long
)

/**
 * F-R20b-a: single shared rule for the previous-period comparison window of a
 * calendar-MONTH period. Pure: no clock reads; the caller supplies every instant and
 * the zone is the system default zone used by [TimePeriodUtils].
 *
 * The prior window of a month is the PREVIOUS CALENDAR MONTH, cut at the same elapsed
 * point (calendar days into the month + local wall-clock time) and clamped to the
 * previous month's end. Mar 31 therefore compares against all of February and
 * Feb 28 against Jan 1..Jan 28, instead of an equal-length block that straddles two
 * months. Calendar-day math runs on local dates so DST transitions do not shift it.
 *
 * Non-month ranges keep the legacy rule (same length immediately before the window).
 */
object PeriodComparisonWindows {

    /**
     * Previous-month window for a month starting at [currentMonthStart], cut at [cut].
     * With [cutInclusive] the instant [cut] itself is inside the window (window ends 1 ms
     * after the mapped instant); otherwise [cut] is an exclusive bound.
     */
    fun previousMonthWindow(
        currentMonthStart: Long,
        cut: Long,
        cutInclusive: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): PeriodComparisonWindow {
        val (priorStart, priorEnd) = TimePeriodUtils.getMonthRange(currentMonthStart, -1)
        val currentStartDate = Instant.ofEpochMilli(currentMonthStart).atZone(zone).toLocalDate()
        val priorStartDate = Instant.ofEpochMilli(priorStart).atZone(zone).toLocalDate()
        val cutZoned = Instant.ofEpochMilli(cut).atZone(zone)

        val elapsedDays = ChronoUnit.DAYS
            .between(currentStartDate, cutZoned.toLocalDate())
            .coerceAtLeast(0L)
        // ZonedDateTime.of resolves a wall-clock time inside a DST gap by shifting forward.
        val mapped = ZonedDateTime.of(
            priorStartDate.plusDays(elapsedDays),
            cutZoned.toLocalTime(),
            zone
        ).toInstant().toEpochMilli()

        val mappedEnd = if (cutInclusive) mapped + 1L else mapped
        return PeriodComparisonWindow(priorStart, mappedEnd.coerceIn(priorStart, priorEnd))
    }

    /**
     * Previous window for a `[start, end)` range as passed by the Home/dashboard data path.
     * A range that is exactly one calendar month uses [previousMonthWindow] with [end] as the
     * exclusive cut; any other range keeps the legacy same-length window ending at [start].
     */
    fun previousWindowForRange(start: Long, end: Long): PeriodComparisonWindow {
        if (isCalendarMonth(start, end)) {
            return previousMonthWindow(start, end, cutInclusive = false)
        }
        val daysInPeriod = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
        return PeriodComparisonWindow(TimePeriodUtils.addDays(start, -daysInPeriod), start)
    }

    private fun isCalendarMonth(start: Long, end: Long): Boolean =
        start == TimePeriodUtils.getStartOfMonth(start) && end == TimePeriodUtils.getEndOfMonth(start)
}
