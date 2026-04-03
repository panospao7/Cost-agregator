package com.yourname.expensetracker.domain.usecase.budget

import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import javax.inject.Inject

/**
 * Use case for evaluating the budget impact of Monte Carlo forecasting results.
 *
 * This use case bridges probabilistic spending forecasts with budget reality,
 * computing expected overruns, tail risks, and providing actionable risk tiers
 * with human-readable messaging for UI display.
 *
 * Risk tiers:
 * - LOW: overrun < 5% of budget AND P(overrun) < 25%
 * - MEDIUM: overrun < 15% of budget AND P(overrun) < 50%
 * - HIGH: overrun < 30% of budget OR P(overrun) < 75%
 * - CRITICAL: overrun >= 30% of budget OR P(overrun) >= 75%
 */
class GetMonteCarloBudgetImpactUseCase @Inject constructor() {

    /**
     * Computes the budget impact of a Monte Carlo result.
     *
     * @param budgetAmount The user's monthly budget amount (in EUR)
     * @param monteCarloResult The Monte Carlo simulation result
     * @return [Result.Success] with [MonteCarloBudgetImpact] or [Result.Error] if inputs are invalid
     */
    operator fun invoke(
        budgetAmount: Double,
        monteCarloResult: MonteCarloResult
    ): Result<MonteCarloBudgetImpact> {
        // Validate inputs
        if (budgetAmount <= 0) {
            return Result.Error(
                IllegalArgumentException("Budget must be positive"),
                "Budget amount must be greater than zero"
            )
        }

        val probabilityUnderBudget = monteCarloResult.probabilityUnderBudget
            ?: return Result.Error(
                IllegalStateException("Probability under budget not available"),
                "Monte Carlo result does not contain budget probability data"
            )

        // Compute expected overrun: max(0, p50 - budget)
        val p50Forecast = monteCarloResult.percentile50
        val expectedOverrun = maxOf(0.0, p50Forecast - budgetAmount)

        // Compute tail risk: P(overrun) = 1 - probabilityUnderBudget
        val probabilityOfOverrun = 1.0 - probabilityUnderBudget

        // Determine risk tier based on overrun magnitude and probability
        val riskTier = determineRiskTier(expectedOverrun, budgetAmount, probabilityOfOverrun)

        // Generate display message based on risk tier and formatted overrun
        val formattedOverrun = MonteCarloBudgetImpact.formatCurrency(expectedOverrun)
        val displayMessage = generateDisplayMessage(riskTier, formattedOverrun)

        val impact = MonteCarloBudgetImpact(
            budgetAmount = budgetAmount,
            p50Forecast = p50Forecast,
            expectedOverrun = expectedOverrun,
            probabilityOfOverrun = probabilityOfOverrun,
            riskTier = riskTier,
            displayMessage = displayMessage,
            formattedOverrun = formattedOverrun
        )

        return Result.Success(impact)
    }

    /**
     * Determines the risk tier based on overrun magnitude and probability.
     *
     * Logic:
     * - LOW: overrun < 5% of budget AND P(overrun) < 25%
     * - MEDIUM: overrun < 15% of budget AND P(overrun) < 50%
     * - HIGH: overrun < 30% of budget OR P(overrun) < 75%
     * - CRITICAL: overrun >= 30% of budget OR P(overrun) >= 75%
     */
    private fun determineRiskTier(
        expectedOverrun: Double,
        budgetAmount: Double,
        probabilityOfOverrun: Double
    ): RiskTier {
        val overrunPercent = if (budgetAmount > 0) expectedOverrun / budgetAmount else 0.0

        return when {
            overrunPercent >= 0.30 || probabilityOfOverrun >= 0.75 -> RiskTier.CRITICAL

            overrunPercent >= 0.15 || probabilityOfOverrun >= 0.50 -> RiskTier.HIGH

            overrunPercent >= 0.05 || probabilityOfOverrun >= 0.25 -> RiskTier.MEDIUM

            else -> RiskTier.LOW
        }
    }

    /**
     * Generates a human-readable message based on risk tier and formatted overrun.
     *
     * Messages:
     * - LOW: "You're on track to stay within budget"
     * - MEDIUM: "You may exceed your budget by €X"
     * - HIGH: "High risk of exceeding budget by €X"
     * - CRITICAL: "Very likely to exceed budget by €X"
     */
    private fun generateDisplayMessage(riskTier: RiskTier, formattedOverrun: String): String {
        return when (riskTier) {
            RiskTier.LOW -> "You're on track to stay within budget"
            RiskTier.MEDIUM -> "You may exceed your budget by $formattedOverrun"
            RiskTier.HIGH -> "High risk of exceeding budget by $formattedOverrun"
            RiskTier.CRITICAL -> "Very likely to exceed budget by $formattedOverrun"
        }
    }
}
