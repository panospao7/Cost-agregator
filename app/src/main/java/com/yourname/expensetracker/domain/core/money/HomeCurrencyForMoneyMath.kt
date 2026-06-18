package com.yourname.expensetracker.domain.core.money

/**
 * CURR-587-02: Domain-use policy result for home-currency resolution in financial math.
 *
 * Maps HomeCurrencyResolution to a simpler Available/Unavailable for use-case logic.
 * Resolved and FirstRunDefault both map to Available; Failed maps to Unavailable.
 */
sealed interface HomeCurrencyForMoneyMath {
    data class Available(
        val currency: CurrencyCode,
        val firstRunDefault: Boolean = false
    ) : HomeCurrencyForMoneyMath

    data class Unavailable(
        val reason: String
    ) : HomeCurrencyForMoneyMath
}
