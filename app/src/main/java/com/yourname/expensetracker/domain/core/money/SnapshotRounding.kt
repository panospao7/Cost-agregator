package com.yourname.expensetracker.domain.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Result of [roundSnapshot]: either the stored-snapshot amount in the home
 * currency, or a bounded reason why none can be produced.
 */
sealed class SnapshotRounding {

    /**
     * Rounded snapshot amount in major units of the home currency. Always `> 0`
     * and exactly representable at the home currency's minor-unit digits
     * (`BigDecimal.valueOf(amount)` has scale at most the digits).
     */
    data class Rounded(val amount: Double) : SnapshotRounding()

    /** No snapshot amount. [reason] is a bounded code; never carries the value or currency. */
    data class Unavailable(val reason: SnapshotRoundingUnavailableReason) : SnapshotRounding()
}

/** Why [roundSnapshot] produced no amount. Bounded; for diagnostics and tests. */
enum class SnapshotRoundingUnavailableReason {
    /** The value is NaN, infinite, zero or negative. */
    NON_FINITE_OR_NON_POSITIVE,

    /** The home currency has no known minor-unit digits (unknown code or placeholder such as XXX). */
    DIGITS_UNAVAILABLE
}

/**
 * Sole rounding rule for a converted snapshot amount that is stored in the home
 * currency (INV-16). Pure: no clock, I/O or logging.
 *
 * 1. Non-finite, zero or negative [value] -> `Unavailable(NON_FINITE_OR_NON_POSITIVE)`.
 * 2. Digits come from [CurrencyFractionDigits]; unavailable -> `Unavailable(DIGITS_UNAVAILABLE)`.
 * 3. `BigDecimal.valueOf(value)` (the decimal string of the double, not its binary
 *    expansion) is rounded to the digits with HALF_EVEN.
 * 4. A positive value never rounds to zero: a zero result becomes one minor unit.
 *
 * The scope is the snapshot only; input rounding, splits, settlements and display
 * formatting use their own rules and are unchanged.
 */
fun roundSnapshot(value: Double, home: CurrencyCode): SnapshotRounding {
    if (!value.isFinite() || value <= 0.0) {
        return SnapshotRounding.Unavailable(SnapshotRoundingUnavailableReason.NON_FINITE_OR_NON_POSITIVE)
    }
    val digits = CurrencyFractionDigits.of(home)
        ?: return SnapshotRounding.Unavailable(SnapshotRoundingUnavailableReason.DIGITS_UNAVAILABLE)
    val rounded = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN)
    val atLeastOneMinorUnit = if (rounded.signum() == 0) BigDecimal.ONE.movePointLeft(digits) else rounded
    return SnapshotRounding.Rounded(atLeastOneMinorUnit.toDouble())
}
