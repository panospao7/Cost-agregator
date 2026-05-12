package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.assertApproxEquals
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementCalculatorTest {

    private val calculator = SettlementCalculator(currencySettingsRepository = mockk())

    @Test
    fun `crash test 4_6 triangle debt yields two transactions totaling 50`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 50.0),
            2L to memberBalance(2L, "B", -30.0),
            3L to memberBalance(3L, "C", -20.0)
        )

        val settlements = calculator.calculateSettlements(balances)

        assertEquals(2, settlements.size)
        assertApproxEquals(50.0, calculator.getTotalSettlementAmount(settlements), 0.01)
        assertEquals(2, calculator.getTransactionCount(settlements))
        assertTrue(settlements.none { it.usedGreedyFallback })
        val bToA = settlements.firstOrNull { it.fromMemberId == 2L && it.toMemberId == 1L }
        val cToA = settlements.firstOrNull { it.fromMemberId == 3L && it.toMemberId == 1L }
        assertTrue(bToA != null)
        assertTrue(cToA != null)
        assertApproxEquals(30.0, bToA?.amount ?: 0.0, 0.01)
        assertApproxEquals(20.0, cToA?.amount ?: 0.0, 0.01)
    }

    @Test
    fun `crash test 4_7 four member case resolves in three transactions and preserves volume`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 3.0),
            2L to memberBalance(2L, "B", 6.0),
            3L to memberBalance(3L, "C", -4.0),
            4L to memberBalance(4L, "D", -5.0)
        )

        val settlements = calculator.calculateSettlements(balances)

        assertEquals(3, settlements.size)
        assertApproxEquals(9.0, settlements.sumOf { it.amount }, 0.01)
        assertTrue(settlements.all { it.amount > 0.0 })
        assertTrue(settlements.none { it.usedGreedyFallback })
    }

    @Test
    fun `calculateSettlementsMinAmount returns same result as primary DFS solver`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 10.0),
            2L to memberBalance(2L, "B", 20.0),
            3L to memberBalance(3L, "C", -10.0),
            4L to memberBalance(4L, "D", -20.0)
        )

        val primary = calculator.calculateSettlements(balances)
        val minAmount = calculator.calculateSettlementsMinAmount(balances)

        assertEquals(primary.size, minAmount.size)
        assertApproxEquals(
            primary.sumOf { it.amount },
            minAmount.sumOf { it.amount },
            0.01
        )
    }

    @Test
    fun `settlement summary includes total volume and transaction count`() {
        val settlements = listOf(
            Settlement(
                fromMemberId = 2L,
                fromMemberName = "B",
                toMemberId = 1L,
                toMemberName = "A",
                amount = 30.0,
                currency = "EUR"
            ),
            Settlement(
                fromMemberId = 3L,
                fromMemberName = "C",
                toMemberId = 1L,
                toMemberName = "A",
                amount = 20.0,
                currency = "EUR"
            )
        )

        val summary = calculator.getSettlementSummary(settlements, "€")

        assertTrue(summary.contains("B pays A: €30.00"))
        assertTrue(summary.contains("C pays A: €20.00"))
        assertTrue(summary.contains("Total to settle: €50.00"))
        assertTrue(summary.contains("2 transactions needed"))
    }

    @Test
    fun `settlement summary includes greedy fallback marker when flagged`() {
        val settlements = listOf(
            Settlement(
                fromMemberId = 2L,
                fromMemberName = "B",
                toMemberId = 1L,
                toMemberName = "A",
                amount = 10.0,
                usedGreedyFallback = true,
                currency = "EUR"
            )
        )

        val summary = calculator.getSettlementSummary(settlements, "\$")

        assertTrue(summary.contains("Approximate plan used due to solver budget limit"))
    }

    @Test
    fun `empty or all settled balances return no settlements`() = runTest {
        val empty = calculator.calculateSettlements(emptyMap())
        val settled = calculator.calculateSettlements(
            mapOf(
                1L to memberBalance(1L, "A", 0.0),
                2L to memberBalance(2L, "B", 0.0)
            )
        )

        assertTrue(empty.isEmpty())
        assertTrue(settled.isEmpty())
        assertFalse(calculator.getSettlementSummary(empty, "€").isBlank())
    }

    private fun memberBalance(id: Long, name: String, net: Double): MemberBalance {
        val paid = if (net > 0) net else 0.0
        val shouldPay = if (net < 0) -net else 0.0
        return MemberBalance(
            memberId = id,
            memberName = name,
            paid = paid,
            shouldPay = shouldPay,
            netBalance = net,
            currency = "EUR"
        )
    }
}