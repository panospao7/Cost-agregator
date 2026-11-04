package com.yourname.expensetracker.domain.util

import kotlin.math.abs

/**
 * Single owner of the "mode of day-intervals" rule shared by recurring-expense
 * detection and notification-subscription detection (BL-08).
 *
 * Pure function, no I/O, no logging.
 */
object IntervalModeSelector {

    /**
     * Selects the representative interval (mode) from [intervalsDays].
     *
     * Rule: take the interval value(s) with the highest exact count. If several
     * values tie, prefer the candidate with the most intervals within its own
     * tolerance ([toleranceFor]); if still tied, prefer the one closest to the
     * median of all intervals; if still tied, the smaller value (deterministic).
     *
     * @return the selected mode, or null when [intervalsDays] is empty.
     */
    fun selectMode(intervalsDays: List<Int>, toleranceFor: (Int) -> Double): Int? {
        if (intervalsDays.isEmpty()) return null

        val counts = intervalsDays.groupingBy { it }.eachCount()
        val maxCount = counts.values.max()
        val tied = counts.filterValues { it == maxCount }.keys
        if (tied.size == 1) return tied.first()

        val sorted = intervalsDays.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }

        return tied.sortedWith(
            compareByDescending<Int> { candidate ->
                val tolerance = toleranceFor(candidate)
                intervalsDays.count { abs(it - candidate) <= tolerance }
            }
                .thenBy { abs(it - median) }
                .thenBy { it }
        ).first()
    }
}
