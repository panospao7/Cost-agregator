package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode

/**
 * Result of resolving the user's home currency.
 *
 * Distinguishes between:
 * - [Resolved]: User has explicitly set a home currency.
 * - [FirstRunDefault]: No setting exists yet; using EUR as first-run default.
 * - [Failed]: Settings could not be read (DataStore error, corruption, etc.).
 *
 * Financial calculations must NOT proceed with [Failed] — they should
 * surface partial/unavailable status instead of silently using EUR.
 */
sealed interface HomeCurrencyResolution {
    val currencyOrNull: CurrencyCode? get() = when (this) {
        is Resolved -> currency
        is FirstRunDefault -> currency
        is Failed -> null
    }

    data class Resolved(val currency: CurrencyCode) : HomeCurrencyResolution
    data class FirstRunDefault(val currency: CurrencyCode) : HomeCurrencyResolution
    data class Failed(val reason: String) : HomeCurrencyResolution
}
