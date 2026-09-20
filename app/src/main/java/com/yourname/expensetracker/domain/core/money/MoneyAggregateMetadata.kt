package com.yourname.expensetracker.domain.core.money

/**
 * Metadata about how a [MoneyAggregate] was produced.
 */
data class MoneyAggregateMetadata(
    val includedTransactionCount: Int = 0,
    val excludedTransactionCount: Int = 0,
    val staleRateCount: Int = 0,
    val missingRateCount: Int = 0,
    val invalidCurrencyCount: Int = 0,
    val latestRateValidDate: Long? = null,
    val oldestRateValidDate: Long? = null,
    /**
     * NEW-P5-009 (RP-06 §6c): true when a NON-EMPTY per-bucket transaction
     * counts list supplied to the aggregate builder did not line up with the
     * bucket list (shorter OR longer). Count-dependent consumers must then treat
     * counts as unknown/approximate instead of trusting silently-defaulted
     * zeros. An EMPTY counts list is the builder's count-agnostic opt-out
     * (parameter default) and is NOT flagged — counts are unknown by design
     * there, not damaged. The amounts themselves are always trustworthy; only
     * the counts are suspect when this flag is set.
     */
    val countsIncomplete: Boolean = false
)
