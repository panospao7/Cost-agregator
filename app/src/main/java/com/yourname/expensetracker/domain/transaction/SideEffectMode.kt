package com.yourname.expensetracker.domain.transaction

/**
 * Controls when post-commit side effects run during expense creation.
 *
 * When a caller wraps [TransactionLifecycleCoordinator.createExpense] inside
 * its own `database.withTransaction { … }`, the coordinator's "post-commit"
 * side effects actually execute while the outer transaction is still active.
 * If the outer transaction later rolls back, side effects will have fired for
 * data that no longer exists (budget alerts for rolled-back expenses, merchant
 * learning from phantom rows, etc.).
 *
 * Use [DEFER] whenever `createExpense` is called inside a caller-managed
 * transaction, then call [TransactionLifecycleCoordinator.dispatchPostCreationSideEffects]
 * after the outer transaction commits.
 */
enum class SideEffectMode {
    /** Side effects run immediately after the coordinator's inner transaction. */
    IMMEDIATE,

    /**
     * Side effects are skipped; the caller is responsible for calling
     * [TransactionLifecycleCoordinator.dispatchPostCreationSideEffects] after
     * the enclosing transaction commits.
     */
    DEFER
}
