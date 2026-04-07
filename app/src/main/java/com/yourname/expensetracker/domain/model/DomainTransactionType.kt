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
    UNKNOWN
}
