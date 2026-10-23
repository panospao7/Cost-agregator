package com.yourname.expensetracker.domain.installment.model

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.CurrencyFractionDigits
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Minor-unit helpers of the installment feature (domain spec section 9, INV-11).
 *
 * Stored plan and entry amounts are Doubles at exactly 2 decimals in the plan currency. All
 * schedule arithmetic, sums, differences and comparisons run in Long minor units; no Double sum
 * is ever compared for equality.
 *
 * Rounding point (the only one in this feature): [toMinorOrNull] rounds a value with more than
 * two decimals to two decimals, HALF_UP, on the decimal text of the Double (`BigDecimal.valueOf`),
 * never on a floating-point product. This follows the spec (OD-7: lender statements and consumer
 * displays round half-up). A value that already has at most two decimals converts exactly. The
 * integer split in the schedule calculator needs no rounding mode.
 *
 * These helpers deliberately do not touch `AmountUtils` or `AmountInputSanitizer`.
 */
object InstallmentMinorUnits {

    /** Minor-unit digits of every supported plan currency (EUR and USD both have 2). */
    const val SCALE: Int = 2

    /**
     * Amounts at or above this many major units are refused. Any 2 dp value below 10^9
     * round-trips exactly through minor units (spec Q6), and the bound keeps every Long sum in
     * this feature (up to 480 entries) far from overflow.
     */
    const val MAX_MAJOR_UNITS: Long = 1_000_000_000L

    private val maxAbsMinor: Long = MAX_MAJOR_UNITS * 100L

    /**
     * Minor-unit digits for [currency] as reported by the shared money accessor, or null if that
     * accessor does not know the currency. Callers treat anything other than [SCALE] as an
     * unsupported currency (fail closed); the scale is never assumed.
     */
    fun fractionDigitsOrNull(currency: InstallmentCurrency): Int? {
        val code = CurrencyCode.parse(currency.code) ?: return null
        return CurrencyFractionDigits.of(code)
    }

    /** true when the shared money accessor confirms [currency] uses exactly [SCALE] digits. */
    fun isSupportedScale(currency: InstallmentCurrency): Boolean = fractionDigitsOrNull(currency) == SCALE

    /**
     * Converts a stored or entered amount to minor units, HALF_UP at two decimals. Returns null
     * for NaN, infinite or out-of-range values (|amount| >= [MAX_MAJOR_UNITS]); the caller
     * refuses the input and never substitutes 0.
     */
    fun toMinorOrNull(amount: Double): Long? {
        if (amount.isNaN() || amount.isInfinite()) return null
        val minor = BigDecimal.valueOf(amount)
            .movePointRight(SCALE)
            .setScale(0, RoundingMode.HALF_UP)
        if (minor.abs() >= BigDecimal.valueOf(maxAbsMinor)) return null
        return minor.longValueExact()
    }

    /** Exact conversion of minor units back to a 2-decimal Double for storage. */
    fun fromMinor(minor: Long): Double = BigDecimal.valueOf(minor, SCALE).toDouble()
}
