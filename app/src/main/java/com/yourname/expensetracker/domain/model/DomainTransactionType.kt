package com.yourname.expensetracker.domain.model

/**
 * Domain-owned transaction type enum.
 *
 * Mirrors the stable value names of [com.yourname.expensetracker.data.database.entity.TransactionType]
 * so that domain/AI model classes never need to import Room entity types.
 *
 * Mapping between this enum and the data-layer enum should happen at the repository / adapter boundary.
 */
enum class DomainTransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN;

    /**
     * Single source-of-truth for "spending" semantics at the domain layer.
     *
     * A transaction counts as user spending if and only if it is a [PURCHASE].
     * All in-memory spend-facing filters (analytics, budgets, forecasting,
     * cash-flow) must use this property instead of ad-hoc `== PURCHASE` checks
     * so that the spending definition can be widened in one place if needed.
     *
     * The SQL-side equivalent lives in [com.yourname.expensetracker.data.database.dao.ExpenseDao.SPENDING_TYPE_SQL].
     */
    val isSpending: Boolean
        get() = this == PURCHASE
}
