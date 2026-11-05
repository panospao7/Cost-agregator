package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.CurrentUserSettlementRow
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.GroupCashFlowReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** DR-01: the single pure group cash-flow rule. Synthetic rows only. */
class GroupCashFlowRuleTest {

    private fun row(
        id: Long,
        type: TransactionType = TransactionType.PURCHASE,
        amount: Double = 3000.0
    ) = Expense(
        id = id,
        amount = amount,
        merchant = "Synthetic $id",
        transactionType = type,
        date = 1_700_000_000_000L,
        categoryId = 1L
    )

    private fun settlement(id: Long, linked: Long?) = CurrentUserSettlementRow(
        id = id,
        amount = 10.0,
        currency = "EUR",
        createdAt = 1_700_000_000_000L,
        linkedExpenseId = linked,
        paidByCurrentUser = true
    )

    @Test
    fun `classify returns NON_PAYER for a non-payer id`() {
        assertEquals(
            GroupCashFlowRule.RowClass.NON_PAYER,
            GroupCashFlowRule.classify(1L, nonPayerIds = setOf(1L), payerIds = setOf(2L))
        )
    }

    @Test
    fun `classify returns PAYER for a payer id`() {
        assertEquals(
            GroupCashFlowRule.RowClass.PAYER,
            GroupCashFlowRule.classify(2L, nonPayerIds = setOf(1L), payerIds = setOf(2L))
        )
    }

    @Test
    fun `classify returns REGULAR for an unlinked id and for empty sets`() {
        assertEquals(
            GroupCashFlowRule.RowClass.REGULAR,
            GroupCashFlowRule.classify(3L, nonPayerIds = setOf(1L), payerIds = setOf(2L))
        )
        assertEquals(
            GroupCashFlowRule.RowClass.REGULAR,
            GroupCashFlowRule.classify(1L, emptySet(), emptySet())
        )
    }

    @Test
    fun `classify boundary - an id in both sets is NON_PAYER`() {
        assertEquals(
            GroupCashFlowRule.RowClass.NON_PAYER,
            GroupCashFlowRule.classify(5L, nonPayerIds = setOf(5L), payerIds = setOf(5L))
        )
    }

    @Test
    fun `asFullAmountCopy counts the full paid amount and drops share fields`() {
        val shared = row(1L).copy(
            isNotMine = true,
            isSharedExpense = true,
            mySharePercentage = 20.0,
            myShareAmount = 600.0
        )

        val copy = GroupCashFlowRule.asFullAmountCopy(shared)

        assertFalse(copy.isNotMine)
        assertFalse(copy.isSharedExpense)
        assertEquals(null, copy.mySharePercentage)
        assertEquals(null, copy.myShareAmount)
        assertEquals(3000.0, copy.effectiveAmount, 0.0)
        assertEquals(shared.id, copy.id)
        assertEquals(shared.amount, copy.amount, 0.0)
        assertEquals(shared.date, copy.date)
        assertEquals(shared.currency, copy.currency)
        assertEquals(shared.transactionType, copy.transactionType)
    }

    @Test
    fun `asFullAmountCopy leaves the stored row and its effective amount unchanged`() {
        val shared = row(1L).copy(isSharedExpense = true, myShareAmount = 500.0)

        GroupCashFlowRule.asFullAmountCopy(shared)

        assertTrue(shared.isSharedExpense)
        assertEquals(500.0, shared.myShareAmount!!, 0.0)
        assertEquals(500.0, shared.effectiveAmount, 0.0)
    }

    @Test
    fun `countedExpenseIds counts deposit purchase and withdrawal`() {
        val rows = listOf(
            row(1L, TransactionType.DEPOSIT),
            row(2L, TransactionType.PURCHASE),
            row(3L, TransactionType.WITHDRAWAL)
        )

        assertEquals(setOf(1L, 2L, 3L), GroupCashFlowRule.countedExpenseIds(rows, emptySet()))
    }

    @Test
    fun `countedExpenseIds boundary - transfers and non-payer rows are not counted`() {
        val rows = listOf(
            row(1L, TransactionType.TRANSFER),
            row(2L, TransactionType.PURCHASE),
            row(3L, TransactionType.PURCHASE)
        )

        assertEquals(setOf(3L), GroupCashFlowRule.countedExpenseIds(rows, nonPayerIds = setOf(2L)))
        assertEquals(emptySet<Long>(), GroupCashFlowRule.countedExpenseIds(emptyList(), emptySet()))
    }

    @Test
    fun `dedupeSettlements keeps unlinked and uncounted-link settlements`() {
        val unlinked = settlement(1L, null)
        val otherLink = settlement(2L, 99L)

        assertEquals(
            listOf(unlinked, otherLink),
            GroupCashFlowRule.dedupeSettlements(listOf(unlinked, otherLink), countedExpenseIds = setOf(10L))
        )
    }

    @Test
    fun `dedupeSettlements boundary - a settlement linked to a counted expense is dropped`() {
        val unlinked = settlement(1L, null)
        val counted = settlement(2L, 10L)

        assertEquals(
            listOf(unlinked),
            GroupCashFlowRule.dedupeSettlements(listOf(unlinked, counted), countedExpenseIds = setOf(10L))
        )
    }

