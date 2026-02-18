package com.yourname.expensetracker.domain.model

import com.yourname.expensetracker.data.database.entity.Expense

data class BlockPartyDay(
    val dayOfMonth: Int,
    val date: Long,
    val actualSpent: Double,
    val targetBudget: Double,
    val isToday: Boolean,
    val status: BlockPartyStatus,
    val baseTarget: Double,
    val recurringImpact: Double,
    val plannedImpact: Double,
    val recurringItems: List<String>,
    val plannedItems: List<String>,
    val topTransactions: List<Expense>
)

enum class BlockPartyStatus {
    UNDER_BUDGET,
    OVER_BUDGET,
    FUTURE,
    TODAY,
    BILL_DAY,
    NO_DATA
}
