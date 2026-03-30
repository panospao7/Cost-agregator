package com.yourname.expensetracker.domain.model

data class CategoryBreakdown(
    val category: CategoryInfo,
    val totalAmount: Double,
    val transactionCount: Int,
    val percentageOfTotal: Float,
    val periodLabel: String
)
