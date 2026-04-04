package com.yourname.expensetracker.domain.budget

data class BudgetRecommendationBudget(
    val amount: Double
)

data class BudgetRecommendationForecast(
    val predictedSpending: Double,
    val predictedRemaining: Double,
    val confidenceScore: Double,
    val riskLevel: BudgetRecommendationRiskLevel,
    val overspendProbability: Double
)

enum class BudgetRecommendationRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
