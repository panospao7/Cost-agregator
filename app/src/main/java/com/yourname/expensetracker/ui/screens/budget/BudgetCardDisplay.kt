package com.yourname.expensetracker.ui.screens.budget

import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus

/**
 * Which text the remaining / over-budget slot of a budget card shows.
 */
internal enum class BudgetCardRemainingSlot {
    /** Nothing: the limit could not be converted, so remaining is a placeholder (RP-09). */
    HIDDEN,

    /** "Remaining unavailable": effective spend is unavailable (INV-17). */
    UNAVAILABLE,
    OVER_BUDGET,
    REMAINING
}

/**
 * INV-17: pure mapping from a [BudgetStatus] to what a budget card may show.
 *
 * `adjustedSpendBreakdown == null` is the single "effective spend unavailable" signal
 * (INV-16). In that case this mapping carries no spend, remaining, over-budget amount,
 * percent or health value at all, so the card can neither show gross
 * [BudgetStatus.spentAmount] nor a percent/health derived from it. The repository still
 * computes percent and health unchanged (R3); they are simply not displayed here.
 */
internal data class BudgetCardDisplay(
    val effectiveSpendAvailable: Boolean,
    /** Effective (accrual) spend; null iff unavailable. Never `status.spentAmount`. */
    val spend: Double?,
    /** `effectiveLimit - spend`; null iff unavailable. */
    val remaining: Double?,
    /** `spend - effectiveLimit` when over budget and the percent is known; else null. */
    val overBudgetAmount: Double?,
    /** Repository percent; null iff unavailable. */
    val percentUsed: Float?,
    /** Repository health; null iff unavailable. */
    val healthStatus: BudgetHealthStatus?,
    val remainingSlot: BudgetCardRemainingSlot
) {
    companion object {
        fun from(status: BudgetStatus): BudgetCardDisplay {
            val breakdown = status.adjustedSpendBreakdown
                ?: return BudgetCardDisplay(
                    effectiveSpendAvailable = false,
                    spend = null,
                    remaining = null,
                    overBudgetAmount = null,
                    percentUsed = null,
                    healthStatus = null,
                    remainingSlot = BudgetCardRemainingSlot.UNAVAILABLE
                )
            val spend = breakdown.effectiveSpend
            val over = status.percentUsed > 1f
            return BudgetCardDisplay(
                effectiveSpendAvailable = true,
                spend = spend,
                remaining = status.effectiveLimit - spend,
                overBudgetAmount = if (status.percentKnown && over) spend - status.effectiveLimit else null,
                percentUsed = status.percentUsed,
                healthStatus = status.healthStatus,
                remainingSlot = when {
                    !status.percentKnown -> BudgetCardRemainingSlot.HIDDEN
                    over -> BudgetCardRemainingSlot.OVER_BUDGET
                    else -> BudgetCardRemainingSlot.REMAINING
                }
            )
        }
    }
}
