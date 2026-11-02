package com.yourname.expensetracker.domain.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure table test for [SavingsGoalDeletionPolicy] (savings-goal-delete S1).
 * Oracle: domain-spec INV-1, INV-14, INV-16, INV-18 and AE-16 (policy half), AE-19.
 */
class SavingsGoalDeletionPolicyTest {

    private val hasBalance = SavingsGoalDeletionDecision.Rejected("SAVINGS_GOAL_DELETE_HAS_BALANCE")
    private val hasHistory = SavingsGoalDeletionDecision.Rejected("SAVINGS_GOAL_DELETE_HAS_HISTORY")
    private val historyUnavailable =
        SavingsGoalDeletionDecision.Rejected("SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE")

    private fun decide(
        balance: Double,
        hasHistory: Boolean = false,
        historyUnavailable: Boolean = false
    ) = SavingsGoalDeletionPolicy.evaluate(balance, hasHistory, historyUnavailable)

    // ---- INV-14: bounded constants ------------------------------------------------------

    @Test
    fun `INV-14 reason code constants are the exact bounded literals`() {
        assertEquals("SAVINGS_GOAL_DELETE_HAS_BALANCE", SAVINGS_GOAL_DELETE_HAS_BALANCE)
        assertEquals("SAVINGS_GOAL_DELETE_HAS_HISTORY", SAVINGS_GOAL_DELETE_HAS_HISTORY)
        assertEquals("SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE", SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE)
        assertEquals("SAVINGS_GOAL_DELETE_UNAVAILABLE", SAVINGS_GOAL_DELETE_UNAVAILABLE)
        val all = listOf(
            SAVINGS_GOAL_DELETE_HAS_BALANCE,
            SAVINGS_GOAL_DELETE_HAS_HISTORY,
            SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE,
            SAVINGS_GOAL_DELETE_UNAVAILABLE
        )
        assertEquals("codes must be distinct", all.size, all.toSet().size)
    }

    // ---- INV-18 / AE-16: balance table (no history, history available) -----------------

    @Test
    fun `INV-18 control zero balance with no history is deletable`() {
        assertEquals(SavingsGoalDeletionDecision.Deletable, decide(0.0))
    }

    @Test
    fun `INV-18 negative zero counts as zero and is deletable`() {
        val negativeZero = -0.0
        // Guard the fixture: this really is the IEEE negative zero, not a plain 0.0.
        assertTrue(1.0 / negativeZero == Double.NEGATIVE_INFINITY)
        assertEquals(SavingsGoalDeletionDecision.Deletable, decide(negativeZero))
    }

    @Test
    fun `INV-18 negative zero still yields HAS_HISTORY when history exists`() {
        assertEquals(hasHistory, decide(-0.0, hasHistory = true))
    }

    @Test
    fun `INV-18 negative zero with unavailable history yields HISTORY_UNAVAILABLE`() {
        assertEquals(historyUnavailable, decide(-0.0, historyUnavailable = true))
    }

    @Test
    fun `INV-18 smallest positive balance is HAS_BALANCE`() {
        assertEquals(hasBalance, decide(0.01))
        assertEquals(hasBalance, decide(Double.MIN_VALUE))
    }

    @Test
    fun `INV-18 NaN balance fails closed as HAS_BALANCE`() {
        assertEquals(hasBalance, decide(Double.NaN))
    }

    @Test
    fun `INV-18 positive and negative infinity fail closed as HAS_BALANCE`() {
        assertEquals(hasBalance, decide(Double.POSITIVE_INFINITY))
        assertEquals(hasBalance, decide(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `INV-18 negative balances fail closed as HAS_BALANCE`() {
        assertEquals(hasBalance, decide(-1.0))
        assertEquals(hasBalance, decide(-0.01))
        assertEquals(hasBalance, decide(-Double.MIN_VALUE))
        assertEquals(hasBalance, decide(-Double.MAX_VALUE))
    }

    @Test
    fun `INV-18 large positive balance is HAS_BALANCE`() {
        assertEquals(hasBalance, decide(1_000_000.0))
        assertEquals(hasBalance, decide(Double.MAX_VALUE))
    }

    // ---- AE-19: precedence rows ----------------------------------------------------------

    @Test
    fun `AE-19 balance 10 with history unavailable yields HAS_BALANCE`() {
        assertEquals(hasBalance, decide(10.0, historyUnavailable = true))
    }

    @Test
    fun `AE-19 ledger row with history unavailable yields HAS_HISTORY`() {
        assertEquals(hasHistory, decide(0.0, hasHistory = true, historyUnavailable = true))
    }

    @Test
    fun `AE-19 no row with history unavailable yields HISTORY_UNAVAILABLE`() {
        assertEquals(historyUnavailable, decide(0.0, hasHistory = false, historyUnavailable = true))
    }

    @Test
    fun `AE-19 events present with history available yields HAS_HISTORY`() {
        assertEquals(hasHistory, decide(0.0, hasHistory = true, historyUnavailable = false))
    }

    @Test
    fun `INV-18 balance outranks history for every non-zero balance and every flag combination`() {
        val nonZero = listOf(10.0, 0.01, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (balance in nonZero) {
            for (history in listOf(false, true)) {
                for (unavailable in listOf(false, true)) {
                    assertEquals(
                        "balance=$balance history=$history unavailable=$unavailable",
                        hasBalance,
                        decide(balance, history, unavailable)
                    )
                }
            }
        }
    }

    @Test
    fun `INV-18 full precedence table for a zero balance`() {
        // (hasHistory, historyUnavailable) -> expected
        val table = listOf(
            Triple(false, false, SavingsGoalDeletionDecision.Deletable),
            Triple(true, false, hasHistory),
            Triple(false, true, historyUnavailable),
            Triple(true, true, hasHistory)
        )
        for ((history, unavailable, expected) in table) {
            assertEquals(
                "history=$history unavailable=$unavailable",
                expected,
                decide(0.0, history, unavailable)
            )
        }
    }

    // ---- INV-16 / INV-14: result shape ---------------------------------------------------

    @Test
    fun `INV-16 policy is a pure function and repeated calls agree`() {
        repeat(3) {
            assertEquals(hasBalance, decide(5.0, hasHistory = true, historyUnavailable = true))
            assertEquals(SavingsGoalDeletionDecision.Deletable, decide(0.0))
        }
    }

    @Test
    fun `INV-14 rejected decisions carry only the bounded code`() {
        val rejections = listOf(
            decide(1.0),
            decide(0.0, hasHistory = true),
            decide(0.0, historyUnavailable = true)
        ).map { it as SavingsGoalDeletionDecision.Rejected }
        val allowed = setOf(
            SAVINGS_GOAL_DELETE_HAS_BALANCE,
            SAVINGS_GOAL_DELETE_HAS_HISTORY,
            SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE
        )
        for (rejection in rejections) {
            assertTrue(rejection.reasonCode in allowed)
        }
    }
}
