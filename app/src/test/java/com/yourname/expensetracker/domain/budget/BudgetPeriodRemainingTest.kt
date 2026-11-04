package com.yourname.expensetracker.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FL-06: the single "money left in the budget period" figure. Pure function, no mocks.
 *
 * Boundary values for the half-minor-unit rule use binary-exact excesses (2^-8 = 0.00390625 just
 * below 0.005, 2^-7 = 0.0078125 just above) on a limit of 1.0, because a decimal literal such as
 * 1.005 - 1.0 is 0.004999999999999893 in IEEE-754 and would test the double rounding, not the rule.
 */
class BudgetPeriodRemainingTest {

    private val dayMs = 86_400_000L
    private val nowMs = 1_705_320_000_000L // 2024-01-15 12:00 UTC
    private val periodEnd = nowMs + 10 * dayMs

    private fun compute(
        limit: Double = 1_000.0,
        effectiveSpend: Double? = 400.0,
        complete: Boolean = true,
        percentKnown: Boolean = true,
        end: Long = periodEnd
    ) = budgetPeriodRemaining(limit, effectiveSpend, complete, percentKnown, end, nowMs)

    private fun reason(result: BudgetPeriodRemaining) =
        (result as BudgetPeriodRemaining.Unavailable).reason

    @Test
    fun `available under the limit remaining is limit minus effective spend and overBy is 0`() {
        val result = compute(limit = 1_000.0, effectiveSpend = 400.0) as BudgetPeriodRemaining.Available

        assertEquals(1_000.0, result.limit, 0.0)
        assertEquals(400.0, result.effectiveSpend, 0.0)
        assertEquals(600.0, result.remaining, 0.0)
        assertEquals(0.0, result.overBy, 0.0)
    }

    @Test
    fun `available over the limit remaining clamps to 0 and overBy is the excess`() {
        val result = compute(limit = 100.0, effectiveSpend = 150.0) as BudgetPeriodRemaining.Available

        assertEquals(0.0, result.remaining, 0.0)
        assertEquals(50.0, result.overBy, 0.0)
    }

    @Test
    fun `available at exactly the limit has remaining 0 and overBy 0`() {
        val result = compute(limit = 100.0, effectiveSpend = 100.0) as BudgetPeriodRemaining.Available

        assertEquals(0.0, result.remaining, 0.0)
        assertEquals(0.0, result.overBy, 0.0)
    }

    @Test
    fun `overBy is 0 just below half a minor unit past the limit`() {
        val result = compute(limit = 1.0, effectiveSpend = 1.0 + 0.00390625) as BudgetPeriodRemaining.Available

        assertEquals(0.0, result.overBy, 0.0)
        assertEquals(0.0, result.remaining, 0.0)
    }

    @Test
    fun `overBy is the excess once it reaches half a minor unit past the limit`() {
        val result = compute(limit = 1.0, effectiveSpend = 1.0 + 0.0078125) as BudgetPeriodRemaining.Available

        assertEquals(0.0078125, result.overBy, 0.0)
        assertEquals(0.0, result.remaining, 0.0)
    }

    @Test
    fun `incomplete conversion is unavailable with CONVERSION_INCOMPLETE`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.CONVERSION_INCOMPLETE,
            reason(compute(complete = false))
        )
    }

    @Test
    fun `unknown percent is unavailable with CONVERSION_INCOMPLETE`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.CONVERSION_INCOMPLETE,
            reason(compute(percentKnown = false))
        )
    }

    @Test
    fun `zero limit is unavailable with NO_POSITIVE_LIMIT`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.NO_POSITIVE_LIMIT,
            reason(compute(limit = 0.0, effectiveSpend = 50.0))
        )
    }

    @Test
    fun `negative limit is unavailable with NO_POSITIVE_LIMIT`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.NO_POSITIVE_LIMIT,
            reason(compute(limit = -10.0, effectiveSpend = 50.0))
        )
    }

    @Test
    fun `null effective spend is unavailable with EFFECTIVE_SPEND_UNAVAILABLE`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.EFFECTIVE_SPEND_UNAVAILABLE,
            reason(compute(effectiveSpend = null))
        )
    }

    @Test
    fun `non finite effective spend is unavailable with EFFECTIVE_SPEND_UNAVAILABLE`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertEquals(
                "effective spend $bad",
                BudgetPeriodRemainingUnavailableReason.EFFECTIVE_SPEND_UNAVAILABLE,
                reason(compute(effectiveSpend = bad))
            )
        }
    }

    @Test
    fun `reason order is conversion incomplete first even with no limit and no effective spend`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.CONVERSION_INCOMPLETE,
            reason(compute(limit = 0.0, effectiveSpend = null, complete = false))
        )
    }

    @Test
    fun `reason order is no positive limit before effective spend unavailable`() {
        assertEquals(
            BudgetPeriodRemainingUnavailableReason.NO_POSITIVE_LIMIT,
            reason(compute(limit = 0.0, effectiveSpend = null))
        )
    }

    @Test
    fun `daysLeft is never negative once the period has ended`() {
        val ended = compute(end = nowMs - 5 * dayMs) as BudgetPeriodRemaining.Available

        assertEquals(0, ended.daysLeft)
    }

    @Test
    fun `daysLeft grows with the period end`() {
        val near = compute(end = nowMs + 3 * dayMs) as BudgetPeriodRemaining.Available
        val far = compute(end = nowMs + 30 * dayMs) as BudgetPeriodRemaining.Available

        assertTrue("near ${near.daysLeft} far ${far.daysLeft}", far.daysLeft > near.daysLeft)
    }
}
