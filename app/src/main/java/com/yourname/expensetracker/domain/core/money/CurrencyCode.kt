package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.domain.currency.SupportedCurrency

/**
 * Type-safe wrapper for ISO 4217 currency codes.
 *
 * Replaces raw `String` currency codes in domain models to prevent
 * invalid or missing currency codes from propagating silently.
 *
 * Use [CurrencyCode.parse] for untrusted input (returns null on invalid codes).
 * Use [CurrencyCode] constructor directly only for known-valid codes.
 */
@JvmInline
value class CurrencyCode(val code: String) : Comparable<CurrencyCode> {

    init {
        require(code.length == 3) { "Currency code must be 3 letters: '$code'" }
        require(code.all { it.isUpperCase() || it.isDigit() }) { "Currency code must be uppercase alphanumeric: '$code'" }
    }

    override fun compareTo(other: CurrencyCode): Int = code.compareTo(other.code)

    override fun toString(): String = code

    companion object {
        val EUR = CurrencyCode("EUR")
        val USD = CurrencyCode("USD")
        val GBP = CurrencyCode("GBP")
        val JPY = CurrencyCode("JPY")
        val CHF = CurrencyCode("CHF")
        val CAD = CurrencyCode("CAD")
        val AUD = CurrencyCode("AUD")
        val SEK = CurrencyCode("SEK")
        val NOK = CurrencyCode("NOK")
        val DKK = CurrencyCode("DKK")
        val PLN = CurrencyCode("PLN")
        val CZK = CurrencyCode("CZK")
        val HUF = CurrencyCode("HUF")
        val RON = CurrencyCode("RON")
        val BGN = CurrencyCode("BGN")
        val HRK = CurrencyCode("HRK")
        val ISK = CurrencyCode("ISK")

        /** All supported currency codes matching the SupportedCurrency enum. */
        val ALL_SUPPORTED = SupportedCurrency.values().map { CurrencyCode(it.code) }.toSet()

        /**
         * Parse a currency code from untrusted input.
         * Returns null if the code is not a valid 3-letter ISO currency.
         */
        fun parse(input: String?): CurrencyCode? {
            if (input.isNullOrBlank()) return null
            val upper = input.trim().uppercase()
            if (upper.length != 3) return null
            if (!upper.all { it.isUpperCase() || it.isDigit() }) return null
            return try {
                CurrencyCode(upper)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * Parse or fallback to a default currency code.
         * Use this when null is not acceptable but you want safe parsing.
         */
        fun parseOr(input: String?, fallback: CurrencyCode): CurrencyCode =
            parse(input) ?: fallback

        /**
         * Get the symbol for this currency code from SupportedCurrency, or
         * return the raw code if unknown.
         */
        fun symbolFor(code: CurrencyCode): String =
            SupportedCurrency.fromCode(code.code)?.symbol ?: code.code
    }
}

/** Extension to convert a String to CurrencyCode safely, returning null on invalid input. */
fun String?.toCurrencyCodeOrNull(): CurrencyCode? = CurrencyCode.parse(this)

/** Extension to convert a String to CurrencyCode with fallback. */
fun String?.toCurrencyCodeOr(fallback: CurrencyCode): CurrencyCode = CurrencyCode.parseOr(this, fallback)
