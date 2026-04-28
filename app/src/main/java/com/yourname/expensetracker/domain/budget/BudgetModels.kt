package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category

data class BudgetStatus(
    val budget: Budget,
    val category: Category?,
    val spentAmount: Double,
    val remainingAmount: Double,
    val percentUsed: Float,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long,
    val adjustedSpendBreakdown: AdjustedSpendBreakdown? = null, // F11: Shared Expenses Budget Offset
    val currency: String = budget.currency,
    val currencyAssumption: String = budget.currencyAssumption,
    val isPartial: Boolean = false,
    val conversionWarning: String? = null
)

data class AdjustedSpendBreakdown(
    val personalSpend: Double,
    val sharedSpend: Double,
    val reimbursedAmount: Double,
    val netSharedLiability: Double,
    val effectiveSpend: Double,
    val pendingReimbursements: Double
)

enum class BudgetHealthStatus {
    ON_TRACK,   // Spent < warning threshold
    WARNING,    // Spent >= warning threshold
    CRITICAL,   // Spent >= critical threshold
    EXCEEDED    // Spent >= 100%
}

data class BudgetSuggestion(
    val categoryId: Long?,
    val categoryName: String,
    val categoryIcon: String,
    val suggestedAmount: Double,
    val basedOnMonths: Int,
    val reason: String
)
