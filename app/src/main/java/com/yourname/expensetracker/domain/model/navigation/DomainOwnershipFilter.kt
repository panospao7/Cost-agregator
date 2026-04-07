package com.yourname.expensetracker.domain.model.navigation

/**
 * Domain-owned ownership filter enum.
 *
 * Mirrors the stable value names of [com.yourname.expensetracker.data.repository.OwnershipFilter]
 * so that domain model classes never need to import data-layer types.
 *
 * Mapping between this enum and the data-layer enum should happen at the repository / adapter boundary.
 */
enum class DomainOwnershipFilter {
    ALL,
    MINE,
    NOT_MINE,
    SHARED,
    TRANSFER
}
