package com.yourname.expensetracker.domain.transaction

sealed interface BusinessExpenseUpdateResult {
    data class Updated(
        val expenseId: Long,
        val changedFields: Set<String>
    ) : BusinessExpenseUpdateResult

    data object NoChange : BusinessExpenseUpdateResult

    data object NotFound : BusinessExpenseUpdateResult

    data class UnsupportedFields(
        val fields: List<String>
    ) : BusinessExpenseUpdateResult

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : BusinessExpenseUpdateResult
}
