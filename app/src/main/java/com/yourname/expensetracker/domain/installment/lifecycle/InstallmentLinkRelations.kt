package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.domain.schedule.ExpenseScheduleRelations
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason

/**
 * The single definition of the [ExpenseScheduleRelations] of an EXISTING expense that is a
 * candidate for an installment link (domain spec 7.0, INV-5, INV-16). Pure: the caller does the
 * reads and passes the facts in, so the coordinator (link) and the read repository (INV-13
 * suggestion eligibility) can never disagree.
 *
 * Links of any entry status (PAID and REVERSED included) count for exclusivity, so the caller
 * passes the entry found by `getEntryByLinkedExpenseId` without a status filter. An expense that
 * carries the INSTALLMENT_PURCHASE flag is treated as a plan purchase record even when no plan row
 * references it (an orphaned flag must not be linkable as a payment). An expense that carries the
 * INSTALLMENT_REVERSED flag is likewise not linkable: it is the refunded payment of a REFUNDED plan
 * (or an orphaned reversed flag awaiting reconcile) and never fulfils another entry. Both flags
 * therefore map to the plan-purchase-record relation, which the link policy refuses as linked
 * elsewhere.
 *
 * The brand new purchase case of `declareWithNewExpense` has no stored row yet and is built
 * separately in the coordinator; `declareOnExpense` follows the declaration rule and is not
 * covered here either.
 */
object InstallmentLinkRelations {

    /**
     * @param linkedInstallmentEntryId id of the entry whose `linkedExpenseId` is this expense, or null.
     * @param planPurchaseRecordExists true when a plan row has this expense as its purchase.
     * @param facts the stored facts of the expense (recurring link and flag are read from here).
     */
    fun forExistingExpense(
        linkedInstallmentEntryId: Long?,
        planPurchaseRecordExists: Boolean,
        facts: InstallmentExpenseFacts
    ): ExpenseScheduleRelations = ExpenseScheduleRelations(
        linkedInstallmentEntryId = linkedInstallmentEntryId,
        linkedRecurringOccurrenceId = facts.linkedRecurringOccurrenceId,
        isPlanPurchaseRecord = planPurchaseRecordExists ||
            facts.spendingExclusionReason == SpendingExclusionReason.INSTALLMENT_PURCHASE.name ||
            facts.spendingExclusionReason == SpendingExclusionReason.INSTALLMENT_REVERSED.name
    )
}
