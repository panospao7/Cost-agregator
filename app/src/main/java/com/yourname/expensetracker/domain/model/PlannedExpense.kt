package com.yourname.expensetracker.domain.model

data class PlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    val date: Long,
    val categoryId: Long?,
    val isRecurring: Boolean,
    val priority: PlannedExpensePriority
)

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}
