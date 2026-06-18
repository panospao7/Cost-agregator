package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.model.NarrativeSection
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.UiText

enum class WeatherState {
    CLEAR_SKIES,
    PARTLY_CLOUDY,
    CLOUDY,
    RAINY,
    STORMY,
    UNKNOWN
}

data class FinancialWeather(
    val state: WeatherState,
    val headline: UiText,
    val summary: UiText,
    val icon: String,
    val riskLevel: Int,
    val totalCommitted: Double,
    val totalLikely: Double,
    val predictedDiscretionary: Double,
    val discretionaryBudget: Double,
    val pastSpendingPoints: List<Double> = emptyList(),
    val projectedSpendingPoints: List<Double> = emptyList(),
    val upcomingItems: List<UpcomingItem> = emptyList(),
    val totalRecurringCount: Int = 0,
    val details: List<NarrativeSection> = emptyList(),
    val isPartial: Boolean = false,
    val qualityWarnings: List<String> = emptyList(),
    val excludedCount: Int = 0
)
