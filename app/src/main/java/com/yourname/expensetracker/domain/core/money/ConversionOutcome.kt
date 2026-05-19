package com.yourname.expensetracker.domain.core.money

/**
 * Result of a currency conversion attempt — either [Converted] or [Failed].
 *
 * Replaces nullable `ConversionResult?` at important boundaries so callers
 * can distinguish success from different failure modes.
 */
sealed interface ConversionOutcome {

    data class Converted(
        val originalAmount: Double,
        val originalCurrency: CurrencyCode,
        val convertedAmount: Double,
        val targetCurrency: CurrencyCode,
        val rateUsed: Double,
        val rateBasis: RateBasis,
        val rateValidDate: Long?,
        val rateLastUpdated: Long?,
        val rateSource: String?,
        val conversionPath: ConversionPath
    ) : ConversionOutcome

    data class Failed(
        val originalAmount: Double,
        val originalCurrency: String,
        val targetCurrency: String,
        val rateBasis: RateBasis,
        val failureType: ConversionFailureType,
        val message: String
    ) : ConversionOutcome
}
