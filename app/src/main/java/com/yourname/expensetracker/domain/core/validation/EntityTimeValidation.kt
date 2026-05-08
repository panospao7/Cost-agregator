package com.yourname.expensetracker.domain.core.validation

object EntityTimeValidation {
    const val UNSET_SENTINEL = 0L

    fun requireValidCreatedAt(timestamp: Long, entityName: String) {
        require(timestamp > UNSET_SENTINEL) {
            "$entityName.createdAt must be > 0 (was $timestamp). Use timeProvider.now()."
        }
    }
}
