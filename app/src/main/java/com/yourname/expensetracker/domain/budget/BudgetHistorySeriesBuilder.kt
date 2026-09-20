package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.domain.util.TimePeriodUtils

/**
 * Shared monthly history normalization for budget autopilot + forecasting.
 *
 * This builder is the single source of truth for:
 * - canonical month-key generation
 * - local-time month bucketing alignment
 * - zero-fill behavior for missing months
 * - observed-vs-filled-vs-complete month counters
 *
 * RP-08 (P6-004): a month bucket is **complete** only when its whole local
 * month range lies inside the requested half-open window. The trailing bucket
 * derived from `windowEndExclusive - 1` is complete only when the window end
 * lands exactly on a local month boundary (start of the next month); the same
 * rule applies at the leading edge. Mid-month trailing buckets are **not**
 * complete history — callers must not ratchet budgets on them.
 */
object BudgetHistorySeriesBuilder {

    data class Series(
        val monthKeys: List<String>,
        val values: List<Double>,
        val observedMonthCount: Int,
        val filledMonthCount: Int,
        /**
         * RP-08 (P6-004): number of returned month buckets whose whole local
         * month range is covered by the requested window. With
         * [excludeIncompleteEdgeMonths] enabled (the default) this always
         * equals [filledMonthCount]; in legacy mode it counts only buckets
         * whose whole month range lies inside the window. Call sites must use
         * this (never the raw bucket count) to decide whether enough history
         * exists for trend-based decisions.
         */
        val completeMonthCount: Int
    )

    enum class TrendDirection {
        INCREASING,
        DECREASING,
        STABLE
    }

    /**
     * Build a normalized monthly series for a half-open time window:
     * [windowStartInclusive, windowEndExclusive).
     *
     * RP-08 (P6-004): when [excludeIncompleteEdgeMonths] is `true` (the
     * default for history/baseline semantics), a leading or trailing month
     * bucket that is only partially covered by the window is excluded from
     * the series entirely — the window is first trimmed to local month
     * boundaries. Gaps between the first and last included complete months
     * are still zero-filled. No pro-rating is performed for any partial
     * bucket.
     */
    fun build(
        monthlyTotals: List<MonthlySpendingTotal>,
        windowStartInclusive: Long,
        windowEndExclusive: Long,
        excludeIncompleteEdgeMonths: Boolean = true
    ): Series {
        if (windowEndExclusive <= windowStartInclusive) {
            return Series(
                monthKeys = emptyList(),
                values = emptyList(),
                observedMonthCount = 0,
                filledMonthCount = 0,
                completeMonthCount = 0
            )
        }

        var effectiveStart = windowStartInclusive
        var effectiveEnd = windowEndExclusive

        if (excludeIncompleteEdgeMonths) {
            // RP-08 P6-004: the leading edge is complete only if the window
            // starts exactly at a local month boundary; the trailing edge is
            // complete only if it ends exactly at one (i.e. the first
            // millisecond of the following month). Trim non-boundary edges.
            if (effectiveStart != TimePeriodUtils.getStartOfMonth(effectiveStart)) {
                effectiveStart = TimePeriodUtils.addMonths(
                    TimePeriodUtils.getStartOfMonth(effectiveStart), 1
                )
            }
            if (effectiveEnd != TimePeriodUtils.getStartOfMonth(effectiveEnd)) {
                effectiveEnd = TimePeriodUtils.getStartOfMonth(effectiveEnd)
            }
            if (effectiveEnd <= effectiveStart) {
                // The window does not fully cover any local month.
                return Series(
                    monthKeys = emptyList(),
                    values = emptyList(),
                    observedMonthCount = 0,
                    filledMonthCount = 0,
                    completeMonthCount = 0
                )
            }
        }

        val startMonthKey = TimePeriodUtils.formatMonthKey(effectiveStart)
        val lastIncludedTimestamp = effectiveEnd - 1L
        val endMonthKey = TimePeriodUtils.formatMonthKey(lastIncludedTimestamp)
        val windowMonthKeys = TimePeriodUtils.buildMonthKeyRange(startMonthKey, endMonthKey).toSet()

        val totalsByMonth = linkedMapOf<String, Double>()
        monthlyTotals.forEach { monthlyTotal ->
            if (monthlyTotal.monthKey in windowMonthKeys) {
                totalsByMonth[monthlyTotal.monthKey] =
                    (totalsByMonth[monthlyTotal.monthKey] ?: 0.0) + monthlyTotal.total
            }
        }

        if (totalsByMonth.isEmpty()) {
            return Series(
                monthKeys = emptyList(),
                values = emptyList(),
                observedMonthCount = 0,
                filledMonthCount = 0,
                completeMonthCount = 0
            )
        }

        val sortedObservedMonthKeys = totalsByMonth.keys.sorted()
        val monthKeys = TimePeriodUtils.buildMonthKeyRange(
            startMonthKey = sortedObservedMonthKeys.first(),
            endMonthKey = sortedObservedMonthKeys.last()
        )

        val values = monthKeys.map { monthKey -> totalsByMonth[monthKey] ?: 0.0 }

        // RP-08 (P6-004): count only buckets whose whole local month range is
        // covered by the ORIGINAL requested window. In default (trimmed) mode
        // every returned bucket qualifies, so this equals filledMonthCount; in
        // legacy edge-inclusive mode it correctly discounts partial edges.
        val completeMonthCount = monthKeys.count { monthKey ->
            val (year, month) = TimePeriodUtils.parseMonthKey(monthKey)
            val monthStart = TimePeriodUtils.getMonthRange(year, month).first
            val monthEnd = TimePeriodUtils.getMonthRange(year, month).second
            monthStart >= windowStartInclusive && monthEnd <= windowEndExclusive
        }

        return Series(
            monthKeys = monthKeys,
            values = values,
            observedMonthCount = totalsByMonth.size,
            filledMonthCount = monthKeys.size,
            completeMonthCount = completeMonthCount
        )
    }

    /**
     * Shared normalized trend rate calculation for both budget engines.
     */
    fun calculateNormalizedTrendRate(monthlyValues: List<Double>): Double {
        if (monthlyValues.size < 2) return 0.0

        val avgSpend = monthlyValues.average()
        if (avgSpend <= 0.0) return 0.0

        val firstHalf = monthlyValues.take(monthlyValues.size / 2)
        val secondHalf = monthlyValues.drop(monthlyValues.size / 2)
        if (firstHalf.isEmpty() || secondHalf.isEmpty()) return 0.0

        val firstHalfAvg = firstHalf.average()
        val secondHalfAvg = secondHalf.average()
        val periodsPerHalf = (monthlyValues.size / 2.0).coerceAtLeast(1.0)

        return if (firstHalfAvg > 0.0) {
            ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) / periodsPerHalf
        } else {
            0.0
        }
    }

    fun classifyTrend(monthlyValues: List<Double>, threshold: Double): TrendDirection {
        val trendRate = calculateNormalizedTrendRate(monthlyValues)
        return when {
            trendRate > threshold -> TrendDirection.INCREASING
            trendRate < -threshold -> TrendDirection.DECREASING
            else -> TrendDirection.STABLE
        }
    }
}