    @Test
    fun `dedupeSettlements keeps a settlement of a non-payer row because that row is not counted`() {
        val rows = listOf(row(10L))
        val linked = settlement(2L, 10L)
        val counted = GroupCashFlowRule.countedExpenseIds(rows, nonPayerIds = setOf(10L))

        assertEquals(listOf(linked), GroupCashFlowRule.dedupeSettlements(listOf(linked), counted))
    }
}

/** DR-01 reader: one typed failure flag per probe, cancellation rethrown, barrier called per read. */
class GroupCashFlowReaderTest {

    private val prefix = "Synthetic.op"
    private val start = 1_000L
    private val end = 2_000L

    private val groupExpenseDao = mockk<GroupExpenseDao>()
    private val groupSettlementDao = mockk<GroupSettlementDao>()
    private val barrier = mockk<DatabaseReadBarrier>(relaxed = true)
    private val reader = GroupCashFlowReader(groupExpenseDao, groupSettlementDao, barrier)

    private val settlementRow = CurrentUserSettlementRow(1L, 25.0, "EUR", 1_500L, null, false)

    private fun givenAllReadsSucceed() {
        coEvery { groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end) } returns listOf(1L, 1L, 2L)
        coEvery { groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end) } returns listOf(3L)
        coEvery { groupSettlementDao.getCurrentUserSettlementsBetween(start, end) } returns listOf(settlementRow)
    }

    @Test
    fun `all reads succeed - sets, settlements and no flags`() = runTest {
        givenAllReadsSucceed()

        val snapshot = reader.read(start, end, prefix)

        assertEquals(setOf(1L, 2L), snapshot.nonPayerExpenseIds)
        assertEquals(setOf(3L), snapshot.payerExpenseIds)
        assertEquals(listOf(settlementRow), snapshot.settlements)
        assertFalse(snapshot.nonPayerReadFailed)
        assertFalse(snapshot.payerReadFailed)
        assertFalse(snapshot.settlementReadFailed)
        assertFalse(snapshot.anyReadFailed)
    }

    @Test
    fun `non-payer read failure sets only its flag and still reads the others`() = runTest {
        givenAllReadsSucceed()
        coEvery { groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end) } throws IllegalStateException("boom")

        val snapshot = reader.read(start, end, prefix)

        assertTrue(snapshot.nonPayerReadFailed)
        assertFalse(snapshot.payerReadFailed)
        assertFalse(snapshot.settlementReadFailed)
        assertTrue(snapshot.anyReadFailed)
        assertEquals(emptySet<Long>(), snapshot.nonPayerExpenseIds)
        assertEquals(setOf(3L), snapshot.payerExpenseIds)
        assertEquals(listOf(settlementRow), snapshot.settlements)
    }

    @Test
    fun `payer read failure sets only its flag`() = runTest {
        givenAllReadsSucceed()
        coEvery { groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end) } throws IllegalStateException("boom")

        val snapshot = reader.read(start, end, prefix)

        assertFalse(snapshot.nonPayerReadFailed)
        assertTrue(snapshot.payerReadFailed)
        assertFalse(snapshot.settlementReadFailed)
        assertEquals(emptySet<Long>(), snapshot.payerExpenseIds)
    }

    @Test
    fun `settlement read failure sets only its flag`() = runTest {
        givenAllReadsSucceed()
        coEvery { groupSettlementDao.getCurrentUserSettlementsBetween(start, end) } throws IllegalStateException("boom")

        val snapshot = reader.read(start, end, prefix)

        assertFalse(snapshot.nonPayerReadFailed)
        assertFalse(snapshot.payerReadFailed)
        assertTrue(snapshot.settlementReadFailed)
        assertTrue(snapshot.settlements.isEmpty())
    }

    @Test
    fun `a blocked barrier on the settlement read is a settlement failure not an escape`() = runTest {
        givenAllReadsSucceed()
        every { barrier.checkReadAllowed("$prefix.readGroupSettlements") } throws IllegalStateException("blocked")

        val snapshot = reader.read(start, end, prefix)

        assertTrue(snapshot.settlementReadFailed)
        assertFalse(snapshot.nonPayerReadFailed)
        coVerify(exactly = 0) { groupSettlementDao.getCurrentUserSettlementsBetween(any(), any()) }
    }

    @Test
    fun `barrier is checked before each of the three reads with the prefixed operation`() = runTest {
        givenAllReadsSucceed()

        reader.read(start, end, prefix)

        verify(exactly = 1) { barrier.checkReadAllowed("$prefix.readGroupPayers") }
        verify(exactly = 1) { barrier.checkReadAllowed("$prefix.readGroupPayerRows") }
        verify(exactly = 1) { barrier.checkReadAllowed("$prefix.readGroupSettlements") }
    }

    @Test
    fun `cancellation from the non-payer read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        givenAllReadsSucceed()
        coEvery { groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end) } throws cancellation

        assertSame(cancellation, runCatching { reader.read(start, end, prefix) }.exceptionOrNull())
    }

    @Test
    fun `cancellation from the payer read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        givenAllReadsSucceed()
        coEvery { groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end) } throws cancellation

        assertSame(cancellation, runCatching { reader.read(start, end, prefix) }.exceptionOrNull())
    }

    @Test
    fun `cancellation from the settlement read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        givenAllReadsSucceed()
        coEvery { groupSettlementDao.getCurrentUserSettlementsBetween(start, end) } throws cancellation

        assertSame(cancellation, runCatching { reader.read(start, end, prefix) }.exceptionOrNull())
    }
}
