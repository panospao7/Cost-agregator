package com.yourname.expensetracker.domain.core.money

/**
 * Record of a currency conversion that could not be completed.
 *
 * Conversion failures must be visible to the user — they must NOT be
 * silently dropped from totals. Instead, the UI should show a warning
 * like "Total excludes 2 transactions because rates were unavailable."
 *
 * This type replaces the older [FailedConversion] in CurrencyConverter
 * (which used raw String currency codes). The old type remains for
 * backward compatibility; new code should use this one.
 */
data class ConversionFailure(
    val originalAmount: MoneyAmount,
    val targetCurrency: CurrencyCode,
    val reason: FailureReason,
    val transactionCount: Int = 0  // E1: how many transactions this failure affects
) {

    /** Human-readable description of the failure. */
    val description: String
        get() = when (reason) {
            FailureReason.MISSING_RATE -> "Missing exchange rate from ${originalAmount.currency.code} to ${targetCurrency.code}"
            FailureReason.INVALID_AMOUNT -> "Invalid amount: ${originalAmount.amount} ${originalAmount.currency.code}"
            FailureReason.RATE_STALE -> "Exchange rate from ${originalAmount.currency.code} to ${targetCurrency.code} is too old"
            FailureReason.UNKNOWN -> "Unknown conversion error for ${originalAmount.amount} ${originalAmount.currency.code}"
        }
}

/**
 * Why a currency conversion failed.
 */
enum class FailureReason {
    MISSING_RATE,
    INVALID_AMOUNT,
    RATE_STALE,
    UNKNOWN
}
