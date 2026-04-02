package com.yourname.expensetracker.domain.model

import java.time.Instant

data class FinancialForecast(
    val horizon: ForecastHorizon,
    val generatedAt: Instant,
    val confidence: Double, // 0.0 - 1.0
    val components: ForecastComponents,
    val actionableInsights: List<String>
)

enum class ForecastHorizon(val days: Int, val displayName: String) {
    NEXT_7_DAYS(7, "Next 7 Days"),
    NEXT_30_DAYS(30, "Next 30 Days"),
    REST_OF_MONTH(0, "Rest of Month") // 0 means calculate based on calendar
}

data class ForecastComponents(
    val recurringExpenses: List<RecurringPattern>,
    val plannedExpenses: List<PlannedExpense> = emptyList(), // Manual intentions
    val goalReserves: Double = 0.0, // Money locked in goals

    // Timeline Data
    val pastSpendingPoints: List<Double>, // Cumulative daily spend up to today
    val projectedSpendingPoints: List<Double>, // Projected cumulative daily spend for rest of month
    
    // Synthesis Metrics
    val totalCommitted: Double,        // High confidence (bills, manual)
    val totalLikely: Double,           // Medium confidence (patterns, manual)
    val predictedDiscretionary: Double, // Habit-based predicted spending
    val discretionaryBudget: Double,   // "Safe-to-Spend"
    val riskLevel: RiskLevel
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class WeatherNarrative(
    val state: com.yourname.expensetracker.data.repository.WeatherState,
    val icon: String,
    val headline: String,
    val summary: String,
    val details: List<NarrativeSection> = emptyList()
)

data class NarrativeSection(
    val title: UiText,
    val icon: String,
    val items: List<String>
)
