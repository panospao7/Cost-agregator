package com.yourname.expensetracker.ui.screens.savings

import com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.SavingsGoalCurrencyPolicy
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Single owner of the quick-add amount, used by both the ViewModel write and the button label so
 * they cannot disagree.
 */
internal object SavingsQuickAdd {
    /** Quick-add step in the goal's own currency (major units). */
    private val STEP = BigDecimal(10)

    /**
     * Amount a quick add writes: the step capped at the remaining amount.
     *
     * The remaining amount (exact decimal target minus current) is rounded HALF_UP to
     * [fractionDigits] before the cap, so floating-point dust such as 1E-16 counts as nothing
     * left. Returns null (no-op) when the inputs are not finite, [fractionDigits] is unknown
     * (fail closed), or the rounded remaining is not positive. A non-null result is at least one
     * minor unit and carries scale [fractionDigits].
     */
    fun amountOrNull(target: Double, current: Double, fractionDigits: Int?): BigDecimal? {
        if (fractionDigits == null || fractionDigits < 0) return null
        if (!target.isFinite() || !current.isFinite()) return null
        val remaining = BigDecimal.valueOf(target)
            .subtract(BigDecimal.valueOf(current))
            .setScale(fractionDigits, RoundingMode.HALF_UP)
        if (remaining.signum() <= 0) return null
        return remaining.min(STEP.setScale(fractionDigits))
    }

    /**
     * Display-only completion: true when the remaining amount rounded HALF_UP to [fractionDigits]
     * is not positive (so 0.7999999999999999 of 0.80 is complete). Unknown digits or non-finite
     * inputs keep the plain `current >= target` comparison. Never changes stored values.
     */
    fun isCompleted(target: Double, current: Double, fractionDigits: Int?): Boolean {
        if (fractionDigits == null || fractionDigits < 0 || !target.isFinite() || !current.isFinite()) {
            return current >= target
        }
        return BigDecimal.valueOf(target)
            .subtract(BigDecimal.valueOf(current))
            .setScale(fractionDigits, RoundingMode.HALF_UP)
            .signum() <= 0
    }

    /** Goal overload: unknown currency or unknown fraction digits keeps `current >= target`. */
    fun isCompleted(goal: SavingsGoal): Boolean {
        val currency = SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal)
        val digits = currency?.let { CurrencyAmountPolicy.fractionDigits(it) }
        return isCompleted(goal.targetAmount, goal.currentAmount, digits)
    }

    /** Button label amount, formatted by the same money formatter as the card amounts. */
    fun labelAmount(amount: BigDecimal, currencyCode: String): String =
        CurrencyFormatter.formatMoney(amount.toDouble(), currencyCode)

    /** Goal overload: unknown currency or unknown fraction digits gives null. */
    fun amountOrNull(goal: SavingsGoal): BigDecimal? {
        val currency = SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal) ?: return null
        return amountOrNull(goal.targetAmount, goal.currentAmount, CurrencyAmountPolicy.fractionDigits(currency))
    }
}
