package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.domain.util.TimePeriodUtils

/** Bounded reason codes for [BudgetPeriodRemaining.Unavailable]; checked in declaration order. */
enum class BudgetPeriodRemainingUnavailableReason {
    /** The snapshot is not complete in the home currency, or its percent is unknown. */
    CONVERSION_INCOMPLETE,

    /** No finite shared-expense-adjusted (effective) spend exists for the budget. */
    EFFECTIVE_SPEND_UNAVAILABLE,

    /** The budget limit is not strictly positive, so no remainder is defined. */
    NO_POSITIVE_LIMIT
}

/**
 * FL-06: the single "money left in the budget period" figure that the Home headline, Runway and
 * Financial Weather all read. Built only from the effective limit and effective spend of the
 * overall budget, so every surface shows the same number.
 */
sealed interface BudgetPeriodRemaining {
    /**
     * @property limit effective budget limit, strictly positive.
     * @property effectiveSpend finite shared-expense-adjusted spend.
     * @property remaining `max(limit - effectiveSpend, 0)`.
     * @property overBy excess over the limit; 0 below half a minor unit ([isEffectivelyOverBudget]).
     * @property daysLeft whole days left in the budget period (period end is exclusive), never negative.
     */
    data class Available(
        val limit: Double,
        val effectiveSpend: Double,
        val remaining: Double,
        val overBy: Double,
        val daysLeft: Int
    ) : BudgetPeriodRemaining

    data class Unavailable(val reason: BudgetPeriodRemainingUnavailableReason) : BudgetPeriodRemaining
}

/**
 * Pure derivation of [BudgetPeriodRemaining]. Reason check order: conversion
 * ([complete] or [percentKnown] false), effective spend (null or non-finite), then limit
 * (not strictly positive).
 */
fun budgetPeriodRemaining(
    limit: Double,
    effectiveSpend: Double?,
    complete: Boolean,
    percentKnown: Boolean,
    periodEnd: Long,
    nowMs: Long
): BudgetPeriodRemaining {
    if (!complete || !percentKnown) {
        return BudgetPeriodRemaining.Unavailable(BudgetPeriodRemainingUnavailableReason.CONVERSION_INCOMPLETE)
    }
    if (!limit.isFinite() || limit <= 0.0) {
        return BudgetPeriodRemaining.Unavailable(BudgetPeriodRemainingUnavailableReason.NO_POSITIVE_LIMIT)
    }
    if (effectiveSpend == null || !effectiveSpend.isFinite()) {
        return BudgetPeriodRemaining.Unavailable(BudgetPeriodRemainingUnavailableReason.EFFECTIVE_SPEND_UNAVAILABLE)
    }
    val over = isEffectivelyOverBudget(effectiveSpend, limit, percentKnown)
    return BudgetPeriodRemaining.Available(
        limit = limit,
        effectiveSpend = effectiveSpend,
        remaining = (limit - effectiveSpend).coerceAtLeast(0.0),
        overBy = if (over) effectiveSpend - limit else 0.0,
        daysLeft = (TimePeriodUtils.daysBetween(nowMs, periodEnd) - 1).coerceAtLeast(0)
    )
}
