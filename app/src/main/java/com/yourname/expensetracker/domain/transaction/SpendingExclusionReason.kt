package com.yourname.expensetracker.domain.transaction

/**
 * Bounded reason code stored in `expenses.spendingExclusionReason` (nullable TEXT,
 * stored as [name], same convention as [ExpenseSource]). A null column counts as
 * spending exactly as before.
 *
 * This is a denormalised flag: the installment plan and entry rows are the source
 * of truth (loan-installments INV-2). The flag exists so SQL aggregates and Kotlin
 * readers can exclude rows without joins.
 */
enum class SpendingExclusionReason {
    /** Purchase record of an installment plan; counts 0 (the installments count instead). */
    INSTALLMENT_PURCHASE,

    /** Installment expense of a REFUNDED plan; counts 0. */
    INSTALLMENT_REVERSED
}
