package com.yourname.expensetracker.domain.core.money

/**
 * ★ APPROVED TYPE ★ An amount of money in a specific currency.
 *
 * This is the **single approved domain type** for all monetary values in the app.
 * Use [MoneyAmount] instead of raw `Double` or `Pair<Double, String>` in all
 * new domain models, analytics outputs, and UI state.
 *
 * Unlike raw `Double` amounts, a [MoneyAmount] always knows its currency,
 * preventing accidental mixed-currency arithmetic.
 *
 * Migration status:
 * - **Do**: Use in new code, ViewModels, domain models
 * - **Should**: Convert existing `Double` + `displayCurrency: String` pairs over time
 * - **Avoid**: Creating new bare `Double` monetary fields
 *
 * For conversion results that include rate/timestamp metadata, see [ConvertedMoney].
 * For aggregated totals across multiple currencies, see [MoneyAggregate].
 */
// M02 PARTIAL: NaN/Infinity are rejected, but MoneyAmount still stores Double.
// Final stabilization requires BigDecimal or minorUnits Long storage.
data class MoneyAmount(
    val amount: Double,
    val currency: CurrencyCode
) {
    init {
        require(!amount.isNaN() && !amount.isInfinite()) {
            "MoneyAmount must be a finite number, got $amount"
        }
    }

    /** Whether the amount is zero. */
    fun isZero(): Boolean = amount == 0.0

    /** Whether the amount is positive. */
    fun isPositive(): Boolean = amount > 0.0

    /** Whether the amount is negative. */
    fun isNegative(): Boolean = amount < 0.0

    /** Format for display using currency symbol. */
    // TODO (M09): Split formatDisplay into three variants:
    //   fun display(locale: Locale): String       — locale-aware grouping separators, currency symbol
    //   fun exportStable(): String                 — Locale.US always, for CSV/JSON export consistency
    //   fun accounting(): String                   — parentheses for negatives, e.g. (€12.34)
    // Current implementation uses String.format("%.2f") which is Locale-dependent
    // and can produce unexpected separators on某些 locale (e.g. comma as decimal).
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

        // M06-FIXED: Factory for BigDecimal values (migration path from domain.util.Money).
        fun fromBigDecimal(value: java.math.BigDecimal, currency: CurrencyCode): MoneyAmount {
            return MoneyAmount(value.toDouble(), currency)
        }
    }
}
