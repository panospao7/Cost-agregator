package com.yourname.expensetracker.domain.installment.lifecycle

/**
 * Read-only port through which other lifecycles ask whether an expense is already the payment of
 * an installment entry (INV-5 schedule exclusivity, INV-4 one entry per expense).
 *
 * The recurring lifecycle consults it before an auto-link or a manual link of an expense to a
 * recurring occurrence. It has no write operation and exposes no entry data: only the id of the
 * linking entry, so a caller can never reach plan rows through it.
 *
 * Links of any entry status (PAID and REVERSED included) count, so the implementation applies no
 * status filter. The implementation lives in the data layer; a read joins the caller's Room
 * transaction when one is open.
 */
interface InstallmentEntryLinkReader {

    /** Id of the installment entry whose `linkedExpenseId` is [expenseId], or null when none. */
    suspend fun linkedEntryIdFor(expenseId: Long): Long?

    /**
     * True when a plan row has [expenseId] as its purchase record (`purchaseExpenseId`), whether or
     * not the expense still carries the plan-purchase spending-exclusion flag. Plan status does not
     * matter.
     */
    suspend fun isPlanPurchase(expenseId: Long): Boolean

    /**
     * Batch form of [linkedEntryIdFor] (S6c-2b): the ids of every expense that is linked by an
     * installment entry of any status, read in one query. Bounded by the number of entries.
     */
    suspend fun linkedExpenseIds(): Set<Long>

    /**
     * Batch form of [isPlanPurchase] (S6c-2b): the ids of every expense that is the purchase record
     * of a plan row of any status, read in one query. The spending-exclusion flag is not consulted
     * here; the caller adds it from the expense rows it already holds.
     */
    suspend fun planPurchaseExpenseIds(): Set<Long>
}
