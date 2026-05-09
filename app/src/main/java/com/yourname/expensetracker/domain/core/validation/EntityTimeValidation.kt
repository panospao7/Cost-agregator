package com.yourname.expensetracker.domain.core.validation

// M13 PARTIAL: Entity timestamps are raw Long. EntityTimeValidation helper exists
// but is not consistently enforced across all entities. Add require(createdAt > 0)
// or require(updatedAt >= createdAt) guards in entity init blocks.

object EntityTimeValidation {
    const val UNSET_SENTINEL = 0L

    fun requireValidCreatedAt(timestamp: Long, entityName: String) {
        require(timestamp > UNSET_SENTINEL) {
            "$entityName.createdAt must be > 0 (was $timestamp). Use timeProvider.now()."
        }
    }
}
