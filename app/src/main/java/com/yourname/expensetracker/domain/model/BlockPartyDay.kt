package com.yourname.expensetracker.domain.model

/**
 * Lightweight domain DTO representing a transaction summary for block-party day previews.
 * Replaces the former direct dependency on the Room [data.database.entity.Expense] entity.
 *
 * Contains only the fields needed for display and downstream domain calculations:
 * - [amount]: the raw transaction amount (used for sorting/ranking)
 * - [effectiveAmount]: the user's share after shared-expense adjustments
 * - [merchant]: display name / description
 * - [date]: transaction timestamp
 * - [categoryId]: optional category reference for grouping
 * - [isSharedExpense]: whether this transaction is shared with others
 * - [myShareAmount]: explicit per-person amount if set
 * - [mySharePercentage]: proportional share percentage if set
 */
data class TransactionSummary(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val merchant: String,
    val date: Long,
    val categoryId: Long?,
    val isSharedExpense: Boolean = false,
    val myShareAmount: Double? = null,
    val mySharePercentage: Int? = null
)

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
    val topTransactions: List<TransactionSummary>
)

enum class BlockPartyStatus {
    UNDER_BUDGET,
    OVER_BUDGET,
    FUTURE,
    TODAY,
    BILL_DAY,
    NO_DATA
}
