package com.yourname.expensetracker.domain.util

import kotlin.math.sqrt

object StatisticsUtils {

    /**
     * Calculates the Sample Standard Deviation from a list of values.
     * Uses Bessel's correction (N-1).
     * Returns 0.0 if there are fewer than 2 values.
     */
    fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val sumSq = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (values.size - 1))
    }
}
