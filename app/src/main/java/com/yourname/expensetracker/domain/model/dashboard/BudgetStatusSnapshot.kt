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
    val periodEnd: Long,
    /** P5-NEW-06: true when the budget limit and/or spend could not be fully converted. */
    val isPartial: Boolean = false,
    /** P5-NEW-06: human-readable conversion warning when [isPartial], else null. */
    val conversionWarning: String? = null
)
