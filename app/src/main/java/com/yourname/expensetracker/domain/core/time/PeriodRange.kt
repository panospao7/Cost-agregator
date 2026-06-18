package com.yourname.expensetracker.domain.core.time

import java.time.ZoneId

/**
 * A typed, half-open time period with explicit semantics.
 *
 * ## Contract
 *
 * All [PeriodRange] instances follow the **`[startInclusive, endExclusive)`**
 * convention. A timestamp `t` belongs to this range when:
 * ```
 *   t >= startInclusiveMillis && t < endExclusiveMillis
 * ```
 *
 * This contract matches [com.yourname.expensetracker.domain.util.TimePeriodUtils]
 * and replaces the untyped `Pair<Long, Long>` that previously carried no
 * information about period kind, timezone, or boundary semantics.
 *
 * ## DST transitions
 *
 * Calendar day boundaries are computed in the given [zoneId]. A day may be 23
 * or 25 hours during DST transitions, so wall-clock duration arithmetic
 * (e.g. `endExclusiveMillis - startInclusiveMillis`) is not a reliable way to
 * compute the number of calendar days in the range. Use
 * [com.yourname.expensetracker.domain.util.TimePeriodUtils.daysBetween] for
 * calendar-day difference.
 *
 * ## Usage
 *
 * ```kotlin
 * val range = PeriodRange(
 *     kind = PeriodKind.THIS_MONTH,
 *     startInclusiveMillis = TimePeriodUtils.getStartOfMonth(now),
 *     endExclusiveMillis = TimePeriodUtils.getEndOfMonth(now),
 *     zoneId = ZoneId.systemDefault(),
 *     label = "April 2026"
 * )
 *
 * if (range.contains(expense.date)) { ... }
 * ```
 *
 * @property kind The semantic kind of this period (TODAY, THIS_MONTH, etc.).
 * @property startInclusiveMillis Epoch millis of the inclusive start boundary.
 * @property endExclusiveMillis Epoch millis of the exclusive end boundary.
 * @property zoneId The timezone this range was computed in.
 * @property label Human-readable label for display purposes.
 */
data class PeriodRange(
    val kind: PeriodKind,
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val label: String = ""
) {
    init {
        require(endExclusiveMillis >= startInclusiveMillis) {
            "endExclusiveMillis ($endExclusiveMillis) must be >= startInclusiveMillis ($startInclusiveMillis)"
        }
    }

    /**
     * Returns `true` if [timestamp] falls within this half-open range.
     *
     * Equivalent to:
     * ```
     * timestamp >= startInclusiveMillis && timestamp < endExclusiveMillis
     * ```
     */
    fun contains(timestamp: Long): Boolean =
        timestamp >= startInclusiveMillis && timestamp < endExclusiveMillis

    /**
     * Returns the duration of this period in milliseconds.
     * Note: this is elapsed wall-clock duration, not calendar-aware.
     * For calendar day counts prefer [TimePeriodUtils.daysBetween].
     */
    val durationMillis: Long
        get() = endExclusiveMillis - startInclusiveMillis

    /**
     * Returns `true` if this period represents a calendar period
     * (TODAY, THIS_WEEK, THIS_MONTH, THIS_QUARTER, THIS_YEAR,
     * LAST_WEEK, LAST_MONTH, LAST_QUARTER, LAST_YEAR)
     * as opposed to a rolling window (LAST_7_DAYS, LAST_30_DAYS) or CUSTOM.
     */
    val isCalendarPeriod: Boolean
        get() = when (kind) {
            PeriodKind.TODAY,
            PeriodKind.THIS_WEEK,
            PeriodKind.LAST_WEEK,
            PeriodKind.THIS_MONTH,
            PeriodKind.LAST_MONTH,
            PeriodKind.THIS_QUARTER,
            PeriodKind.LAST_QUARTER,
            PeriodKind.THIS_YEAR,
            PeriodKind.LAST_YEAR -> true
            PeriodKind.LAST_7_DAYS,
            PeriodKind.LAST_30_DAYS,
            PeriodKind.CUSTOM -> false
        }

    override fun toString(): String =
        "PeriodRange(kind=$kind, label='$label', [$startInclusiveMillis, $endExclusiveMillis), zone=$zoneId)"
}
