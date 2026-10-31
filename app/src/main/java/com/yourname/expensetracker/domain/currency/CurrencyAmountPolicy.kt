package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.CurrencyFractionDigits
import java.math.BigDecimal

/**
 * Outcome of [CurrencyAmountPolicy.check]. The checked amount is never rounded or truncated:
 * every variant describes the amount exactly as it was parsed.
 */
sealed interface AmountCheck {
    /** The amount is positive, within the currency's digits and within the currency's cap. */
    data object Ok : AmountCheck

    /** The amount is zero, negative or not a finite number. */
    data object NotPositive : AmountCheck

    /** The amount has more fractional digits than the currency allows ([allowedDigits]). */
    data class PrecisionExceeded(val allowedDigits: Int) : AmountCheck

    /** The amount is above the per-currency cap ([cap], major units). */
    data class AboveCap(val cap: BigDecimal) : AmountCheck

    /**
     * The currency's minor-unit digits are unknown (OD-6, fail closed). Never treated as 2 digits
     * and never as "no precision limit".
     */
    data object DigitsUnavailable : AmountCheck
}

/**
 * Single owner of the per-currency amount rules for user-entered amounts: minor-unit digits
 * (INV-12/13) and the maximum amount (INV-14).
 *
 * - Digits are NOT defined here. [fractionDigits] delegates to the one digits accessor,
 *   [CurrencyFractionDigits.of]; this object adds no second accessor and no rounding.
 * - [cap] is a static typo guard (domain spec section 6), independent of rate availability.
 * - [check] runs on the parsed value and never rounds it.
 *
 * Pure: no clock, I/O, logging or randomness.
 */
object CurrencyAmountPolicy {

    /** Cap for every code that is not in the table (non-catalog codes keep today's limit). */
    val DEFAULT_CAP: BigDecimal = BigDecimal("1000000")

    private val CAP_1M = DEFAULT_CAP
    private val CAP_10M = BigDecimal("10000000")
    private val CAP_100M = BigDecimal("100000000")
    private val CAP_1B = BigDecimal("1000000000")

    private val capsByCode: Map<String, BigDecimal> = buildMap {
        listOf(
            CurrencyCode.EUR.code, "USD", "GBP", "CHF", "CAD", "AUD", "NZD", "SGD",
            "CNY", "HKD", "DKK", "PLN", "RON", "BRL", "BGN", "HRK"
        ).forEach { put(it, CAP_1M) }
        listOf("MXN", "ZAR", "SEK", "NOK", "CZK", "TRY").forEach { put(it, CAP_10M) }
        listOf("JPY", "INR", "RUB", "HUF", "ISK").forEach { put(it, CAP_100M) }
        put("KRW", CAP_1B)
    }

    /**
     * Minor-unit digits of [code], or `null` when unavailable. Delegates to
     * [CurrencyFractionDigits.of]; never guesses a value.
     */
    fun fractionDigits(code: CurrencyCode): Int? = CurrencyFractionDigits.of(code)

    /** Maximum allowed amount in major units for [code]; [DEFAULT_CAP] for codes outside the table. */
    fun cap(code: CurrencyCode): BigDecimal = capsByCode[code.code] ?: DEFAULT_CAP

    /**
     * Checks an amount as parsed from user input.
     *
     * Order: not positive, digits unavailable, precision, cap. The precision test is
     * `parsed.stripTrailingZeros().scale() <= digits` (trailing zeros do not count, so
     * `1500.00` is whole). An amount equal to the cap is allowed.
     */
    fun check(parsed: BigDecimal, code: CurrencyCode): AmountCheck {
        if (parsed.signum() <= 0) return AmountCheck.NotPositive
        val digits = fractionDigits(code) ?: return AmountCheck.DigitsUnavailable
        if (parsed.stripTrailingZeros().scale() > digits) return AmountCheck.PrecisionExceeded(digits)
        val cap = cap(code)
        if (parsed.compareTo(cap) > 0) return AmountCheck.AboveCap(cap)
        return AmountCheck.Ok
    }

    /**
     * Convenience for callers that hold the parser's `Double`. Converts with
     * [BigDecimal.valueOf] (the decimal string of the double, not its binary expansion);
     * a non-finite value is [AmountCheck.NotPositive].
     */
    fun check(parsed: Double, code: CurrencyCode): AmountCheck =
        if (!parsed.isFinite()) AmountCheck.NotPositive else check(BigDecimal.valueOf(parsed), code)
}
