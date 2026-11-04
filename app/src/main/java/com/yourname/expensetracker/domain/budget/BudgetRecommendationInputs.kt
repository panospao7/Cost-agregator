package com.yourname.expensetracker.domain.budget

data class BudgetRecommendationBudget(
    val amount: Double
)

data class BudgetRecommendationForecast(
    val predictedSpending: Double,
    val predictedRemaining: Double,
    val confidenceScore: Double,
    val riskLevel: BudgetRecommendationRiskLevel,
    val overspendProbability: Double,
    /**
     * BL-06: full-period projection (spent so far + remaining-period [predictedSpending]),
     * supplied by the forecast model's predictedPeriodTotal(). Defaults to [predictedSpending]
     * (nothing spent yet) so callers without spend data keep their old meaning.
     */
    val predictedPeriodTotal: Double = predictedSpending
)

enum class BudgetRecommendationRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
