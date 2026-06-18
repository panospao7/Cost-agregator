package com.yourname.expensetracker.domain.usecase.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact
import com.yourname.expensetracker.domain.model.budget.MonteCarloBudgetImpact.RiskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetMonteCarloBudgetImpactUseCaseTest {

    private val useCase = GetMonteCarloBudgetImpactUseCase()

    @Test
    fun `invoke returns error when budget is zero`() {
        val result = useCase(
            budgetAmount = 0.0,
            monteCarloResult = monteCarloResult(p50 = 1000.0, probabilityUnderBudget = 0.6)
        )

        assertTrue(result is Result.Error)
        assertEquals("Budget amount must be greater than zero", (result as Result.Error).message)
    }

    @Test
    fun `invoke returns error when probability is null`() {
        val result = useCase(
            budgetAmount = 1000.0,
            monteCarloResult = monteCarloResult(p50 = 1100.0, probabilityUnderBudget = null)
        )

        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message?.contains("budget probability") == true)
    }

    @Test
    fun `invoke classifies LOW when overrun and probability are both below thresholds`() {
        val result = useCase(
            budgetAmount = 1000.0,
            monteCarloResult = monteCarloResult(
                p50 = 1030.0, // 3%
                probabilityUnderBudget = 0.80 // overrun probability = 20%
            )
        )

        val impact = (result as Result.Success).data
        assertEquals(RiskTier.LOW, impact.riskTier)
        assertApproxEquals(30.0, impact.expectedOverrun, 0.0001)
        assertApproxEquals(0.20, impact.probabilityOfOverrun, 0.0001)
    }

    @Test
    fun `invoke does not short-circuit on expectedOverrun zero and still uses probability`() {
        val result = useCase(
            budgetAmount = 1000.0,
            monteCarloResult = monteCarloResult(
                p50 = 900.0, // expected overrun = 0
                probabilityUnderBudget = 0.40 // overrun probability = 60%
            )
        )

        val impact = (result as Result.Success).data
        assertApproxEquals(0.0, impact.expectedOverrun, 0.0001)
        assertApproxEquals(0.60, impact.probabilityOfOverrun, 0.0001)
        assertEquals(RiskTier.HIGH, impact.riskTier)
    }

    @Test
    fun `invoke uses BOTH dimensions overrun and probability for tiering`() {
        val highByOverrunLowProbability = (useCase(
            budgetAmount = 1000.0,
            monteCarloResult = monteCarloResult(
                p50 = 1200.0, // 20% overrun
                probabilityUnderBudget = 0.90 // 10% overrun probability
            )
        ) as Result.Success).data

        val highByProbabilityLowOverrun = (useCase(
            budgetAmount = 1000.0,
            monteCarloResult = monteCarloResult(
                p50 = 1010.0, // 1% overrun
                probabilityUnderBudget = 0.45 // 55% overrun probability
            )
        ) as Result.Success).data

        assertEquals(RiskTier.HIGH, highByOverrunLowProbability.riskTier)
        assertEquals(RiskTier.HIGH, highByProbabilityLowOverrun.riskTier)
    }

    @Test
    fun `boundary overrun exactly five percent is MEDIUM`() {
        val impact = successImpact(
            budget = 1000.0,
            p50 = 1050.0,
            probabilityUnderBudget = 0.99
        )

        assertEquals(RiskTier.MEDIUM, impact.riskTier)
    }

    @Test
    fun `boundary overrun exactly fifteen percent is HIGH`() {
        val impact = successImpact(
            budget = 1000.0,
            p50 = 1150.0,
            probabilityUnderBudget = 0.99
        )

        assertEquals(RiskTier.HIGH, impact.riskTier)
    }

    @Test
    fun `boundary overrun exactly thirty percent is CRITICAL`() {
        val impact = successImpact(
            budget = 1000.0,
            p50 = 1300.0,
            probabilityUnderBudget = 0.99
        )

        assertEquals(RiskTier.CRITICAL, impact.riskTier)
    }

    @Test
    fun `boundary probability exactly twenty five percent is MEDIUM`() {
        val impact = successImpact(
            budget = 1000.0,
            p50 = 1000.0,
            probabilityUnderBudget = 0.75 // overrun probability = 25%
        )

        assertEquals(RiskTier.MEDIUM, impact.riskTier)
    }

    @Test
    fun `boundary probability exactly fifty percent is HIGH`() {
        val impact = successImpact(
            budget = 1000.0,
            p50 = 1000.0,
            probabilityUnderBudget = 0.50 // overrun probability = 50%
        )

        assertEquals(RiskTier.HIGH, impact.riskTier)
    }

    @Test
    fun `boundary probability exactly seventy five percent is CRITICAL`() {
        val impact = successImpact(
            budget = 1000.0,
            p50 = 1000.0,
            probabilityUnderBudget = 0.25 // overrun probability = 75%
        )

        assertEquals(RiskTier.CRITICAL, impact.riskTier)
    }

    @Test
    fun `invoke ignores monte carlo internal budgetAmount null and uses provided budget`() {
        val impact = successImpact(
            budget = 800.0,
            p50 = 840.0,
            probabilityUnderBudget = 0.90,
            mcBudgetAmount = null
        )

        assertApproxEquals(800.0, impact.budgetAmount, 0.0001)
        assertApproxEquals(40.0, impact.expectedOverrun, 0.0001)
    }

    private fun successImpact(
        budget: Double,
        p50: Double,
        probabilityUnderBudget: Double,
        mcBudgetAmount: Double? = budget
    ): MonteCarloBudgetImpact {
        val result = useCase(
            budgetAmount = budget,
            monteCarloResult = monteCarloResult(
                p50 = p50,
                probabilityUnderBudget = probabilityUnderBudget,
                budgetAmount = mcBudgetAmount
            )
        )
        return (result as Result.Success).data
    }

    private fun monteCarloResult(
        p50: Double,
        probabilityUnderBudget: Double?,
        budgetAmount: Double? = 1000.0
    ): MonteCarloResult {
        return MonteCarloResult(
            percentile10 = p50 - 100,
            percentile25 = p50 - 50,
            percentile50 = p50,
            percentile75 = p50 + 50,
            percentile90 = p50 + 100,
            probabilityUnderBudget = probabilityUnderBudget,
            budgetAmount = budgetAmount,
            spentToDate = 500.0,
            knownUpcoming = 200.0,
            confidence = SimulationConfidence(
                score = 0.7,
                level = ConfidenceLevel.HIGH,
                reason = "deterministic test"
            ),
            metadata = SimulationMetadata(
                qualifyingWeeks = 8,
                totalWeeksExamined = 12,
                iterations = 1000,
                logNormalMu = 0.0,
                logNormalSigma = 1.0,
                daysRemaining = 12,
                computedAt = 1_700_000_000_000L
            ),
            displayCurrency = "EUR"
        )
    }
}
