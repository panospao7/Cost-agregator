package com.yourname.expensetracker.domain.forecasting

/**
 * Data quality metadata for forecast inputs.
 * Additive — no consumer break. All fields default to neutral values.
 *
 * (ARCH-02/P6-P1-3): Future stages will populate this from
 * ForecastInputAssembler when currency normalization is partial.
 */
data class ForecastDataQuality(
    val isPartial: Boolean = false,
    val excludedActualCount: Int = 0,
    val excludedPlannedCount: Int = 0,
    val excludedRecurringCount: Int = 0,
    val conversionWarnings: List<String> = emptyList(),
    val confidencePenalty: Double = 0.0
)
