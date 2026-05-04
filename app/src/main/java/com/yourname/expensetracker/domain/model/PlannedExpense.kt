package com.yourname.expensetracker.domain.model

data class PlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    val currency: String = "EUR",
    val date: Long,
    val categoryId: Long?,
    val isRecurring: Boolean,
    val priority: PlannedExpensePriority,
    /** Key linking this planned expense to a recurring occurrence (occurrenceKey). */
    val sourceOccurrenceKey: String? = null
) {
    init {
        require(description.isNotBlank()) { "description cannot be blank" }
        require(amount.isFinite() && amount > 0.0) { "amount must be a positive finite number" }
    }
}

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}
