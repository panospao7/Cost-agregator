package com.yourname.expensetracker.domain.model.budget

import com.yourname.expensetracker.domain.util.CurrencyFormatter

/**
 * Represents the impact of Monte Carlo forecasting on a user's budget.
 *
 * This model bridges probabilistic spending forecasts with budget reality,
 * providing risk assessment and actionable messaging for the UI.
 *
 * @property budgetAmount The budget amount against which the Monte Carlo result was evaluated
 * @property p50Forecast The 50th percentile (median) projected month-end spending
 * @property expectedOverrun The expected budget overrun: max(0, p50 - budget)
 * @property probabilityOfOverrun The probability of exceeding budget: 1 - probabilityUnderBudget
 * @property riskTier The assessed risk level based on overrun magnitude and probability
 * @property displayMessage Human-readable message for UI display
 * @property formattedOverrun The formatted overrun amount with currency symbol (e.g., "$123.45")
 */
data class MonteCarloBudgetImpact(
    val budgetAmount: Double,
    val p50Forecast: Double,
    val expectedOverrun: Double,
    val probabilityOfOverrun: Double,
    val riskTier: RiskTier,
    val displayMessage: String,
    val formattedOverrun: String
) {
    /**
     * Risk tier classification based on overrun magnitude and probability.
     */
    enum class RiskTier {
        /** Overrun < 5% of budget AND P(overrun) < 25% */
        LOW,
        /** Overrun < 15% of budget AND P(overrun) < 50% */
        MEDIUM,
        /** Overrun < 30% of budget OR P(overrun) < 75% */
        HIGH,
        /** Overrun >= 30% of budget OR P(overrun) >= 75% */
        CRITICAL
    }

    companion object {
        /**
         * Format currency for display messages using the app currency formatter.
         */
        fun formatCurrency(amount: Double): String {
            return CurrencyFormatter.format(amount)
        }
    }
}
