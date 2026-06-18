package com.yourname.expensetracker.domain.model.dashboard

/**
 * Lightweight domain DTO representing a top transaction for budget status.
 * Avoids direct dependency on data layer Expense entity.
 */
data class DomainExpenseSummary(
    val id: Long,
    val amount: Double,
    val description: String,
    val categoryName: String?,
    val date: Long
)

data class DomainDayBudgetStatus(
    val dayOfMonth: Int,
    val date: Long,
    val actualSpent: Double,
    val targetBudget: Double,
    val isToday: Boolean,
    val status: DomainBlockStatus,
    val baseTarget: Double = 0.0,
    val recurringImpact: Double = 0.0,
    val plannedImpact: Double = 0.0,
    val recurringItems: List<String> = emptyList(),
    val plannedItems: List<String> = emptyList(),
    val topTransactions: List<DomainExpenseSummary> = emptyList()
)
