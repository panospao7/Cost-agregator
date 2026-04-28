package com.yourname.expensetracker.domain.core.money

/**
 * An amount of money in a specific currency.
 *
 * This is the fundamental currency-safe building block. Unlike raw `Double` amounts, *
 * a [MoneyAmount] always knows its currency, preventing accidental mixed-currency
 * arithmetic.
 *
 * Use this instead of `Pair<Double, String>` or bare `Double` in domain models
 * and aggregation results.
 *
 * For conversion results that include rate/timestamp metadata, see [ConvertedMoney].
 * For aggregated totals across multiple currencies, see [MoneyAggregate].
 */
data class MoneyAmount(
    val amount: Double,
    val currency: CurrencyCode
) {

    /** Whether the amount is zero. */
    fun isZero(): Boolean = amount == 0.0

    /** Whether the amount is positive. */
    fun isPositive(): Boolean = amount > 0.0

    /** Whether the amount is negative. */
    fun isNegative(): Boolean = amount < 0.0

    /** Format for display using currency symbol. */
    fun formatDisplay(): String = "${CurrencyCode.symbolFor(currency)}${String.format("%.2f", amount)}"

    /** Add two money amounts in the SAME currency. Throws if currencies differ. */
    operator fun plus(other: MoneyAmount): MoneyAmount {
        require(currency == other.currency) {
            "Cannot add MoneyAmount with different currencies: $currency + ${other.currency}. Use currency conversion first."
        }
        return MoneyAmount(amount + other.amount, currency)
    }

    /** Subtract two money amounts in the SAME currency. Throws if currencies differ. */
    operator fun minus(other: MoneyAmount): MoneyAmount {
        require(currency == other.currency) {
            "Cannot subtract MoneyAmount with different currencies: $currency - ${other.currency}. Use currency conversion first."
        }
        return MoneyAmount(amount - other.amount, currency)
    }

    /** Multiply by a scalar. */
    operator fun times(factor: Double): MoneyAmount = MoneyAmount(amount * factor, currency)

    /** Negate the amount. */
    fun negate(): MoneyAmount = MoneyAmount(-amount, currency)

    /** Absolute value. */
    fun abs(): MoneyAmount = MoneyAmount(kotlin.math.abs(amount), currency)

    companion object {
        val ZERO_EUR = MoneyAmount(0.0, CurrencyCode.EUR)

        /** Create a zero amount in the given currency. */
        fun zero(currency: CurrencyCode): MoneyAmount = MoneyAmount(0.0, currency)
    }
}
