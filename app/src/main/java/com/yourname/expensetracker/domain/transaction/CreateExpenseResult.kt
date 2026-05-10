package com.yourname.expensetracker.domain.transaction

sealed class CreateExpenseResult {
    data class Created(val expenseId: Long) : CreateExpenseResult()
    data class DuplicateSkipped(
        val existingExpenseId: Long,
        val reason: String,
        val eventLogged: Boolean = false
    ) : CreateExpenseResult()
    data class ValidationFailed(val errors: List<String>) : CreateExpenseResult()
    data class InsertConflict(val dedupeKey: String) : CreateExpenseResult()
    data class Error(val exception: Throwable) : CreateExpenseResult()
}
