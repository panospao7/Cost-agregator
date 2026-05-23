package com.yourname.expensetracker.data.database.dao

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Direct ExpenseDao mutation is restricted. Route through TransactionLifecycleCoordinator or add an explicitly reviewed, write-barrier-protected allowlist exception."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RestrictedExpenseDaoMutation
