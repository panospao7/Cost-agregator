package com.yourname.expensetracker.ui.screens.analytics

import com.yourname.expensetracker.domain.analytics.TimePeriod
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Half-open `[startInclusive, endExclusive)` window used as the comparison baseline.
 * An empty window (start == end) means "no comparison available".
 */
internal data class ComparisonWindow(
    val startInclusive: Long,
    val endExclusive: Long
) {
    val isEmpty: Boolean get() = endExclusive <= startInclusive
}

/**
 * V-E-12: like-for-like (period-to-date) comparison windows for Analytics.
 *
 * The prior window is the previous **calendar** period (previous day / ISO week /
 * month / quarter / year), cut at the same elapsed point as the current period:
 * the same number of calendar days into the period and the same local wall-clock
 * time. A steady daily spend therefore compares at 0% for every period, and
 * Mar 31 vs February is cut at the end of February instead of comparing 31 days of
 * spend with 28.
 *
 * The mapped instant is inclusive (the window ends 1 ms after it) so an expense
 * stamped exactly at the elapsed point counts on both sides.
 *
 * All calendar arithmetic is DST-safe (java.time local-date math plus
 * [TimePeriodUtils] range helpers) and uses the system default zone like the rest
 * of the analytics pipeline.
 */
internal object AnalyticsComparisonWindows {

    /**
     * Prior comparison window for [period]. [currentStart] is the start of the
     * current period as computed by the ViewModel. [TimePeriod.ALL] has no
     * comparison baseline and returns an empty window.
     */
    fun priorWindow(period: TimePeriod, currentStart: Long, now: Long): ComparisonWindow {
        if (period == TimePeriod.ALL) return ComparisonWindow(currentStart, currentStart)

        val (priorStart, priorEnd) = previousCalendarPeriod(period, now)
        val zone = ZoneId.systemDefault()
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        val currentStartDate = Instant.ofEpochMilli(currentStart).atZone(zone).toLocalDate()
        val priorStartDate = Instant.ofEpochMilli(priorStart).atZone(zone).toLocalDate()

        val elapsedDays = ChronoUnit.DAYS
            .between(currentStartDate, nowZoned.toLocalDate())
            .coerceAtLeast(0L)
        // ZonedDateTime.of resolves a wall-clock time inside a DST gap by shifting forward.
        val mappedInstant = ZonedDateTime.of(
            priorStartDate.plusDays(elapsedDays),
            nowZoned.toLocalTime(),
            zone
        ).toInstant().toEpochMilli()

        val cutEnd = (mappedInstant + 1L).coerceIn(priorStart, priorEnd)
        return ComparisonWindow(priorStart, cutEnd)
    }

    /**
     * Exclusive upper bound for prior-year dates in the year-over-year comparison:
     * only dates before the same calendar date one year ago count (year-to-date vs
     * prior-year-to-date).
     */
    fun priorYearCutoff(now: Long): Long = TimePeriodUtils.addYears(now, -1)

    /**
     * The like-for-like cut (prior window end and YoY cutoff) depends on the local
     * time-of-day, so the comparison instant is `now` truncated to the minute. The same
     * value is used for the cut and for the analytics cache key, so a cached result is
     * never reused across a minute change.
     */
    fun comparisonMinute(now: Long): Long = now - Math.floorMod(now, MILLIS_PER_MINUTE)

    /**
     * Whether an expense dated [date] counts in the year-over-year comparison for [year]:
     * it must fall in that calendar year, and prior-year dates must also be before
     * [priorYearCutoff] (year-to-date vs prior-year-to-date).
     */
    fun countsInYearOverYear(date: Long, year: Int, priorYear: Int, priorYearCutoff: Long): Boolean =
        TimePeriodUtils.getYear(date) == year && (year != priorYear || date < priorYearCutoff)

    /**
     * Whether a current-period row participates in the percentage comparison. Rows dated
     * after the comparison instant (future-dated entries) are in the displayed period total
     * but have no cut-off prior-window counterpart, so they are excluded from the delta.
     */
    fun countsInComparison(date: Long, comparisonMinute: Long): Boolean = date <= comparisonMinute

    /** ALL has no baseline, so it never exposes a previous total (rather than 0.0). */
    fun previousTotalOrNull(period: TimePeriod, previousAmount: Double?): Double? =
        if (period == TimePeriod.ALL) null else previousAmount

    /** Period-over-period percentage; null for ALL, unavailable amounts or a non-positive baseline. */
    fun changePercent(period: TimePeriod, comparableCurrent: Double?, previous: Double?): Float? {
        if (period == TimePeriod.ALL || comparableCurrent == null || previous == null || previous <= 0.0) return null
        return ((comparableCurrent - previous) / previous * 100).toFloat()
    }

    private const val MILLIS_PER_MINUTE = 60_000L

    /** Full previous calendar period (uncut) for [period]. Not valid for [TimePeriod.ALL]. */
    internal fun previousCalendarPeriod(period: TimePeriod, now: Long): Pair<Long, Long> {
        return when (period) {
            TimePeriod.TODAY -> {
                val todayStart = TimePeriodUtils.getStartOfDay(now)
                TimePeriodUtils.addDays(todayStart, -1) to todayStart
            }
            TimePeriod.WEEK -> TimePeriodUtils.getWeekRange(now, -1)
            TimePeriod.MONTH -> TimePeriodUtils.getMonthRange(now, -1)
            TimePeriod.QUARTER -> TimePeriodUtils.getQuarterRange(now, -1)
            TimePeriod.YEAR -> TimePeriodUtils.getYearRange(now, -1)
            TimePeriod.ALL -> now to now
        }
    }
}
