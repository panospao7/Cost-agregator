package com.yourname.expensetracker.ui.screens.budget

import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.effectiveBudgetHealth
import com.yourname.expensetracker.domain.budget.isEffectivelyOverBudget

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
 * [BudgetStatus.spentAmount] nor a percent/health derived from it. When effective spend is
 * available the card's percent and health are recomputed from it (J-08-1) so they agree with
 * the remaining / over-budget text; the repository's own gross percent and health stay
 * unchanged (R3).
 */
internal data class BudgetCardDisplay(
    val effectiveSpendAvailable: Boolean,
    /** Effective (accrual) spend; null iff unavailable. Never `status.spentAmount`. */
    val spend: Double?,
    /** `effectiveLimit - spend`; null iff unavailable. */
    val remaining: Double?,
    /** `spend - effectiveLimit` when over budget and the percent is known; else null. */
    val overBudgetAmount: Double?,
    /**
     * Effective spend / effective limit when the percent is known and the limit is positive;
     * 1f when the limit is not positive and spend is over it; otherwise the repository's value.
     * Null iff effective spend is unavailable.
     */
    val percentUsed: Float?,
    /**
     * Health for [percentUsed] via the shared threshold rule and the budget's own alert
     * thresholds when the percent is effective-based; otherwise the repository's value.
     * Null iff effective spend is unavailable.
     */
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
            // J-08-1: the over-budget STATE and its AMOUNT both come from effective spend. The
            // repository percent is gross-based, so gross 120 / effective 90 / limit 100 must
            // not read "over budget" next to a negative amount. Over means at least half a
            // minor unit above the limit, so the amount never rounds to "0.00 over".
            // F6: a zero or negative (rollover deficit) limit with spend above it is over budget too.
            val over = isEffectivelyOverBudget(spend, status.effectiveLimit, status.percentKnown)
            // The bar and colour must agree with the text slot, so when the percent is known
            // and the limit positive they come from effective spend over the effective limit,
            // using the one shared threshold rule and this budget's own alert thresholds.
            // Otherwise (percent unknown, or no positive limit) the repository values stay.
            val effectivePercent = if (status.percentKnown && status.effectiveLimit > 0.0) {
                (spend / status.effectiveLimit).toFloat()
            } else {
                null
            }
            return BudgetCardDisplay(
                effectiveSpendAvailable = true,
                spend = spend,
                remaining = status.effectiveLimit - spend,
                overBudgetAmount = if (over) spend - status.effectiveLimit else null,
                // A zero or negative limit with spend over it (F6) has no ratio: the bar is full (1f)
                // so it agrees with the "over" text and the EXCEEDED health, not the 0f placeholder.
                percentUsed = effectivePercent ?: if (over) 1f else status.percentUsed,
                // Same derivation the Home path uses (domain effectiveBudgetHealth).
                healthStatus = effectiveBudgetHealth(
                    effectiveSpend = spend,
                    effectiveLimit = status.effectiveLimit,
                    percentKnown = status.percentKnown,
                    notifyAtWarning = status.budget.notifyAtWarning,
                    notifyAtCritical = status.budget.notifyAtCritical,
                    fallback = status.healthStatus
                ),
                remainingSlot = when {
                    !status.percentKnown -> BudgetCardRemainingSlot.HIDDEN
                    over -> BudgetCardRemainingSlot.OVER_BUDGET
                    else -> BudgetCardRemainingSlot.REMAINING
                }
            )
        }
    }
}

internal data class BudgetSummaryCounts(
    val onTrack: Int,
    val warning: Int,
    val exceeded: Int,
    val partial: Int
)

/** Summary counts from the same effective health each card shows; unavailable budgets count in no health bucket. */
internal fun budgetSummaryCounts(budgets: List<BudgetStatus>): BudgetSummaryCounts {
    val shown = budgets.map { it to BudgetCardDisplay.from(it).healthStatus }
    return BudgetSummaryCounts(
        // S8-011: partial budgets are not counted as confidently on-track.
        onTrack = shown.count { (budget, health) -> health == BudgetHealthStatus.ON_TRACK && !budget.isPartial },
        warning = shown.count { (_, health) -> health == BudgetHealthStatus.WARNING || health == BudgetHealthStatus.CRITICAL },
        exceeded = shown.count { (_, health) -> health == BudgetHealthStatus.EXCEEDED },
        partial = budgets.count { it.isPartial }
    )
}
