package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.domain.forecasting.StressRecommendation
import com.yourname.expensetracker.domain.forecasting.StressRecommendationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stress-forecast-actions AE-11 (INV-11): the card shows at most [STRESS_RECOMMENDATION_VISIBLE_CAP]
 * recommendations, taken in domain order.
 *
 * The defensive omission of an R2 row without `daysAhead` happens while rendering (a private
 * composable), not in [visibleStressRecommendations], so it is not unit-testable here.
 */
class StressRecommendationVisibleCapTest {

    private val r1 = StressRecommendation(StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS)
    private val r2 = StressRecommendation(StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING, daysAhead = 60)
    private val r3 = StressRecommendation(
        StressRecommendationKind.BUILD_EMERGENCY_BUFFER,
        bufferTarget = 500.0,
        bufferCurrency = "EUR"
    )
    private val r4 = StressRecommendation(StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES)

    @Test
    fun `INV-11 visible cap constant is two`() {
        assertEquals(2, STRESS_RECOMMENDATION_VISIBLE_CAP)
    }

    @Test
    fun `AE-11 four emitted items show the first two in domain order`() {
        val visible = visibleStressRecommendations(listOf(r1, r2, r3, r4))

        assertEquals(listOf(r1, r2), visible)
    }

    @Test
    fun `AE-11 domain order decides which two show not kind precedence`() {
        val visible = visibleStressRecommendations(listOf(r4, r3, r2, r1))

        assertEquals(listOf(r4, r3), visible)
    }

    @Test
    fun `INV-11 three items are capped at two`() {
        val visible = visibleStressRecommendations(listOf(r2, r3, r4))

        assertEquals(listOf(r2, r3), visible)
        assertEquals(STRESS_RECOMMENDATION_VISIBLE_CAP, visible.size)
    }

    @Test
    fun `INV-11 exactly two items are all shown in order`() {
        assertEquals(listOf(r1, r3), visibleStressRecommendations(listOf(r1, r3)))
    }

    @Test
    fun `INV-11 a single item is shown unchanged`() {
        assertEquals(listOf(r4), visibleStressRecommendations(listOf(r4)))
    }

    @Test
    fun `INV-11 an empty list stays empty`() {
        assertTrue(visibleStressRecommendations(emptyList()).isEmpty())
    }

    @Test
    fun `non-finite buffer target takes the no-amount fallback`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { target ->
            val rec = StressRecommendation(
                StressRecommendationKind.BUILD_EMERGENCY_BUFFER,
                bufferTarget = target,
                bufferCurrency = "EUR"
            )
            assertFalse("target=$target", hasDisplayableBufferAmount(rec))
        }
    }

    @Test
    fun `finite buffer target with currency is displayable but missing parts are not`() {
        assertTrue(hasDisplayableBufferAmount(r3))
        assertFalse(hasDisplayableBufferAmount(r3.copy(bufferCurrency = " ")))
        assertFalse(hasDisplayableBufferAmount(r3.copy(bufferCurrency = null)))
        assertFalse(hasDisplayableBufferAmount(r3.copy(bufferTarget = null)))
    }

    @Test
    fun `INV-11 the cap keeps the parameters of the shown items`() {
        val visible = visibleStressRecommendations(listOf(r2, r3, r1))

        assertEquals(60, visible[0].daysAhead)
        assertEquals(500.0, visible[1].bufferTarget)
        assertEquals("EUR", visible[1].bufferCurrency)
    }
}
