package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode

/**
 * Typed read-time home currency state (MC 1a-3a).
 *
 * Unlike [HomeCurrencyResolution.Failed], failures carry only a bounded
 * [ReadHomeFailure] code: no raw exception message, stored value, or path ever
 * crosses this boundary (privacy fail-closed, bounded diagnostics).
 *
 * Producers ([CurrencySettingsRepository.observeReadHomeCurrency]) emit only
 * [Available] and [Unavailable] with [ReadHomeFailure.READ_ERROR] or
 * [ReadHomeFailure.INVALID_CODE]. [Loading] and [ReadHomeFailure.TIMEOUT] are
 * applied by the consumer (e.g. before the first emission, or when no emission
 * arrives within the consumer's own deadline).
 */
sealed interface ReadHomeCurrencyState {

    /** No value has been read yet. Consumer-applied; never emitted by repositories. */
    data object Loading : ReadHomeCurrencyState

    /**
     * Home currency read successfully.
     *
     * @param firstRunDefault true when no home currency has been stored yet and
     * [code] is the EUR first-run default (not a user choice).
     */
    data class Available(
        val code: CurrencyCode,
        val firstRunDefault: Boolean
    ) : ReadHomeCurrencyState

    /** Home currency could not be determined; never substitute a default. */
    data class Unavailable(val reason: ReadHomeFailure) : ReadHomeCurrencyState

    companion object {
        /**
         * Single mapping rule from a stored raw home-currency string.
         * null/blank -> first-run default EUR; unparseable -> INVALID_CODE
         * (the raw value is intentionally dropped).
         */
        fun fromStoredValue(raw: String?): ReadHomeCurrencyState {
            if (raw.isNullOrBlank()) {
                return Available(CurrencyCode.EUR, firstRunDefault = true)
            }
            val parsed = CurrencyCode.parse(raw)
                ?: return Unavailable(ReadHomeFailure.INVALID_CODE)
            return Available(parsed, firstRunDefault = false)
        }
    }
}

/** Bounded failure codes for [ReadHomeCurrencyState.Unavailable]. */
enum class ReadHomeFailure {
    /** The underlying settings store could not be read. */
    READ_ERROR,

    /** No value arrived within the consumer's deadline. Consumer-applied. */
    TIMEOUT,

    /** A value was stored but is not a valid currency code. */
    INVALID_CODE
}
