package com.yourname.expensetracker.domain.transaction

import java.time.LocalDate

/**
 * Why a user-present (flagged) create was refused by the coordinator (MEC domain spec 1.4).
 * Bounded; carries no amount, currency, merchant or zone.
 */
enum class RateRefusalReason {
    /** No stored rate dated on or before the expense's local date. */
    NO_RATE,

    /** A rate exists but is more than 7 calendar days older than the expense date. */
    STALE_RATE,

    /** The currency is not in the catalog or not selectable for new entries (and not home). */
    UNSUPPORTED_CURRENCY,

    /** The home currency could not be resolved (or its digits are unavailable): fail closed. */
    HOME_CURRENCY_UNRESOLVED
}

/**
 * Typed refusal attached to [CreateExpenseResult.ValidationFailed.rateRefusal].
 * [latestRateDate] is set only for [RateRefusalReason.STALE_RATE]; it is a public ECB date and is
 * never logged.
 */
data class RateRefusal(
    val reason: RateRefusalReason,
    val latestRateDate: LocalDate? = null
)

/**
 * Controlled error codes placed in `ValidationFailed.errors` for a rate refusal. Constants only;
 * never payload-derived.
 */
object RateRefusalCodes {
    const val NOT_ELIGIBLE_NONE = "RATE_NOT_ELIGIBLE_NONE"
    const val NOT_ELIGIBLE_STALE = "RATE_NOT_ELIGIBLE_STALE"
    const val NOT_ELIGIBLE_UNSUPPORTED = "RATE_NOT_ELIGIBLE_UNSUPPORTED"
    const val NOT_ELIGIBLE_HOME_UNRESOLVED = "RATE_NOT_ELIGIBLE_HOME_UNRESOLVED"

    /** Error code for a flagged amount with more fractional digits than the currency allows. */
    const val AMOUNT_PRECISION_EXCEEDED = "AMOUNT_PRECISION_EXCEEDED"

    /** Error code for a flagged amount whose currency digits are unavailable (OD-6, fail closed). */
    const val AMOUNT_DIGITS_UNAVAILABLE = "AMOUNT_DIGITS_UNAVAILABLE"

    fun codeFor(reason: RateRefusalReason): String = when (reason) {
        RateRefusalReason.NO_RATE -> NOT_ELIGIBLE_NONE
        RateRefusalReason.STALE_RATE -> NOT_ELIGIBLE_STALE
        RateRefusalReason.UNSUPPORTED_CURRENCY -> NOT_ELIGIBLE_UNSUPPORTED
        RateRefusalReason.HOME_CURRENCY_UNRESOLVED -> NOT_ELIGIBLE_HOME_UNRESOLVED
    }
}
