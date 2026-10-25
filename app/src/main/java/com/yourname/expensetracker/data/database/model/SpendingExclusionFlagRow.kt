package com.yourname.expensetracker.data.database.model

/**
 * Projection of `expenses.id` and `expenses.spendingExclusionReason` for rows that carry a
 * spending-exclusion flag. Read-only; contains no merchant, notes or amount.
 */
data class SpendingExclusionFlagRow(
    val id: Long,
    val spendingExclusionReason: String
)
