package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class SettlementCalculatorStressTest {

    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).apply {
        every { homeCurrency() } returns flowOf("EUR")
    }
    private val calculator = SettlementCalculator(currencySettingsRepository = currencySettingsRepository, writeBarrier = mockk(relaxed = true))

    @Test
    fun `15 member alternating plus minus one completes within solver budget without fallback bug B_03`() = runTest {
        val balances = (1L..15L).associateWith { id ->
            val net = if (id % 2L == 0L) 1.0 else -1.0
            memberBalance(id, "M$id", net)
        }.toMutableMap()

        val originalSum = balances.values.sumOf { it.netBalance }
        balances[15L] = memberBalance(15L, "M15", balances.getValue(15L).netBalance - originalSum)

        val settlements = calculator.calculateSettlements(balances)
        val elapsedNs = measureNanoTime {
            assertTrue(settlements.all { it.amount > 0.0 })
            assertFalse(settlements.any { it.usedGreedyFallback })
            assertApproxEquals(7.0, settlements.sumOf { it.amount }, 0.01)
        }

        assertTrue(elapsedNs < 500_000_000L)
    }

    @Test
    fun `all zero balances return empty plan immediately`() = runTest {
        val balances = (1L..15L).associateWith { id -> memberBalance(id, "M$id", 0.0) }

        val settlements = calculator.calculateSettlements(balances)
        val elapsedNs = measureNanoTime {
            assertTrue(settlements.isEmpty())
        }

        assertTrue(elapsedNs < 100_000_000L)
    }

    @Test
    fun `larger balanced case still preserves settlement volume invariant`() = runTest {
        val balances = mapOf(
            1L to memberBalance(1L, "A", 10.0),
            2L to memberBalance(2L, "B", 10.0),
            3L to memberBalance(3L, "C", 10.0),
            4L to memberBalance(4L, "D", -12.0),
            5L to memberBalance(5L, "E", -8.0),
            6L to memberBalance(6L, "F", -10.0)
        )

        val settlements = calculator.calculateSettlements(balances)
        val totalNegativeAbs = balances.values.filter { it.netBalance < 0.0 }.sumOf { -it.netBalance }

        assertApproxEquals(totalNegativeAbs, settlements.sumOf { it.amount }, 0.01)
        assertEquals(settlements.size, calculator.getTransactionCount(settlements))
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
