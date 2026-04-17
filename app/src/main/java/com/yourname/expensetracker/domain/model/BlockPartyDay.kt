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
) {
    init {
        require(amount.isFinite()) { "amount must be finite" }
        require(effectiveAmount.isFinite()) { "effectiveAmount must be finite" }
        require(merchant.isNotBlank()) { "merchant cannot be blank" }
        require(myShareAmount == null || (myShareAmount.isFinite() && myShareAmount >= 0.0)) {
            "myShareAmount must be a non-negative finite number"
        }
        require(mySharePercentage == null || mySharePercentage in 0..100) {
            "mySharePercentage must be between 0 and 100"
        }
    }
}

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
) {
    init {
        require(dayOfMonth in 1..31) { "dayOfMonth must be between 1 and 31" }
        require(actualSpent.isFinite()) { "actualSpent must be finite" }
        require(targetBudget.isFinite() && targetBudget >= 0.0) { "targetBudget must be a non-negative finite number" }
        require(baseTarget.isFinite() && baseTarget >= 0.0) { "baseTarget must be a non-negative finite number" }
        require(recurringImpact.isFinite()) { "recurringImpact must be finite" }
        require(plannedImpact.isFinite()) { "plannedImpact must be finite" }
        require(recurringItems.none { it.isBlank() }) { "recurringItems cannot contain blank entries" }
        require(plannedItems.none { it.isBlank() }) { "plannedItems cannot contain blank entries" }
    }
}

enum class BlockPartyStatus {
    UNDER_BUDGET,
    OVER_BUDGET,
    FUTURE,
    TODAY,
    BILL_DAY,
    NO_DATA
}
