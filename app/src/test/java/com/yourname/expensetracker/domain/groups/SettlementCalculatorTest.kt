package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.assertApproxEquals
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementCalculatorTest {

    private val calculator = SettlementCalculator(currencySettingsRepository = mockk(), writeBarrier = mockk(relaxed = true))

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

        val summary = calculator.getSettlementSummary(settlements, "EUR")

        assertTrue(summary.contains("B pays A: ${com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(30.0, "EUR")}"))
        assertTrue(summary.contains("C pays A: ${com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(20.0, "EUR")}"))
        assertTrue(summary.contains("Total to settle: ${com.yourname.expensetracker.domain.util.CurrencyFormatter.formatMoney(50.0, "EUR")}"))
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

    // ── fix-1.0.4 (owner-approved revalidation fix, item 143): settlement uses the currency's minor units ──

    @Test
    fun `JPY settlement amounts and total are whole units`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 1000.0, "JPY"),
            2L to memberBalance(2L, "B", -667.0, "JPY"),
            3L to memberBalance(3L, "C", -333.0, "JPY")
        )

        val settlements = calculator.calculateSettlements(balances)

        assertEquals(listOf(667.0, 333.0), settlements.sortedBy { it.fromMemberId }.map { it.amount })
        assertTrue(settlements.all { it.currency == "JPY" })
        assertEquals(1000.0, calculator.getTotalSettlementAmount(settlements), 0.0)
    }

    @Test
    fun `JPY fractional balances are rounded to whole units and conserve the total`() = runTest {
        // 100.4 -> 100 and -100.6 -> -101: the 1 yen imbalance is absorbed by the largest debtor.
        val balances = mapOf(
            1L to memberBalance(1L, "A", 100.4, "JPY"),
            2L to memberBalance(2L, "B", -100.6, "JPY")
        )

        val settlements = calculator.calculateSettlements(balances)

        assertEquals(1, settlements.size)
        assertEquals(100.0, settlements.single().amount, 0.0)
        assertEquals(100.0, calculator.getTotalSettlementAmount(settlements), 0.0)
    }

    @Test
    fun `EUR settlement keeps two decimals`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 10.01, "EUR"),
            2L to memberBalance(2L, "B", -6.67, "EUR"),
            3L to memberBalance(3L, "C", -3.34, "EUR")
        )

        val settlements = calculator.calculateSettlements(balances)

        assertEquals(listOf(6.67, 3.34), settlements.sortedBy { it.fromMemberId }.map { it.amount })
        assertEquals(10.01, calculator.getTotalSettlementAmount(settlements), 0.0)
    }

    @Test
    fun `unknown currency settlement keeps two decimals`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 10.01, "ZZZ"),
            2L to memberBalance(2L, "B", -10.01, "ZZZ")
        )

        val settlements = calculator.calculateSettlements(balances)

        assertEquals(10.01, settlements.single().amount, 0.0)
    }

    private fun memberBalance(id: Long, name: String, net: Double, currency: String = "EUR"): MemberBalance {
        val paid = if (net > 0) net else 0.0
        val shouldPay = if (net < 0) -net else 0.0
        return MemberBalance(
            memberId = id,
            memberName = name,
            paid = paid,
            shouldPay = shouldPay,
            netBalance = net,
            currency = currency
        )
    }
}
