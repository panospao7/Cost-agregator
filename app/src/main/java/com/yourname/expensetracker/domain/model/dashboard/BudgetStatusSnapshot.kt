package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.budget.BudgetHealthStatus

data class BudgetStatusSnapshot(
    val budgetCategoryId: Long?,
    val budgetAmount: Double,
    val categoryName: String?,
    val spentAmount: Double,
    val remainingAmount: Double,
    val percentUsed: Double,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long
)
