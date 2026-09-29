package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class SharedExpenseManagerSettlementTest {
    private val dispatcher = StandardTestDispatcher()
    private val port = mockk<SharedExpenseDataPort>(relaxed = true)
    private val manager = SharedExpenseManager(port, object : TimeProvider {
        override fun now() = 2_000L
    }, mockk(), dispatcher)

    private fun fixture(payments: List<SharedGroupSettlement>) {
        coEvery { port.getGroupOnce(7L) } returns SharedExpenseGroup(7L, "Trip", defaultCurrency = "EUR")
        coEvery { port.getGroupMembersOnce(7L) } returns listOf(
            SharedExpenseMember(1L, 7L, "Debtor", joinedAt = 0L, leftAt = 1_500L),
            SharedExpenseMember(2L, 7L, "Creditor", joinedAt = 0L))
        coEvery { port.getGroupExpensesOnce(7L) } returns listOf(
            SharedGroupExpense(1L, 7L, null, 2L, 1_000L, "Dinner", 100.0, "EUR"))
        coEvery { port.getGroupSettlementsOnce(7L) } returns payments
    }

    @Test
    fun partialPaymentIncludesDepartedMemberWithoutChangingExpenseTotals() = runTest(dispatcher) {
        fixture(listOf(SharedGroupSettlement(7L, 1L, 2L, 20.0, "EUR", "RECORDED")))
        val result = manager.calculateBalances(7L)
        assertEquals(-30.0, result.getValue(1L).netBalance, 0.0)
        assertEquals(30.0, result.getValue(2L).netBalance, 0.0)
        assertEquals(0.0, result.getValue(1L).paid, 0.0)
        assertEquals(100.0, result.getValue(2L).paid, 0.0)
        assertTrue(result.values.all { it.shouldPay == 50.0 && it.currency == "EUR" })
        val suggestions = SettlementCalculator(mockk(), mockk(relaxed = true))
            .calculateSettlements(result, "EUR")
        assertEquals(30.0, suggestions.single().amount, 0.0)
    }

    @Test
    fun fullPaymentProducesNoDuplicateSettlementSuggestion() = runTest(dispatcher) {
        fixture(listOf(SharedGroupSettlement(7L, 1L, 2L, 50.0, "EUR", "COMPLETED")))
        val result = manager.calculateBalances(7L)
        assertTrue(result.values.all { it.netBalance == 0.0 })
        assertTrue(SettlementCalculator(mockk(), mockk(relaxed = true))
            .calculateSettlements(result, "EUR").isEmpty())
    }

    // Non-copying sentinels: the framework may copy cross-boundary throwables;
    // createCopy()=null keeps the original instance so assertSame detects
    // application wrapping/replacement.
    private class IdentityQueryFailure(message: String) :
        IllegalStateException(message), CopyableThrowable<IdentityQueryFailure> {
        override fun createCopy(): IdentityQueryFailure? = null
    }

    private class IdentityReadCancellation(message: String) :
        CancellationException(message), CopyableThrowable<IdentityReadCancellation> {
        override fun createCopy(): IdentityReadCancellation? = null
    }

    @Test
    fun settlementReadFailureDoesNotReturnExpenseOnlyBalances() = runTest(dispatcher) {
        fixture(emptyList())
        val failure = IdentityQueryFailure("query failed")
        coEvery { port.getGroupSettlementsOnce(7L) } throws failure
        assertSame(failure, assertFailsWith<IllegalStateException> { manager.calculateBalances(7L) })
    }

    @Test
    fun settlementReadCancellationPropagates() = runTest(dispatcher) {
        fixture(emptyList())
        val cancellation = IdentityReadCancellation("cancelled")
        coEvery { port.getGroupSettlementsOnce(7L) } throws cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { manager.calculateBalances(7L) })
    }
}
