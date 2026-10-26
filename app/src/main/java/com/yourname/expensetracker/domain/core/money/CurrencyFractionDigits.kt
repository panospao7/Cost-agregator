package com.yourname.expensetracker.domain.core.money

import java.util.Currency

/**
 * Single accessor for the number of minor-unit digits of a currency (ISO 4217,
 * as known to the JDK). Money code in `domain/core/money` that needs the digits
 * of a currency goes through [of]; it must not read the JDK value itself.
 *
 * Fail-closed: an unknown code, or a currency without defined digits (the JDK
 * reports a negative count, for example for the `XXX` placeholder), yields
 * `null`. Callers decide what unavailable means; this accessor never guesses 2.
 */
object CurrencyFractionDigits {

    /** Minor-unit digits of [code] (0 for JPY, 2 for EUR, 3 for BHD), or `null` if unavailable. */
    fun of(code: CurrencyCode): Int? =
        try {
            Currency.getInstance(code.code).defaultFractionDigits.takeIf { it >= 0 }
        } catch (failure: IllegalArgumentException) {
            null
        }
}
