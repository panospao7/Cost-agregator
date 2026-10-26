package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * S6c-2b: [RecurringInstallmentEligibility] is the one place the recurring lifecycle decides the
 * installment half of "may this expense be linked to an occurrence" (INV-5). The per-expense form
 * (coordinator gate) and the batch form (occurrence generation) must give identical answers for
 * any expense. Pure JVM: the reader is an in-memory fake that counts its calls.
 */
class RecurringInstallmentEligibilityTest {

    private class FakeReader(
        private val linkedExpenseIds: Set<Long> = emptySet(),
        private val planPurchaseIds: Set<Long> = emptySet()
    ) : InstallmentEntryLinkReader {
        var linkedEntryCalls = 0
        var planPurchaseCalls = 0
        var linkedSetCalls = 0
        var planPurchaseSetCalls = 0

        override suspend fun linkedEntryIdFor(expenseId: Long): Long? {
            linkedEntryCalls++
            return if (expenseId in linkedExpenseIds) 900L + expenseId else null
        }

        override suspend fun isPlanPurchase(expenseId: Long): Boolean {
            planPurchaseCalls++
            return expenseId in planPurchaseIds
        }

        override suspend fun linkedExpenseIds(): Set<Long> {
            linkedSetCalls++
            return linkedExpenseIds
        }

        override suspend fun planPurchaseExpenseIds(): Set<Long> {
            planPurchaseSetCalls++
            return planPurchaseIds
        }
    }

    private fun expense(id: Long, reason: String? = null, type: TransactionType = TransactionType.PURCHASE) = Expense(
        id = id,
        amount = 15.99,
        currency = "EUR",
        merchant = "Netflix",
        transactionType = type,
        date = 1_000L,
        createdAt = 1_000L,
        source = "test",
        spendingExclusionReason = reason
    )

    // id -> shape. 1 normal, 2 installment-linked, 3 plan row (no flag), 4 purchase flag only,
    // 5 reversed flag only, 6 plan row + flag, 7 installment-linked + flag, 8 unrelated flag,
    // 9 non-purchase type that is not otherwise excluded.
    private val expenses = listOf(
        expense(1L),
        expense(2L),
        expense(3L),
        expense(4L, reason = "INSTALLMENT_PURCHASE"),
        expense(5L, reason = "INSTALLMENT_REVERSED"),
        expense(6L, reason = "INSTALLMENT_PURCHASE"),
        expense(7L, reason = "INSTALLMENT_PURCHASE"),
        expense(8L, reason = "SOME_OTHER_REASON"),
        expense(9L, type = TransactionType.WITHDRAWAL)
    )

    private fun reader() = FakeReader(
        linkedExpenseIds = setOf(2L, 7L),
        planPurchaseIds = setOf(3L, 6L)
    )

    private fun eligibility(reader: FakeReader) =
        RecurringInstallmentEligibility(dagger.Lazy { reader })

    @Test
    fun perExpenseFormRefusesEveryInstallmentShapeAndKeepsNormalExpenses() = runTest(timeout = 60.seconds) {
        val subject = eligibility(reader())

        assertTrue("normal expense", subject.mayLinkToRecurringOccurrence(expenses[0]))
        assertFalse("installment-linked", subject.mayLinkToRecurringOccurrence(expenses[1]))
        assertFalse("plan row without the flag", subject.mayLinkToRecurringOccurrence(expenses[2]))
        assertFalse("orphaned purchase flag", subject.mayLinkToRecurringOccurrence(expenses[3]))
        assertFalse("orphaned reversed flag", subject.mayLinkToRecurringOccurrence(expenses[4]))
        assertFalse("plan row plus flag", subject.mayLinkToRecurringOccurrence(expenses[5]))
        assertFalse("linked plus flag", subject.mayLinkToRecurringOccurrence(expenses[6]))
        assertTrue("an unrelated exclusion reason is not an installment relation", subject.mayLinkToRecurringOccurrence(expenses[7]))
        assertTrue("layer 2 does not judge the transaction type", subject.mayLinkToRecurringOccurrence(expenses[8]))
    }

