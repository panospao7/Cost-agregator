package com.yourname.expensetracker.domain.transaction.lifecycle

/**
 * Outcome of [TransactionLifecycleCoordinator.setSpendingExclusionDbOnly].
 *
 * Carries no free text, amounts or merchant data; every variant is a bounded value.
 */
sealed interface SpendingInclusionResult {
    /** The flag changed; an UPDATED lifecycle event was written in the same transaction. */
    data class Updated(val expenseId: Long) : SpendingInclusionResult

    /** The expense already had the requested flag value; nothing was written. */
    data object NoOp : SpendingInclusionResult

    /** The expense does not exist; nothing was written. */
    data object NotFound : SpendingInclusionResult

    /**
     * A flag was requested on an expense that carries a refund netting (`refundNettedAmount > 0`);
     * nothing was written (D-19: installments and refund links are mutually exclusive). Clearing a
     * flag is never refused.
     */
    data object RefundLinked : SpendingInclusionResult
}
