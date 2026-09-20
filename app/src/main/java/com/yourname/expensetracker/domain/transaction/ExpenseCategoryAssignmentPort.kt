package com.yourname.expensetracker.domain.transaction

/**
 * Lifecycle-aware port for assigning expense categories.
 *
 * Replaces direct `expenseDao.updateCategory()` calls from receipt lifecycle
 * code so that category assignment writes proper transaction events,
 * respects the write barrier, and is auditable.
 *
 * P3-BLOCKER-09 / P2-18: Prevents bypassing transaction lifecycle.
 *
 * RP-11 FIX 4: the two operations are deliberately split. Room transactions
 * are RE-ENTRANT — a side-effect dispatch made from inside
 * [assignCategoryIfUnset] would join the CALLER's outer transaction, so the
 * budget recheck / anomaly alert would read uncommitted data, run even if the
 * outer transaction rolled back (phantom side effects), and extend the outer
 * transaction's lock window. The assignment + `EXPENSE_CATEGORY_ASSIGNED`
 * event stay atomic in-transaction; the side-effect dispatch is a separate
 * call that the transactional caller makes only AFTER its transaction has
 * committed (returned successfully).
 */
interface ExpenseCategoryAssignmentPort {
    suspend fun assignCategoryIfUnset(
        expenseId: Long,
        categoryId: Long,
        source: String,
        correlationId: String? = null
    ): CategoryAssignmentOutcome

    /**
     * RP-11 FIX 4: post-commit side-effect dispatch for a category assignment
     * that has already committed. MUST be invoked only after the transaction
     * that committed the assignment returned successfully — never from inside
     * a Room transaction. Only a real [CategoryAssignmentOutcome.Assigned]
     * justifies a dispatch; Skipped/Failed outcomes dispatch nothing.
     */
    suspend fun dispatchAssignedCategorySideEffects(
        expenseId: Long,
        source: String,
        correlationId: String? = null
    )
}

sealed interface CategoryAssignmentOutcome {
    data object Assigned : CategoryAssignmentOutcome
    data object SkippedAlreadySet : CategoryAssignmentOutcome
    data object SkippedExpenseMissing : CategoryAssignmentOutcome
    data class Failed(val reason: String) : CategoryAssignmentOutcome
}