    @Test
    fun batchFormGivesTheSameAnswerAsThePerExpenseFormForEveryExpense() = runTest(timeout = 60.seconds) {
        val subject = eligibility(reader())

        val excluded = subject.excludedExpenseIds(expenses)

        for (expense in expenses) {
            assertEquals(
                "expense ${expense.id}: batch and per-expense forms must agree",
                !subject.mayLinkToRecurringOccurrence(expense),
                expense.id in excluded
            )
        }
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L, 7L), excluded)
    }

    @Test
    fun batchFormReadsEachSetExactlyOnceForAnyNumberOfExpenses() = runTest(timeout = 60.seconds) {
        val reader = reader()
        val subject = eligibility(reader)

        subject.excludedExpenseIds(expenses)

        assertEquals(1, reader.linkedSetCalls)
        assertEquals(1, reader.planPurchaseSetCalls)
        assertEquals("the batch form never reads per expense", 0, reader.linkedEntryCalls)
        assertEquals("the batch form never reads per expense", 0, reader.planPurchaseCalls)
    }

    @Test
    fun batchFormReadsNothingForAnEmptyInput() = runTest(timeout = 60.seconds) {
        val reader = reader()
        val subject = eligibility(reader)

        assertTrue(subject.excludedExpenseIds(emptyList()).isEmpty())

        assertEquals(0, reader.linkedSetCalls)
        assertEquals(0, reader.planPurchaseSetCalls)
    }

    @Test
    fun noPlansMeansNothingIsExcludedExceptFlaggedRows() = runTest(timeout = 60.seconds) {
        val subject = eligibility(FakeReader())

        assertEquals(setOf(4L, 5L, 6L, 7L), subject.excludedExpenseIds(expenses))
    }

    @Test
    fun snapshotFormAgreesWithTheExpenseFormOnEveryStoredRelation() = runTest(timeout = 60.seconds) {
        val reader = reader()
        val subject = eligibility(reader)
        val snapshots = expenses.map { expense ->
            com.yourname.expensetracker.domain.model.ExpenseSnapshot(
                id = expense.id,
                amount = expense.amount,
                effectiveAmount = expense.effectiveAmount,
                currency = expense.currency,
                merchant = expense.merchant,
                merchantKey = expense.merchantKey,
                transactionType = com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE,
                date = expense.date,
                categoryId = null,
                isNotMine = false,
                transferDirection = null,
                notes = null
            )
        }

        val excluded = subject.excludedSnapshotIds(snapshots)

        // The snapshot carries no spending-exclusion flag, so only the two stored relations count:
        // ids 2 and 7 (linked) and 3 and 6 (plan row). Flag-only rows 4 and 5 are the documented gap.
        assertEquals(setOf(2L, 3L, 6L, 7L), excluded)
        for (expense in expenses.filter { it.spendingExclusionReason == null }) {
            assertEquals(
                "expense ${expense.id}: snapshot and expense forms must agree when no flag is set",
                !subject.mayLinkToRecurringOccurrence(expense),
                expense.id in excluded
            )
        }
        assertEquals(1, reader.linkedSetCalls)
        assertEquals(1, reader.planPurchaseSetCalls)
    }

    @Test
    fun snapshotFormReadsEachSetOnceAndNothingForAnEmptyInput() = runTest(timeout = 60.seconds) {
        val reader = reader()
        val subject = eligibility(reader)

        assertTrue(subject.excludedSnapshotIds(emptyList()).isEmpty())
        assertEquals(0, reader.linkedSetCalls)
        assertEquals(0, reader.planPurchaseSetCalls)
    }

    @Test
    fun theReaderIsNotBuiltUntilADecisionIsNeeded() = runTest(timeout = 60.seconds) {
        var built = 0
        val subject = RecurringInstallmentEligibility(dagger.Lazy {
            built++
            FakeReader()
        })

        assertEquals("constructing the helper must not build the reader", 0, built)
        assertTrue(subject.excludedExpenseIds(emptyList()).isEmpty())
        assertEquals("an empty batch needs no reader", 0, built)
        subject.mayLinkToRecurringOccurrence(expenses[0])
        assertTrue(built >= 1)
    }
}
