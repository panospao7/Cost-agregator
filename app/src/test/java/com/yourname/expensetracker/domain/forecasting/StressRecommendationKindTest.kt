package com.yourname.expensetracker.domain.forecasting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stress-forecast-actions INV-3: [StressRecommendationKind.action] is the only kind-to-intent
 * mapping; R4/R5/R6 are informational ([StressRecommendationAction.None]).
 */
class StressRecommendationKindTest {

    private val expectedActions: Map<StressRecommendationKind, StressRecommendationAction> = mapOf(
        StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS to StressRecommendationAction.OpenRecurring,
        StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING to StressRecommendationAction.OpenTransactionsThisMonth,
        StressRecommendationKind.BUILD_EMERGENCY_BUFFER to StressRecommendationAction.OpenSavingsGoals,
        StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES to StressRecommendationAction.None,
        StressRecommendationKind.HEALTHY_OUTLOOK to StressRecommendationAction.None,
        StressRecommendationKind.NO_IMMEDIATE_CONCERNS to StressRecommendationAction.None
    )

    @Test
    fun `INV-3 expectation table covers every kind so a new kind forces a mapping decision`() {
        assertEquals(StressRecommendationKind.values().toSet(), expectedActions.keys)
        assertEquals(6, StressRecommendationKind.values().size)
    }

    @Test
    fun `INV-3 each kind maps to its single action`() {
        expectedActions.forEach { (kind, action) ->
            assertEquals("action for $kind", action, kind.action)
        }
    }

    @Test
    fun `INV-3 review recurring opens recurring`() {
        assertEquals(StressRecommendationAction.OpenRecurring, StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS.action)
    }

    @Test
    fun `INV-3 reduce discretionary spending opens this month transactions`() {
        assertEquals(
            StressRecommendationAction.OpenTransactionsThisMonth,
            StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING.action
        )
    }

    @Test
    fun `INV-3 build emergency buffer opens savings goals`() {
        assertEquals(StressRecommendationAction.OpenSavingsGoals, StressRecommendationKind.BUILD_EMERGENCY_BUFFER.action)
    }

    @Test
    fun `INV-5 R4 R5 and R6 are informational with action None`() {
        assertEquals(StressRecommendationAction.None, StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES.action)
        assertEquals(StressRecommendationAction.None, StressRecommendationKind.HEALTHY_OUTLOOK.action)
        assertEquals(StressRecommendationAction.None, StressRecommendationKind.NO_IMMEDIATE_CONCERNS.action)
    }

    @Test
    fun `INV-5 exactly three kinds are actionable and exactly three are informational`() {
        val informational = StressRecommendationKind.values().filter { it.action == StressRecommendationAction.None }.toSet()
        val actionable = StressRecommendationKind.values().toSet() - informational
        assertEquals(
            setOf(
                StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES,
                StressRecommendationKind.HEALTHY_OUTLOOK,
                StressRecommendationKind.NO_IMMEDIATE_CONCERNS
            ),
            informational
        )
        assertEquals(
            setOf(
                StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS,
                StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING,
                StressRecommendationKind.BUILD_EMERGENCY_BUFFER
            ),
            actionable
        )
    }

    @Test
    fun `INV-3 the three actionable kinds map to three distinct actions`() {
        val actions = expectedActions.filterValues { it != StressRecommendationAction.None }.values
        assertEquals(3, actions.toSet().size)
    }

    @Test
    fun `INV-8 a recommendation built from a kind alone carries no parameters`() {
        StressRecommendationKind.values().forEach { kind ->
            val recommendation = StressRecommendation(kind)
            assertEquals(kind, recommendation.kind)
            assertNull(recommendation.daysAhead)
            assertNull(recommendation.bufferTarget)
            assertNull(recommendation.bufferCurrency)
        }
    }

    @Test
    fun `INV-8 recommendation equality is structural so recompute is idempotent`() {
        val a = StressRecommendation(StressRecommendationKind.BUILD_EMERGENCY_BUFFER, bufferTarget = 500.0, bufferCurrency = "EUR")
        val b = StressRecommendation(StressRecommendationKind.BUILD_EMERGENCY_BUFFER, bufferTarget = 500.0, bufferCurrency = "EUR")
        val differentCurrency = StressRecommendation(StressRecommendationKind.BUILD_EMERGENCY_BUFFER, bufferTarget = 500.0, bufferCurrency = "USD")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != differentCurrency)
    }
}
