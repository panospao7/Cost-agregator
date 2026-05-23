package com.yourname.expensetracker.domain.transaction.validation

data class TransactionValidationError(
    val code: String,
    val message: String,
    val field: String? = null
)

class TransactionValidationException(
    val errors: List<TransactionValidationError>
) : IllegalArgumentException(
    errors.joinToString("; ") { it.message }
)
