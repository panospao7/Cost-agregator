package com.yourname.expensetracker.domain.savings

import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.SavingsGoal

/** Matching currency labels are not evidence of a legacy goal's denomination. */
object SavingsGoalCurrencyPolicy {
    fun knownCurrencyOrNull(goal: SavingsGoal): CurrencyCode? =
        knownCurrencyOrNull(goal.currency, goal.currencyAssumption)

    fun knownCurrencyOrNull(currency: String, currencyAssumption: String): CurrencyCode? {
        val hasKnownProvenance = when (currencyAssumption) {
            "EXPLICIT", "EXPLICIT_HOME_CURRENCY" -> true
            else -> when (CurrencyAssumption.fromSerializedName(currencyAssumption)) {
                CurrencyAssumption.USER_CONFIRMED, CurrencyAssumption.PARSED_FROM_SOURCE -> true
                else -> false
            }
        }
        return CurrencyCode.parse(currency)
            ?.takeIf { hasKnownProvenance && it in CurrencyCode.ALL_SUPPORTED }
    }

    fun isUnfinished(goal: SavingsGoal): Boolean = goal.currentAmount < goal.targetAmount

    fun isRecommendationEligible(goal: SavingsGoal, homeCurrency: CurrencyCode): Boolean =
        isUnfinished(goal) && knownCurrencyOrNull(goal) == homeCurrency

    fun hasUnknownActiveGoals(goals: List<SavingsGoal>): Boolean =
        goals.any { isUnfinished(it) && knownCurrencyOrNull(it) == null }
}
