package com.yourname.expensetracker.golden

import com.yourname.expensetracker.domain.forecasting.DataQualityAssessor
import com.yourname.expensetracker.domain.forecasting.HistoricalSpendingDistribution
import com.yourname.expensetracker.domain.forecasting.DistributionFit
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

/**
 * Golden Scenario Test: Forecast Synthesis (Monte Carlo)
 *
 * Proves that:
 * 1. Monte Carlo simulation with seed=42 produces deterministic percentiles
 * 2. Known upcoming expenses are added to the forecast
 * 3. Budget probability is computed correctly
 * 4. Confidence level reflects data quality
 *
 * Uses REAL MonteCarloSpendingSimulator + REAL DataQualityAssessor.
 * HistoricalSpendingDistribution is mocked to provide a known distribution fit.
 */
class ForecastSynthesisGoldenTest : GoldenTestBase() {

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "forecast_synthesis",
        numericTolerance = 1.0 // Monte Carlo has inherent variance at percentile boundaries
    )

    @Test
    fun `monte carlo produces deterministic forecast with seed 42`() = runTest {
        // Mock historical distribution to return a known log-normal fit
        // mu=5.5, sigma=0.3 → median weekly spend ≈ exp(5.5) ≈ 245 EUR
        val weeklyTotals = listOf(200.0, 220.0, 240.0, 250.0, 260.0, 270.0, 280.0, 300.0,
            210.0, 230.0, 245.0, 255.0, 265.0, 275.0, 290.0, 310.0,
            215.0, 235.0, 248.0, 258.0, 268.0, 278.0, 295.0, 305.0)

        val fit = DistributionFit(
            mu = 5.5,
            sigma = 0.3,
            qualifyingWeekCount = 24,
            totalWeeksExamined = 30,
            trimmedWeeklyTotals = weeklyTotals,
            allWeeklyTotals = weeklyTotals,
            displayCurrency = "EUR"
        )

        val historicalDistribution = mockk<HistoricalSpendingDistribution>()
        coEvery { historicalDistribution.computeDistribution(any()) } returns fit

        val dataQualityAssessor = DataQualityAssessor()
        val simulator = MonteCarloSpendingSimulator(
            historicalDistribution = historicalDistribution,
            dataQualityAssessor = dataQualityAssessor,
            timeProvider = timeProvider
        )

        // Simulate: spent 500 so far, 100 known upcoming, budget 1500
        val result = simulator.simulate(
            spentToDate = 500.0,
            knownUpcoming = 100.0,
            budgetAmount = 1500.0,
            displayCurrency = "EUR",
            estimatedWeeklyRecurring = 50.0
        )

        // Serialize
        val actual = JSONObject().apply {
            put("resultNotNull", result != null)
            if (result != null) {
                put("p10", result.percentile10)
                put("p25", result.percentile25)
                put("p50", result.percentile50)
                put("p75", result.percentile75)
                put("p90", result.percentile90)
                put("spentToDate", result.spentToDate)
                put("knownUpcoming", result.knownUpcoming)
                put("budgetAmount", result.budgetAmount)
                put("probabilityUnderBudget", result.probabilityUnderBudget)
                put("confidenceLevel", result.confidence.level.name)
                put("confidenceScore", result.confidence.score)
                put("displayCurrency", result.displayCurrency)

                // Sanity: p10 < p50 < p90
                put("percentilesOrdered", result.percentile10 <= result.percentile50
                        && result.percentile50 <= result.percentile90)
                // All percentiles include spentToDate + knownUpcoming as base
                put("allAboveBaseline", result.percentile10 >= 600.0) // 500 + 100
            }
        }

        verifier.verify(actual).assertPassed()
    }
}
