package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.budget.BudgetHealthStatus

data class BudgetStatusSnapshot(
    val budgetCategoryId: Long?,
    val budgetAmount: Double,
    val categoryName: String?,
    val spentAmount: Double,
    val remainingAmount: Double,
    val percentUsed: Double,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long,
    /** P5-NEW-06: true when the budget limit and/or spend could not be fully converted. */
    val isPartial: Boolean = false,
    /** P5-NEW-06: human-readable conversion warning when [isPartial], else null. */
    val conversionWarning: String? = null,
    /** Denomination of effectiveLimit/remainingAmount, not a guessed home currency. */
    val currency: String? = null,
    val currencyAssumption: String = "UNKNOWN",
    val percentKnown: Boolean = true,
    /**
     * FL-06: shared-expense-adjusted spend in [currency] (the Budget card's effective spend),
     * or null when the adjusted breakdown is unavailable. Never defaults to [spentAmount].
     */
    val effectiveSpend: Double? = null,
    /**
     * Health of [effectiveSpend] over the limit (the Budget card's health), from the shared domain
     * derivation; null when effective spend is unavailable. [healthStatus] stays gross-based and
     * keeps feeding the cloud payload unchanged.
     */
    val effectiveHealthStatus: BudgetHealthStatus? = null
) {
    /**
     * True when the budget period spans 28 to 31 days (rounded to whole days so a DST-shifted day
     * cannot push a month out of range). Weekly, yearly and other periods are not month-scale, so
     * calendar-month rate math must not read their limit.
     */
    val isMonthScalePeriod: Boolean
        get() = Math.round((periodEnd - periodStart) / 86_400_000.0) in 28L..31L

    fun hasCompleteAmountIn(homeCurrency: String): Boolean {
        val target = com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(homeCurrency)
            ?.takeIf { it in com.yourname.expensetracker.domain.core.money.CurrencyCode.ALL_SUPPORTED }
            ?: return false
        val knownProvenance = currencyAssumption in setOf("EXPLICIT", "EXPLICIT_HOME_CURRENCY", "USER_CONFIRMED", "PARSED_FROM_SOURCE")
        return knownProvenance && com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(currency.orEmpty()) == target &&
            !isPartial && conversionWarning == null && percentKnown &&
            budgetAmount.isFinite() && budgetAmount >= 0.0 && remainingAmount.isFinite()
    }
}
