package com.yourname.expensetracker.domain.model

data class BudgetSnapshot(
    val categoryId: Long?,
    val amount: Double,
    val currency: String
)
