package com.yourname.expensetracker.domain.model.dashboard

data class DashboardExpense(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val merchant: String,
    val transactionType: DashboardTransactionType,
    val date: Long,
    val categoryId: Long?,
    val isNotMine: Boolean,
    val isManualEntry: Boolean
)

enum class DashboardTransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

data class DashboardCategory(
    val id: Long,
    val name: String,
    val icon: String,
    val color: String
)
