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
 * - observed-vs-filled month counters
 */
object BudgetHistorySeriesBuilder {

    data class Series(
        val monthKeys: List<String>,
        val values: List<Double>,
        val observedMonthCount: Int,
        val filledMonthCount: Int
    )

    enum class TrendDirection {
        INCREASING,
        DECREASING,
        STABLE
    }

    /**
     * Build a normalized monthly series for a half-open time window:
     * [windowStartInclusive, windowEndExclusive).
     */
    fun build(
        monthlyTotals: List<MonthlySpendingTotal>,
        windowStartInclusive: Long,
        windowEndExclusive: Long
    ): Series {
        if (windowEndExclusive <= windowStartInclusive) {
            return Series(
                monthKeys = emptyList(),
                values = emptyList(),
                observedMonthCount = 0,
                filledMonthCount = 0
            )
        }

        val startMonthKey = TimePeriodUtils.formatMonthKey(windowStartInclusive)
        val lastIncludedTimestamp = windowEndExclusive - 1L
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
                filledMonthCount = 0
            )
        }

        val sortedObservedMonthKeys = totalsByMonth.keys.sorted()
        val monthKeys = TimePeriodUtils.buildMonthKeyRange(
            startMonthKey = sortedObservedMonthKeys.first(),
            endMonthKey = sortedObservedMonthKeys.last()
        )

        val values = monthKeys.map { monthKey -> totalsByMonth[monthKey] ?: 0.0 }

        return Series(
            monthKeys = monthKeys,
            values = values,
            observedMonthCount = totalsByMonth.size,
            filledMonthCount = monthKeys.size
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
