package com.yourname.expensetracker.domain.core.money

/**
 * Exactly one transaction already normalized to [normalizedCurrency].
 * Aggregated buckets are not transaction rows; transactionCount must equal one.
 * A supplied transactionId is unique within the aggregate.
 *
 * The row deliberately carries the original currency and rate-valid date so
 * an aggregate can preserve provenance without converting the value again.
 */
data class NormalizedMoneyRow(
    val amount: Double,
    val normalizedCurrency: CurrencyCode,
    val sourceCurrency: CurrencyCode? = null,
    val transactionCount: Int = 1,
    val rateValidDate: Long? = null,
    val transactionId: Long? = null
)

/**
 * Quality and exclusion context belonging to one analytics slice.
 *
 * Callers must pass counts for the slice being aggregated, not counts from a
 * larger source window. This prevents a window-level exclusion count from
 * being attached to every day, category, or other derived slice.
 */
data class NormalizedMoneyQuality(
    val excludedTransactionCount: Int = 0,
    val staleRateCount: Int = 0,
    val missingRateCount: Int = 0,
    val invalidCurrencyCount: Int = 0,
    val conversionQuality: ConversionQuality = ConversionQuality.COMPLETE,
    val warningMessage: String? = null
) {
    init {
        require(excludedTransactionCount >= 0) { "excludedTransactionCount must be non-negative" }
        require(staleRateCount >= 0) { "staleRateCount must be non-negative" }
        require(missingRateCount >= 0) { "missingRateCount must be non-negative" }
        require(invalidCurrencyCount >= 0) { "invalidCurrencyCount must be non-negative" }
    }
}
