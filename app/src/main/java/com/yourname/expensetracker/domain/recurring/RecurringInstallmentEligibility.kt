package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentLinkRelations
import com.yourname.expensetracker.domain.schedule.ExpenseScheduleLinkPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE place where the recurring lifecycle decides the installment half of "may this expense be
 * linked to a recurring occurrence" (INV-5 schedule exclusivity, domain spec row (d)). Every
 * recurring path that can link an expense goes through it:
 *
 * - the coordinator's link gate (auto-link, manual link, post-update reconcile) uses the
 *   per-expense form [mayLinkToRecurringOccurrence];
 * - the occurrence generation path (create / activate / top-up / update through
 *   [OccurrenceConflictResolver], and the in-memory projection) uses the batch form
 *   [excludedExpenseIds], which reads the installment link sets once per call.
 *
 * Both forms share the private [decide], so they cannot disagree. The decision is made by
 * [ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence] over relations built by
 * [InstallmentLinkRelations.forExistingExpense] (never re-derived from the exclusion flag here):
 * an expense that pays an installment entry (any entry status), or is a plan purchase record
 * ("plan row OR INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED flag"), is refused. An existing recurring
 * link of the expense is not a refusal reason (the policy ignores it for this target), so it is not
 * read here.
 *
 * Recurring's own layer (not-mine, TRANSFER, DEPOSIT) stays with its callers.
 *
 * The reader is a lazy cross-module dependency: constructing this class never builds it. Reads run
 * inside the caller's Room transaction when one is open. The class performs no writes.
 */
@Singleton
class RecurringInstallmentEligibility @Inject constructor(
    private val installmentEntryLinkReader: dagger.Lazy<InstallmentEntryLinkReader>
) {

    /** Per-expense form: true when the installment policy lets [expense] be linked to an occurrence. */
    suspend fun mayLinkToRecurringOccurrence(expense: Expense): Boolean {
        val linkReader = installmentEntryLinkReader.get()
        val linkedInstallmentEntryId = linkReader.linkedEntryIdFor(expense.id)
        val isPlanPurchaseRow = linkReader.isPlanPurchase(expense.id)
        return decide(expense, linkedInstallmentEntryId, isPlanPurchaseRow)
    }

    /**
     * Batch form: the ids among [expenses] that the installment policy refuses, for any expense the
     * per-expense form refuses. The two link sets are read once per call (never per expense), and
     * nothing is read when [expenses] is empty. The flag-based case comes from the rows in hand,
     * through the same [decide].
     */
    suspend fun excludedExpenseIds(expenses: Collection<Expense>): Set<Long> {
        if (expenses.isEmpty()) return emptySet()
        val linkReader = installmentEntryLinkReader.get()
        val linkedExpenseIds = linkReader.linkedExpenseIds()
        val planPurchaseExpenseIds = linkReader.planPurchaseExpenseIds()
        val excluded = HashSet<Long>()
        for (expense in expenses) {
            val linkedMarker = if (expense.id in linkedExpenseIds) BATCH_LINKED_ENTRY_MARKER else null
            if (!decide(expense, linkedMarker, expense.id in planPurchaseExpenseIds)) {
                excluded.add(expense.id)
            }
        }
        return excluded
    }

    /**
     * The single decision. Only the presence of [linkedInstallmentEntryId] matters to the policy for
     * the RECURRING_OCCURRENCE target, so the batch form passes a marker instead of a real entry id.
     */
    private fun decide(
        expense: Expense,
        linkedInstallmentEntryId: Long?,
        planPurchaseRowExists: Boolean
    ): Boolean {
        val facts = InstallmentExpenseFacts(
            expenseId = expense.id,
            currency = expense.currency,
            dateMillis = expense.date,
            isSpendingType = InstallmentExpenseFacts.isSpendingType(expense.transactionType),
            isNotMine = expense.isNotMine,
            isSharedExpense = expense.isSharedExpense,
            hasSplit = InstallmentExpenseFacts.hasSplit(expense.splitTemplateId, expense.splitVisualization),
            spendingExclusionReason = expense.spendingExclusionReason,
            categoryId = expense.categoryId,
            paymentMethod = expense.paymentMethod,
            linkedRecurringOccurrenceId = null
        )
        val relations = InstallmentLinkRelations.forExistingExpense(
            linkedInstallmentEntryId = linkedInstallmentEntryId,
            planPurchaseRecordExists = planPurchaseRowExists,
            facts = facts
        )
        return ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence(ELIGIBILITY_TARGET_ID, relations)
    }

    private companion object {
        /**
         * Occurrence id handed to [ExpenseScheduleLinkPolicy] when no target occurrence is chosen
         * yet. The RECURRING_OCCURRENCE exclusivity rule does not depend on it (0 is never a stored id).
         */
        const val ELIGIBILITY_TARGET_ID = 0L

        /** Stand-in "some entry links this expense" id for the batch form; its value is never used. */
        const val BATCH_LINKED_ENTRY_MARKER = -1L
    }
}
