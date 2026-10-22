package com.yourname.expensetracker.domain.core.money

/**
 * Result of the comparable-home-value rule ([ComparableHomeValue.comparableValue]):
 * either an ownership-adjusted amount expressed in the home currency, or the
 * bounded reason the row has no such amount.
 *
 * SQL represents [None] as `NULL` and has no reason; parity tests compare
 * [Value] against NULL only.
 */
sealed class ComparableValue {

    /**
     * Ownership-adjusted amount in major units of the home currency.
     *
     * May be 0.0 (not-mine row, 0 % share). Negative only for pre-validator legacy
     * amounts in the home currency. `-0.0` never appears here: the rule owner
     * normalises it to `0.0` (SQLite treats them as equal, Kotlin `compareTo` does not).
     */
    data class Value(val amountInHome: Double) : ComparableValue()

    /** No comparable value. [reason] is a bounded code; never row content. */
    data class None(val reason: NoComparableValueReason) : ComparableValue()
}

/**
 * Why a foreign-currency row has no comparable value. Bounded; for tests and
 * diagnostics only, never shown with row content.
 */
enum class NoComparableValueReason {
    /** Foreign row with `baseAmount <= 0` (sentinel, legacy default, no-rate row). */
    NO_SNAPSHOT,

    /** Foreign row with `baseAmount > 0` snapshotted in a currency other than home. */
    SNAPSHOT_OTHER_CURRENCY,

    /** Foreign row with a home-currency snapshot but `exchangeRateUsed <= 0`. */
    SNAPSHOT_RATE_INVALID
}
