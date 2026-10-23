package com.yourname.expensetracker.data.database.query

import com.yourname.expensetracker.data.database.dao.ExpenseDao

/**
 * SQL projection of the single spending-inclusion rule (loan-installments INV-1).
 *
 * The rule has exactly two projections that must never diverge:
 *  - Kotlin: `Expense.countsAsSpending` and `Expense.effectiveAmount` (flagged rows add 0.0);
 *  - SQL: the constants below. `SpendingInclusionParityTest` keeps them equal.
 *
 * A row counts as the user's own spending when `isNotMine = 0` and
 * `spendingExclusionReason IS NULL`. A non-null reason (an installment purchase record or
 * the expense of a refunded plan) adds 0 to every spending aggregate.
 *
 * All members are Kotlin `const val` strings, so they are inlined before Room's annotation
 * processor runs and may be interpolated into `@Query` strings and RawQuery builders.
 *
 * Never use these in the duplicate BLOCKING or RESOLVER query families (INV-8): a flagged
 * purchase row must stay a duplicate target.
 */
object SpendingInclusionSql {

    /** WHERE-clause form for queries that reference `expenses` without an alias. */
    const val SPENDING_PREDICATE: String =
        "isNotMine = 0 AND spendingExclusionReason IS NULL"

    /** Same predicate with the `e.` table alias prefix. */
    const val SPENDING_PREDICATE_E: String =
        "e.isNotMine = 0 AND e.spendingExclusionReason IS NULL"

    /**
     * Canonical effective-amount expression for queries that zero rows via CASE instead of
     * filtering: a flagged row yields 0.0, every other row yields exactly the existing
     * [ExpenseDao.EFFECTIVE_AMOUNT_SQL] value (not-mine zeroing, shared share, full amount).
     * Mirrors `Expense.effectiveAmount`.
     */
    const val EFFECTIVE_AMOUNT: String =
        "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 " +
            "ELSE (${ExpenseDao.EFFECTIVE_AMOUNT_SQL}) END"

    /** Same expression with the `e.` table alias prefix. */
    const val EFFECTIVE_AMOUNT_E: String =
        "CASE WHEN e.spendingExclusionReason IS NOT NULL THEN 0.0 " +
            "ELSE (${ExpenseDao.EFFECTIVE_AMOUNT_E_SQL}) END"
}
