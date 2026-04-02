package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.domain.model.UiText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing a budget recommendation.
 */
data class BudgetRecommendation(
    val type: RecommendationType,
    val title: UiText,
    val description: String,
    val priority: RecommendationPriority,
    val potentialSavings: Double? = null,
    val suggestedActions: List<String> = emptyList()
)

enum class RecommendationType {
    REDUCE_SPENDING,
    INCREASE_BUDGET,
    ADJUST_CATEGORY,
    PAUSE_NON_ESSENTIAL,
    REVIEW_SUBSCRIPTIONS,
    GENERAL_ADVICE
}

enum class RecommendationPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Generates AI-powered recommendations based on budget forecasts.
 */
@Singleton
class BudgetRecommendationEngine @Inject constructor() {
    
    /**
     * Generate recommendations based on a budget forecast.
     */
    fun generateRecommendations(
        budget: Budget,
        forecast: BudgetForecast,
        currentSpending: Double
    ): List<BudgetRecommendation> {
        val recommendations = mutableListOf<BudgetRecommendation>()
        
        // Calculate remaining budget
        val remaining = budget.amount - currentSpending
        val percentUsed = if (budget.amount > 0) (currentSpending / budget.amount) * 100 else 0.0
        
        // High risk recommendations
        if (forecast.riskLevel == ForecastRiskLevel.HIGH || 
            forecast.riskLevel == ForecastRiskLevel.CRITICAL) {
            recommendations.add(
                BudgetRecommendation(
                    type = RecommendationType.REDUCE_SPENDING,
                    title = UiText.from(R.string.domain_budget_reduce_urgent),
                    description = "You're at risk of exceeding your budget. Consider cutting non-essential expenses for the remainder of the period.",
                    priority = if (forecast.overspendProbability > 0.8) RecommendationPriority.CRITICAL 
                             else RecommendationPriority.HIGH,
                    potentialSavings = forecast.predictedSpending - remaining
                )
            )
            
            recommendations.add(
                BudgetRecommendation(
                    type = RecommendationType.PAUSE_NON_ESSENTIAL,
                    title = UiText.from(R.string.domain_budget_pause_non_essential),
                    description = "Delay discretionary purchases like dining out, entertainment, and shopping until next period.",
                    priority = RecommendationPriority.HIGH,
                    suggestedActions = listOf(
                        "Cook at home instead of dining out",
                        "Skip entertainment expenses this week",
                        "Avoid impulse purchases",
                        "Use what you have before buying new"
                    )
                )
            )
        }
        
        // Medium risk recommendations
        if (forecast.riskLevel == ForecastRiskLevel.MEDIUM) {
            recommendations.add(
                BudgetRecommendation(
                    type = RecommendationType.REVIEW_SUBSCRIPTIONS,
                    title = UiText.from(R.string.domain_budget_review_subscriptions),
                    description = "You're on track to use most of your budget. Review subscriptions and recurring expenses for potential savings.",
                    priority = RecommendationPriority.MEDIUM,
                    suggestedActions = listOf(
                        "Cancel unused subscriptions",
                        "Downgrade premium services",
                        "Share family plans to reduce costs"
                    )
                )
            )
        }
        
        // Low confidence recommendations
        if (forecast.confidenceScore < 0.6) {
            recommendations.add(
                BudgetRecommendation(
                    type = RecommendationType.GENERAL_ADVICE,
                    title = UiText.from(R.string.domain_budget_build_history),
                    description = "We don't have enough data to make accurate predictions yet. Keep tracking expenses for better forecasts.",
                    priority = RecommendationPriority.LOW
                )
            )
        }
        
        // Early period recommendations
        if (percentUsed < 20 && forecast.predictedRemaining < budget.amount * 0.5) {
            recommendations.add(
                BudgetRecommendation(
                    type = RecommendationType.GENERAL_ADVICE,
                    title = UiText.from(R.string.domain_budget_early_warning),
                    description = "Your spending rate suggests you may exceed budget. Consider spreading expenses more evenly.",
                    priority = RecommendationPriority.MEDIUM
                )
            )
        }
        
        // Budget adjustment recommendation
        if (forecast.predictedRemaining < 0 && percentUsed > 75) {
            recommendations.add(
                BudgetRecommendation(
                    type = RecommendationType.INCREASE_BUDGET,
                    title = UiText.from(R.string.domain_budget_increase),
                    description = "You're consistently exceeding this budget. Consider increasing the limit or creating additional budgets.",
                    priority = RecommendationPriority.LOW
                )
            )
        }
        
        // Sort by priority
        return recommendations.sortedBy { it.priority.ordinal }
    }
    
    /**
     * Get a summary of the budget health.
     */
    fun getBudgetHealthSummary(
        budget: Budget,
        forecast: BudgetForecast,
        currentSpending: Double
    ): String {
        val remaining = budget.amount - currentSpending
        val percentUsed = if (budget.amount > 0) (currentSpending / budget.amount) * 100 else 0.0
        
        return buildString {
            append("Budget Health Summary\n")
            append("====================\n\n")
            append("Budget: €${String.format("%.2f", budget.amount)}\n")
            append("Spent: €${String.format("%.2f", currentSpending)} (${String.format("%.1f", percentUsed)}%)\n")
            append("Remaining: €${String.format("%.2f", remaining)}\n\n")
            append("AI Forecast:\n")
            append("- Predicted spending: €${String.format("%.2f", forecast.predictedSpending)}\n")
            append("- Predicted remaining: €${String.format("%.2f", forecast.predictedRemaining)}\n")
            append("- Risk level: ${forecast.riskLevel.name}\n")
            append("- Confidence: ${String.format("%.0f", forecast.confidenceScore * 100)}%\n")
            append("- Overspend probability: ${String.format("%.0f", forecast.overspendProbability * 100)}%\n")
        }
    }
    
    /**
     * Get emoji indicator for risk level.
     */
    fun getRiskEmoji(riskLevel: ForecastRiskLevel): String {
        return when (riskLevel) {
            ForecastRiskLevel.LOW -> "✅"
            ForecastRiskLevel.MEDIUM -> "⚠️"
            ForecastRiskLevel.HIGH -> "🔴"
            ForecastRiskLevel.CRITICAL -> "🚨"
        }
    }
    
    /**
     * Get color code for risk level (for UI).
     */
    fun getRiskColor(riskLevel: ForecastRiskLevel): String {
        return when (riskLevel) {
            ForecastRiskLevel.LOW -> "#4CAF50" // Green
            ForecastRiskLevel.MEDIUM -> "#FF9800" // Orange
            ForecastRiskLevel.HIGH -> "#F44336" // Red
            ForecastRiskLevel.CRITICAL -> "#B71C1C" // Dark Red
        }
    }
}
