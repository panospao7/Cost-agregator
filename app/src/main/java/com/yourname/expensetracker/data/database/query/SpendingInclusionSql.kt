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
     * Canonical (OUTER, netted) effective-amount expression for queries that zero rows via CASE
     * instead of filtering: a flagged row yields 0.0; a row with no refund
     * (`refundNettedAmount <= 0.0`) yields exactly the existing [ExpenseDao.EFFECTIVE_AMOUNT_SQL]
     * value (not-mine zeroing, shared share, full amount); a refund-netted row reduces the gross
     * amount first and takes the user's share from the net (refund-netting INV-6), never below 0.
     * Mirrors `Expense.effectiveAmount` branch for branch, in the same arithmetic order. Every SQL
     * total, average, min or max must use this constant, never the inner `EFFECTIVE_AMOUNT_SQL`
     * (refund-netting INV-9; guard G-SPENDING-PREDICATE-05).
     * Built with `+` (not an in-literal template) so the db_access
     * room_inventory resolver can expand the cross-file constant (it does not expand templates inside a
     * const literal); the compiled value is identical.
     */
    const val EFFECTIVE_AMOUNT: String =
        "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 " +
            "WHEN refundNettedAmount <= 0.0 THEN (" + ExpenseDao.EFFECTIVE_AMOUNT_SQL + ") " +
            "WHEN isNotMine = 1 THEN 0.0 " +
            "WHEN refundNettedAmount >= amount THEN 0.0 " +
            "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL " +
            "THEN myShareAmount * (amount - refundNettedAmount) / amount " +
            "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL " +
            "THEN (amount - refundNettedAmount) * mySharePercentage / 100.0 " +
            "ELSE amount - refundNettedAmount END"

    /**
     * FROZEN text of [EFFECTIVE_AMOUNT] as it was before refund netting (schema 152 and below). It
     * exists only so `SemanticAggregates` can recognise the definition hash written into backup
     * manifests by earlier builds; it equals [EFFECTIVE_AMOUNT] on every row with
     * `refundNettedAmount = 0.0`, which is every row of a pre-153 database. Never use it in a query.
     */
    const val EFFECTIVE_AMOUNT_PRE_REFUND_NETTING: String =
        "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 " +
            "ELSE (" + ExpenseDao.EFFECTIVE_AMOUNT_SQL + ") END"

    /** Same expression with the `e.` table alias prefix. */
    const val EFFECTIVE_AMOUNT_E: String =
        "CASE WHEN e.spendingExclusionReason IS NOT NULL THEN 0.0 " +
            "WHEN e.refundNettedAmount <= 0.0 THEN (" + ExpenseDao.EFFECTIVE_AMOUNT_E_SQL + ") " +
            "WHEN e.isNotMine = 1 THEN 0.0 " +
            "WHEN e.refundNettedAmount >= e.amount THEN 0.0 " +
            "WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL " +
            "THEN e.myShareAmount * (e.amount - e.refundNettedAmount) / e.amount " +
            "WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL " +
            "THEN (e.amount - e.refundNettedAmount) * e.mySharePercentage / 100.0 " +
            "ELSE e.amount - e.refundNettedAmount END"
}
