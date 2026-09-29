package com.yourname.expensetracker.domain.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class GroupSettlementBalancePolicyTest {
    private val balances = mapOf(1L to -50.0, 2L to 50.0)
    private fun payment(amount: Double, status: String = "RECORDED") =
        SharedGroupSettlement(7L, 1L, 2L, amount, "EUR", status)

    @Test
    fun partialAndMultiplePaymentsMoveBothSidesTowardZero() {
        assertEquals(mapOf(1L to -30.0, 2L to 30.0),
            GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(20.0)), 7L, "EUR"))
        val result = GroupSettlementBalancePolicy.applyToBalances(
            balances, listOf(payment(20.0), payment(30.0, "COMPLETED")), 7L, "EUR")
        assertEquals(mapOf(1L to 0.0, 2L to 0.0), result)
        assertEquals(0.0, result.values.sum(), 0.0)
    }

    @Test
    fun unknownCancelledForeignAndOtherGroupPaymentsAreExcluded() {
        val excluded = listOf(payment(10.0, "UNKNOWN"), payment(10.0, "CANCELLED"),
            payment(10.0).copy(currency = "USD"), payment(10.0).copy(groupId = 8L))
        assertEquals(balances, GroupSettlementBalancePolicy.applyToBalances(balances, excluded, 7L, "EUR"))
    }

    @Test
    fun zeroAndOverpaymentsAreNotClamped() {
        assertEquals(balances, GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(0.0)), 7L, "EUR"))
        assertEquals(mapOf(1L to 10.0, 2L to -10.0),
            GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(60.0)), 7L, "EUR"))
    }

    @Test
    fun decimalAccumulationDoesNotIntroduceFloatingPointResidue() {
        val result = GroupSettlementBalancePolicy.applyToBalances(
            mapOf(1L to -0.3, 2L to 0.3), listOf(payment(0.1), payment(0.2)), 7L, "EUR")
        assertTrue(result.values.all { it == 0.0 })
    }

    @Test
    fun nonFiniteEffectiveAmountsFailInsteadOfHidingPaymentHistory() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { amount ->
            assertFailsWith<NumberFormatException> {
                GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(amount)), 7L, "EUR")
            }
        }
    }
}
