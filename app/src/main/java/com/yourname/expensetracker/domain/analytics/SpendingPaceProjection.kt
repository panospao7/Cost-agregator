package com.yourname.expensetracker.domain.analytics

internal object SpendingPaceProjection {
    private const val MIN_STABLE_PROJECTION_DAYS = 5

    fun calculateProjectedTotal(
        monthSpent: Double,
        daysElapsed: Int,
        daysInMonth: Int,
        baselineDailyRate: Double
    ): Double {
        if (daysElapsed <= 0) return monthSpent

        val linearProjection = monthSpent * daysInMonth.toDouble() / daysElapsed
        if (daysElapsed >= MIN_STABLE_PROJECTION_DAYS) {
            return linearProjection
        }

        if (baselineDailyRate > 0.0) {
            val baselineMonthlyProjection = baselineDailyRate * daysInMonth
            val weight = daysElapsed.toDouble() / MIN_STABLE_PROJECTION_DAYS.toDouble()
            return (baselineMonthlyProjection * (1.0 - weight)) + (linearProjection * weight)
        }

        val stabilizedElapsedDays = maxOf(daysElapsed, MIN_STABLE_PROJECTION_DAYS)
        return monthSpent * daysInMonth.toDouble() / stabilizedElapsedDays
    }
}
