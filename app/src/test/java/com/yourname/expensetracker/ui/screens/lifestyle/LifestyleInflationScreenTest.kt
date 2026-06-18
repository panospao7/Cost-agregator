package com.yourname.expensetracker.ui.screens.lifestyle

import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifestyleInflationScreenTest {

    @Test
    fun `calculateMonthlyTrendBarWeights skips all zero-value segments`() {
        val weights = calculateMonthlyTrendBarWeights(
            month = monthData(
                income = 0.0,
                totalSpending = 0.0,
                essentialSpending = 0.0,
                discretionarySpending = 0.0
            ),
            maxReferenceAmount = 0.0
        )

        assertNull(weights.essential)
        assertNull(weights.discretionary)
        assertNull(weights.savings)
    }

    @Test
    fun `calculateMonthlyTrendBarWeights keeps positive segments valid when reference amount is zero`() {
        val weights = calculateMonthlyTrendBarWeights(
            month = monthData(
                income = 0.0,
                totalSpending = 100.0,
                essentialSpending = 25.0,
                discretionarySpending = 75.0
            ),
            maxReferenceAmount = 0.0
        )

        assertTrue(weights.essential != null && weights.essential > 0f)
        assertTrue(weights.discretionary != null && weights.discretionary > 0f)
        assertNull(weights.savings)
    }

    @Test
    fun `calculateMonthlyTrendBarWeights clamps tiny positive values above zero`() {
        val weights = calculateMonthlyTrendBarWeights(
            month = monthData(
                income = 1_000.0,
                totalSpending = 0.1,
                essentialSpending = 0.1,
                discretionarySpending = 0.0
            ),
            maxReferenceAmount = 1_000.0
        )

        assertEquals(MONTHLY_TREND_MIN_SEGMENT_WEIGHT, weights.essential ?: 0f)
    }

    @Test
    fun `resolveLifestyleInflationContentState returns loading when first load is in progress`() {
        val state = resolveLifestyleInflationContentState(
            hasReport = false,
            isLoading = true,
            hasError = false
        )

        assertEquals(LifestyleInflationContentState.FULL_SCREEN_LOADING, state)
    }

    @Test
    fun `resolveLifestyleInflationContentState returns full screen error when no report exists`() {
        val state = resolveLifestyleInflationContentState(
            hasReport = false,
            isLoading = false,
            hasError = true
        )

        assertEquals(LifestyleInflationContentState.FULL_SCREEN_ERROR, state)
    }

    @Test
    fun `resolveLifestyleInflationContentState keeps content visible when stale report exists with error`() {
        val state = resolveLifestyleInflationContentState(
            hasReport = true,
            isLoading = false,
            hasError = true
        )

        assertEquals(LifestyleInflationContentState.CONTENT, state)
    }

    private fun monthData(
        income: Double,
        totalSpending: Double,
        essentialSpending: Double,
        discretionarySpending: Double
    ) = LifestyleInflationDetector.MonthlyLifestyleData(
        month = "2026-04",
        income = income,
        totalSpending = totalSpending,
        discretionarySpending = discretionarySpending,
        essentialSpending = essentialSpending,
        savingsRate = 0.0,
        lifestyleScore = 0.0
    )
}
