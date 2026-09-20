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
 * - [currency]: source currency of [effectiveAmount]; blank means unknown
 *   (legacy callers) — consumers must treat blank as identity, never invent
 *   a currency. Metadata-only for display; block-party arithmetic on foreign
 *   currencies converts via the engine's typed converter (RP-06 6b slice 3).
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
    val mySharePercentage: Int? = null,
    /**
     * RP-06 6b slice 3 (P5-CURRENT-009): source currency of [effectiveAmount].
     * Blank means unknown/legacy — callers that populate it enable the engine's
     * typed-converter arithmetic path; blank keeps legacy identity behavior.
     */
    val currency: String = ""
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

/**
 * Domain DTO for one block-party day.
 *
 * @property conversionFailureCount RP-06 D6 (6b deferral resolution): number of
 *   items on THIS day excluded because currency conversion failed (bounded,
 *   count-only — no amounts, no currencies, no exception text). 0 when the day
 *   took the identity path or all conversions succeeded. Month-level conversion
 *   failures (monthly recurring/planned totals) remain engine-log-only; this
 *   field carries day-scoped counts.
 */
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
    val topTransactions: List<TransactionSummary>,
    val conversionFailureCount: Int = 0
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
