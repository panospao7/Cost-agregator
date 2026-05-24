package com.yourname.expensetracker.data.database.dao

/**
 * Opt-in required for direct ExpenseDao mutation methods.
 *
 * WARNING level is intentionally used instead of ERROR because Room's KSP-generated
 * ExpenseDao_Impl overrides all annotated interface methods and cannot carry @OptIn.
 * The architecture test (ExpenseDaoMutationAccessTest) provides the hard enforcement
 * at CI time instead.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Direct ExpenseDao mutation is restricted. Route through TransactionLifecycleCoordinator or add a reviewed write-barrier-protected allowlist exception."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RestrictedExpenseDaoMutation
