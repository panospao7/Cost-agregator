package com.yourname.expensetracker.domain.model

data class PlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    val date: Long,
    val categoryId: Long?,
    val isRecurring: Boolean,
    val priority: PlannedExpensePriority
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
