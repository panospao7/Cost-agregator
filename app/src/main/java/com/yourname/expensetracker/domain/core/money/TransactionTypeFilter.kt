package com.yourname.expensetracker.domain.core.money

/**
 * Filter for which transaction types to include in aggregation.
 */
enum class TransactionTypeFilter {
    PURCHASE_ONLY,
    INCOME_ONLY,
    TRANSFER_ONLY,
    ALL_EXCEPT_TRANSFERS,
    ALL_TYPES
}
