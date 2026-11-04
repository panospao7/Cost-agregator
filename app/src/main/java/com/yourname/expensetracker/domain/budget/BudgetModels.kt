package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

data class BudgetStatus(
    val budget: Budget,
    val category: Category?,
    val spentAmount: Double,
    val remainingAmount: Double,
    /**
     * RP-09 (P6-005): kept NON-null as a source-compatibility value. When
     * [percentKnown] is false this field is a placeholder `0f` and MUST NOT be
     * presented as "0% used" or enter any threshold comparison — consumers gate
     * display and alerts on [percentKnown] instead.
     */
    val percentUsed: Float,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long,
    val effectiveLimit: Double,
    val adjustedSpendBreakdown: AdjustedSpendBreakdown? = null, // F11: Shared Expenses Budget Offset
    val currency: String = budget.currency,
    val currencyAssumption: String = budget.currencyAssumption,
    val isPartial: Boolean = false,
    val conversionWarning: String? = null,
    /**
     * RP-09 (P6-005, preferred contract): false when the budget limit could not
     * be converted to home currency at all
     * ([com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE]),
     * so no home-currency percent can exist. Then [percentUsed] is a `0f`
     * placeholder, [healthStatus] is [BudgetHealthStatus.UNKNOWN], and a
     * controlled [conversionWarning] explains the conversion failure.
     *
     * When only some spend rows were excluded (partial conversion) but the limit
     * is known, this stays `true` while [isPartial] is `true` and threshold
     * checks remain allowed.
     */
    val percentKnown: Boolean = true
) {
    val moneySpentAmount: MoneyAmount get() = MoneyAmount(spentAmount, CurrencyCode(currency))
    val moneyRemainingAmount: MoneyAmount get() = MoneyAmount(remainingAmount, CurrencyCode(currency))
    val moneyEffectiveLimit: MoneyAmount get() = MoneyAmount(effectiveLimit, CurrencyCode(currency))
}

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
    EXCEEDED,   // Spent >= 100%
    UNKNOWN     // Conversion failed — status cannot be determined
}

/**
 * The single percent-to-health threshold rule for a known percent: at least 100% is
 * [BudgetHealthStatus.EXCEEDED], then the per-budget critical and warning thresholds.
 * Shared by the repository (gross percent) and the budget card (effective percent) so the
 * thresholds exist in exactly one place. [BudgetHealthStatus.UNKNOWN] is not decided here.
 */
fun budgetHealthForPercent(
    percent: Float,
    notifyAtWarning: Float,
    notifyAtCritical: Float
): BudgetHealthStatus = when {
    percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
    percent >= notifyAtCritical -> BudgetHealthStatus.CRITICAL
    percent >= notifyAtWarning -> BudgetHealthStatus.WARNING
    else -> BudgetHealthStatus.ON_TRACK
}

/** Half a minor unit (0.005): the smallest excess over a limit that still displays as a non-zero amount. */
const val BUDGET_OVER_MIN_EXCESS: Double = 0.005

/**
 * True when [effectiveSpend] is at least half a minor unit above [effectiveLimit] and the limit is
 * known. Also covers a zero or negative (rollover deficit) limit, where any spend is over budget.
 */
fun isEffectivelyOverBudget(
    effectiveSpend: Double,
    effectiveLimit: Double,
    percentKnown: Boolean
): Boolean = percentKnown && effectiveSpend - effectiveLimit >= BUDGET_OVER_MIN_EXCESS

/**
 * The single effective-spend health derivation shared by the budget card and the Home path.
 * Positive limit: [budgetHealthForPercent] on effective spend / effective limit. Limit at or below
 * zero: [BudgetHealthStatus.EXCEEDED] when spend is over it (see [isEffectivelyOverBudget]).
 * Otherwise (percent unknown, or zero limit without spend) [fallback] is returned.
 */
fun effectiveBudgetHealth(
    effectiveSpend: Double,
    effectiveLimit: Double,
    percentKnown: Boolean,
    notifyAtWarning: Float,
    notifyAtCritical: Float,
    fallback: BudgetHealthStatus
): BudgetHealthStatus = when {
    percentKnown && effectiveLimit > 0.0 ->
        budgetHealthForPercent((effectiveSpend / effectiveLimit).toFloat(), notifyAtWarning, notifyAtCritical)
    isEffectivelyOverBudget(effectiveSpend, effectiveLimit, percentKnown) -> BudgetHealthStatus.EXCEEDED
    else -> fallback
}

/** Effective-spend health of this status; null iff effective spend is unavailable (INV-16). */
fun BudgetStatus.effectiveHealthOrNull(): BudgetHealthStatus? =
    adjustedSpendBreakdown?.let {
        effectiveBudgetHealth(
            effectiveSpend = it.effectiveSpend,
            effectiveLimit = effectiveLimit,
            percentKnown = percentKnown,
            notifyAtWarning = budget.notifyAtWarning,
            notifyAtCritical = budget.notifyAtCritical,
            fallback = healthStatus
        )
    }

data class BudgetSuggestion(
    val categoryId: Long?,
    val categoryName: String,
    val categoryIcon: String,
    val suggestedAmount: Double,
    val basedOnMonths: Int,
    val reason: String
)
