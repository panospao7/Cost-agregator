package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander.OccurrenceCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * S6c-2b: [OccurrenceConflictResolver] treats an expense that the installment link policy excludes
 * (it pays an installment entry, or is a plan purchase record) as a non-candidate, so the occurrence
 * stays PLANNED and unlinked (INV-5, domain spec row (d)). The exclusion set is read once per call.
 * Pure JVM: the reader is an in-memory fake.
 */
class OccurrenceConflictResolverInstallmentExclusionTest {

    private class FakeReader(
        private val linkedExpenseIds: Set<Long> = emptySet(),
        private val planPurchaseIds: Set<Long> = emptySet()
    ) : InstallmentEntryLinkReader {
        var batchReads = 0
        var perExpenseReads = 0

        override suspend fun linkedEntryIdFor(expenseId: Long): Long? {
            perExpenseReads++
            return if (expenseId in linkedExpenseIds) 1L else null
        }

        override suspend fun isPlanPurchase(expenseId: Long): Boolean {
            perExpenseReads++
            return expenseId in planPurchaseIds
        }

        override suspend fun linkedExpenseIds(): Set<Long> {
            batchReads++
            return linkedExpenseIds
        }

        override suspend fun planPurchaseExpenseIds(): Set<Long> {
            batchReads++
            return planPurchaseIds
        }
    }

    private val day = 1_700_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun candidate(due: Long = day, key: String = "RECURRING_RULE|1|$due|MONTHLY") = OccurrenceCandidate(
        occurrenceKey = key,
        dueDate = due,
        expectedAmount = 15.99,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Netflix",
        categoryId = null,
        sourceType = "RECURRING_RULE",
        sourceId = 1L
    )

    private fun expense(
        id: Long,
        date: Long = day,
        reason: String? = null,
        type: TransactionType = TransactionType.PURCHASE
    ) = Expense(
        id = id,
        amount = 15.99,
        currency = "EUR",
        merchant = "Netflix",
        transactionType = type,
        date = date,
        createdAt = date,
        source = "test",
        spendingExclusionReason = reason
    )

    private fun resolver(reader: FakeReader) =
        OccurrenceConflictResolver(RecurringInstallmentEligibility(dagger.Lazy { reader }))

    @Test
    fun aNormalExpenseStillPaysTheOccurrence() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader()).resolve(listOf(candidate()), listOf(expense(1L)))

        assertEquals("PAID", resolved.single().status)
        assertEquals(1L, resolved.single().linkedExpenseId)
    }

    @Test
    fun anInstallmentLinkedExpenseLeavesTheOccurrencePlanned() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader(linkedExpenseIds = setOf(1L)))
            .resolve(listOf(candidate()), listOf(expense(1L)))

        assertEquals("PLANNED", resolved.single().status)
        assertNull(resolved.single().linkedExpenseId)
        assertNull(resolved.single().paidAmount)
    }

    @Test
    fun aPlanPurchaseRowWithoutTheFlagLeavesTheOccurrencePlanned() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader(planPurchaseIds = setOf(1L)))
            .resolve(listOf(candidate()), listOf(expense(1L)))

        assertEquals("PLANNED", resolved.single().status)
        assertNull(resolved.single().linkedExpenseId)
    }

    @Test
    fun aPurchaseFlagWithNoPlanRowLeavesTheOccurrencePlanned() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader())
            .resolve(listOf(candidate()), listOf(expense(1L, reason = "INSTALLMENT_PURCHASE")))

        assertEquals("PLANNED", resolved.single().status)
        assertNull(resolved.single().linkedExpenseId)
    }

    @Test
    fun aReversedFlagWithNoPlanRowLeavesTheOccurrencePlanned() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader())
            .resolve(listOf(candidate()), listOf(expense(1L, reason = "INSTALLMENT_REVERSED")))

        assertEquals("PLANNED", resolved.single().status)
        assertNull(resolved.single().linkedExpenseId)
    }

    @Test
    fun aNormalExpenseBesideAnExcludedOneStillPaysTheOccurrence() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader(linkedExpenseIds = setOf(1L)))
            .resolve(listOf(candidate()), listOf(expense(1L), expense(2L)))

        assertEquals("PAID", resolved.single().status)
        assertEquals(2L, resolved.single().linkedExpenseId)
    }

    @Test
    fun theExclusionSetIsReadOnceForManyCandidatesAndExpenses() = runTest(timeout = 60.seconds) {
        val reader = FakeReader(linkedExpenseIds = setOf(2L), planPurchaseIds = setOf(3L))
        val candidates = listOf(
            candidate(due = day),
            candidate(due = day + dayMs),
            candidate(due = day + 2 * dayMs)
        )
        val expenses = listOf(
            expense(1L, date = day),
            expense(2L, date = day + dayMs),
            expense(3L, date = day + 2 * dayMs),
            expense(4L, date = day + 5 * dayMs)
        )

        val resolved = resolver(reader).resolve(candidates, expenses)

        assertEquals(listOf("PAID", "PLANNED", "PLANNED"), resolved.map { it.status })
        assertEquals("two batch reads (linked set and plan set), once per resolve call", 2, reader.batchReads)
        assertEquals("never one read per expense", 0, reader.perExpenseReads)
    }

    // Owner item 37(b): the layer-1 type rule (RecurringLinkTypeEligibility.mayCreateLink) is unchanged here.
    @Test
    fun anUnknownExpenseLeavesTheOccurrencePlannedAndAWithdrawalStillPaysIt() = runTest(timeout = 60.seconds) {
        val unknown = resolver(FakeReader())
            .resolve(listOf(candidate()), listOf(expense(1L, type = TransactionType.UNKNOWN)))
        assertEquals("PLANNED", unknown.single().status)
        assertNull(unknown.single().linkedExpenseId)

        val withdrawal = resolver(FakeReader())
            .resolve(listOf(candidate()), listOf(expense(1L, type = TransactionType.WITHDRAWAL)))
        assertEquals("PAID", withdrawal.single().status)
        assertEquals(1L, withdrawal.single().linkedExpenseId)
    }

    @Test
    fun anUnknownExpenseBesideAPurchaseDoesNotBlockTheMatch() = runTest(timeout = 60.seconds) {
        val resolved = resolver(FakeReader()).resolve(
            listOf(candidate()),
            listOf(expense(1L, type = TransactionType.UNKNOWN), expense(2L))
        )

        assertEquals("PAID", resolved.single().status)
        assertEquals(2L, resolved.single().linkedExpenseId)
    }

    @Test
    fun noCandidatesOrNoExpensesReadNothing()= runTest(timeout = 60.seconds) {
        val reader = FakeReader()
        val subject = resolver(reader)

        assertEquals(emptyList<Any>(), subject.resolve(emptyList(), listOf(expense(1L))))
        assertEquals("PLANNED", subject.resolve(listOf(candidate()), emptyList()).single().status)

        assertEquals(0, reader.batchReads)
        assertEquals(0, reader.perExpenseReads)
    }
}
