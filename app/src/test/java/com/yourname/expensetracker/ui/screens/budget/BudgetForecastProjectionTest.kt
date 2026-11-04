package com.yourname.expensetracker.ui.screens.budget

import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forecast screen compares the full-period projection (spent so far + predicted
 * remaining-period spend) with the full-period limit. The screen colours the predicted-spending
 * row red when `projectedPeriodTotal(forecast) > limit` (strict). Synthetic data only.
 *
 * Seam note: the colour rule and the `+ spentToDate` shift of the low/base/high bounds sit inline
 * inside the private composable `ForecastDetailsCard`, and `calculateForecastBounds` is private,
 * so only `projectedPeriodTotal` can be pinned here.
 */
class BudgetForecastProjectionTest {

    private val delta = 1e-9

    private fun forecast(spent: Double, predicted: Double): BudgetForecast =
        BudgetForecast(
            budgetId = 1L,
            forecastDate = 1_700_000_000_000L,
            targetPeriodStart = 1_699_000_000_000L,
            targetPeriodEnd = 1_701_000_000_000L,
            predictedSpending = predicted,
            predictedRemaining = 0.0,
            confidenceScore = 0.8,
            riskLevel = ForecastRiskLevel.LOW,
            overspendProbability = 0.0,
        ).also { it.spentToDate = spent }

    @Test
    fun spent90Predicted20_projectsTo110_overLimit100_eventhoughPredictedAloneIsUnder() {
        val f = forecast(spent = 90.0, predicted = 20.0)
        val limit = 100.0

        assertTrue(f.predictedSpending < limit)
        assertEquals(110.0, projectedPeriodTotal(f), delta)
        assertTrue(projectedPeriodTotal(f) > limit)
    }

    @Test
    fun spent10Predicted20_projectsTo30_underLimit100() {
        val f = forecast(spent = 10.0, predicted = 20.0)
        val limit = 100.0

        assertEquals(30.0, projectedPeriodTotal(f), delta)
        assertFalse(projectedPeriodTotal(f) > limit)
    }

    @Test
    fun projectionExactlyAtLimit_isNotOver_underStrictGreaterThanColourRule() {
        val f = forecast(spent = 80.0, predicted = 20.0)
        val limit = 100.0

        assertEquals(100.0, projectedPeriodTotal(f), delta)
        assertFalse(projectedPeriodTotal(f) > limit)
    }

    @Test
    fun projectionJustAboveLimit_isOver() {
        val f = forecast(spent = 80.0, predicted = 20.01)

        assertEquals(100.01, projectedPeriodTotal(f), delta)
        assertTrue(projectedPeriodTotal(f) > 100.0)
    }

    @Test
    fun spentToDateZero_projectionEqualsPredictedSpending() {
        val under = forecast(spent = 0.0, predicted = 20.0)
        val over = forecast(spent = 0.0, predicted = 150.0)

        assertEquals(20.0, projectedPeriodTotal(under), delta)
        assertFalse(projectedPeriodTotal(under) > 100.0)
        assertEquals(150.0, projectedPeriodTotal(over), delta)
        assertTrue(projectedPeriodTotal(over) > 100.0)
    }

    @Test
    fun spentToDateUnsetDefault_behavesAsZero() {
        val f = BudgetForecast(
            budgetId = 1L,
            forecastDate = 1_700_000_000_000L,
            targetPeriodStart = 1_699_000_000_000L,
            targetPeriodEnd = 1_701_000_000_000L,
            predictedSpending = 42.0,
            predictedRemaining = 0.0,
            confidenceScore = 0.5,
            riskLevel = ForecastRiskLevel.LOW,
            overspendProbability = 0.0,
        )

        assertEquals(42.0, projectedPeriodTotal(f), delta)
    }

    @Test
    fun `every forecast bound is shifted by the spend to date`() {
        val without = fullPeriodForecastBounds(20.0, 0.8, spentToDate = 0.0)
        val with = fullPeriodForecastBounds(20.0, 0.8, spentToDate = 90.0)

        assertEquals(without.first + 90.0, with.first, 1e-9)
        assertEquals(110.0, with.second, 1e-9)
        assertEquals(without.third + 90.0, with.third, 1e-9)
    }

    @Test
    fun `the low bound clamps before the shift so it never falls below the spend to date`() {
        val bounds = fullPeriodForecastBounds(0.0, 0.0, spentToDate = 90.0)

        assertEquals(90.0, bounds.first, 1e-9)
    }

    @Test
    fun `predictedPeriodTotal is spent plus remaining-period prediction for limit 1000 spent 700 predicted 200`() {
        val f = forecast(spent = 700.0, predicted = 200.0)

        assertEquals(900.0, f.predictedPeriodTotal(), delta)
        assertFalse(f.predictedPeriodTotal() > 1_000.0)
    }

    @Test
    fun `predictedPeriodTotal for spent 700 predicted 500 is 1200 and exceeds limit 1000`() {
        val f = forecast(spent = 700.0, predicted = 500.0)

        assertEquals(1_200.0, f.predictedPeriodTotal(), delta)
        assertTrue(f.predictedPeriodTotal() > 1_000.0)
    }

    private fun riskForecast(
        confidence: Double,
        risk: ForecastRiskLevel = ForecastRiskLevel.HIGH,
        probability: Double = 0.7,
    ): BudgetForecast =
        BudgetForecast(
            budgetId = 1L,
            forecastDate = 1_700_000_000_000L,
            targetPeriodStart = 1_699_000_000_000L,
            targetPeriodEnd = 1_701_000_000_000L,
            predictedSpending = 100.0,
            predictedRemaining = 0.0,
            confidenceScore = confidence,
            riskLevel = risk,
            overspendProbability = probability,
        )

    @Test
    fun `overspend probability is shown above confidence 0_6 with known risk and positive probability`() {
        assertTrue(shouldShowOverspendProbability(riskForecast(confidence = 0.61)))
    }

    @Test
    fun `overspend probability is hidden at confidence 0_59`() {
        assertFalse(shouldShowOverspendProbability(riskForecast(confidence = 0.6)))
    }

    @Test
    fun `overspend probability is hidden for UNKNOWN risk even at high confidence`() {
        assertFalse(
            shouldShowOverspendProbability(riskForecast(confidence = 0.95, risk = ForecastRiskLevel.UNKNOWN))
        )
    }

    @Test
    fun `overspend probability is hidden when probability is zero even at high confidence`() {
        assertFalse(shouldShowOverspendProbability(riskForecast(confidence = 0.95, probability = 0.0)))
    }

    @Test
    fun partialNotice_showsForPartialOrExcludedRows_andHidesWhenClear() {
        val clear = forecast(spent = 10.0, predicted = 20.0).copy(isPartial = false, excludedExpenseCount = 0)
        assertFalse(shouldShowPartialForecastNotice(clear))
        assertTrue(shouldShowPartialForecastNotice(clear.copy(isPartial = true)))
        assertTrue(shouldShowPartialForecastNotice(clear.copy(excludedExpenseCount = 3)))
    }
}
