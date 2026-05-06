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
 *
 * ## CURR-11: Minor-unit migration (Double → Long) — breaking change (planned)
 * Currently [MoneyAmount.amount] is stored as a `Double`, which causes
 * precision loss for fractional-cent values (e.g. 0.1 + 0.2 = 0.30000000000000004)
 * and is incompatible with financial standards that mandate minor-unit arithmetic.
 *
 * ### Migration plan
 * 1. Introduce a new `MoneyAmountMinor` type (or overload) that stores `amount`
 *    as a `Long` representing the minor unit (cents for EUR/USD, satoshis for BTC,
 *    etc.) alongside a `CurrencyCode`.
 * 2. Add a `decimals: Int = currency.defaultDecimals()` parameter so the same
 *    type works for all currencies regardless of their exponent (EUR=2, JPY=0,
 *    BTC=8, TND=3).
 * 3. Provide bidirectional conversion helpers:
 *    ```kotlin
 *    fun MoneyAmount.toMinorUnits(): Long = (amount * 10.0.pow(decimals)).roundToLong()
 *    fun Long.toMajorUnits(currency: CurrencyCode): MoneyAmount = ...
 *    ```
 * 4. **Compatibility break**: All Room entities, DAO queries, and domain models
 *    that currently store/receive `Double` amounts must be migrated in a single
 *    schema version bump. A Room migration must CAST existing double values to
 *    the equivalent minor-unit Long, using a per-currency multiplier.
 * 5. **Transition period**: Keep both `Double` and `Long` accessors on the
 *    [MoneyAmount] type so that code not yet migrated compiles (deprecated).
 * 6. Remove the `Double`-backed [MoneyAmount] entirely once all callers are
 *    converted.
 *
 * ### Affected areas
 * - All `@Entity` classes with monetary fields (Expense, Budget, etc.)
 * - All DAO `INSERT`/`UPDATE`/`query` methods that read/write amounts
 * - [MultiCurrencyRepository], [CurrencyConverter], analytics normalisers
 * - UI formatting (currently uses `String.format("%.2f", amount)`)
 * - Export/import serialisation
 * - Backup/restore (schema version must gate the format)
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
