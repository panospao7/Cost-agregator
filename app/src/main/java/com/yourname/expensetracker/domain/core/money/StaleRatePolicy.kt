package com.yourname.expensetracker.domain.core.money

/**
 * Policy for determining when an exchange rate is considered stale.
 */
data class StaleRatePolicy(
    val maxAgeMs: Long?,
    val compareAgainst: StaleRateReference = StaleRateReference.NOW
) {
    companion object {
        /** Default: 24 hours compared against current time. */
        val Default = StaleRatePolicy(maxAgeMs = 24 * 60 * 60 * 1000L, compareAgainst = StaleRateReference.NOW)
        /** No staleness check — accept any rate. */
        val None = StaleRatePolicy(maxAgeMs = null)
    }
}

enum class StaleRateReference {
    NOW,
    TRANSACTION_DATE,
    RATE_VALID_DATE
}
