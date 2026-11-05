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
 * Cross-rule claim seed: expense ids already claimed by an earlier rule in the same read-only
 * projection loop are never matched again by [OccurrenceConflictResolver.resolve].
 * Pure JVM: the installment reader is an empty in-memory fake.
 */
class OccurrenceConflictResolverClaimedTest {

    private class EmptyReader : InstallmentEntryLinkReader {
        override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
        override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
        override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
        override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
    }

    private val day = 1_700_000_000_000L

    private fun candidate() = OccurrenceCandidate(
        occurrenceKey = "RECURRING_RULE|2|$day|MONTHLY",
        dueDate = day,
        expectedAmount = 100.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Netflix",
        categoryId = null,
        sourceType = "RECURRING_RULE",
        sourceId = 2L
    )

    private fun expense(id: Long) = Expense(
        id = id,
        amount = 100.0,
        currency = "EUR",
        merchant = "Netflix",
        transactionType = TransactionType.PURCHASE,
        date = day,
        createdAt = day,
        source = "test"
    )

    private val resolver = OccurrenceConflictResolver(RecurringInstallmentEligibility(dagger.Lazy { EmptyReader() }))

    @Test
    fun claimedOnlyMatchingExpenseLeavesCandidatePlanned() = runTest(timeout = 60.seconds) {
        val resolved = resolver.resolve(
            listOf(candidate()),
            listOf(expense(1L)),
            alreadyClaimedExpenseIds = setOf(1L)
        )

        assertEquals("PLANNED", resolved.single().status)
        assertNull(resolved.single().linkedExpenseId)
    }

    @Test
    fun defaultEmptyClaimedSetMatchesAsBefore() = runTest(timeout = 60.seconds) {
        val resolved = resolver.resolve(listOf(candidate()), listOf(expense(1L)))

        assertEquals("PAID", resolved.single().status)
        assertEquals(1L, resolved.single().linkedExpenseId)
    }

    @Test
    fun aDifferentExpenseIsNotBlockedByTheClaimedSet() = runTest(timeout = 60.seconds) {
        val resolved = resolver.resolve(
            listOf(candidate()),
            listOf(expense(1L), expense(2L)),
            alreadyClaimedExpenseIds = setOf(1L)
        )

        assertEquals("PAID", resolved.single().status)
        assertEquals(2L, resolved.single().linkedExpenseId)
    }

    @Test
    fun aClaimedIdUnrelatedToTheExpensesDoesNotBlockTheMatch() = runTest(timeout = 60.seconds) {
        val resolved = resolver.resolve(
            listOf(candidate()),
            listOf(expense(1L)),
            alreadyClaimedExpenseIds = setOf(99L)
        )

        assertEquals("PAID", resolved.single().status)
        assertEquals(1L, resolved.single().linkedExpenseId)
    }
}
