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
        /** CURR-9A6-05: Latest-rate staleness for normalization engine — 7 days. */
        val LatestDefault = StaleRatePolicy(maxAgeMs = 7 * 24 * 60 * 60 * 1000L, compareAgainst = StaleRateReference.NOW)
        /** No staleness check — accept any rate. */
        val None = StaleRatePolicy(maxAgeMs = null)

        /** CURR-3E8-08: Canonical policy selection based on rate basis. */
        fun forBasis(rateBasis: com.yourname.expensetracker.domain.core.money.RateBasis): StaleRatePolicy =
            if (rateBasis == com.yourname.expensetracker.domain.core.money.RateBasis.LATEST_AVAILABLE) LatestDefault else None
    }
}

enum class StaleRateReference {
    NOW,
    TRANSACTION_DATE,
    RATE_VALID_DATE
}
