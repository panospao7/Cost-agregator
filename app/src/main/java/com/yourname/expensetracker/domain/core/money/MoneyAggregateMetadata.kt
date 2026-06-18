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
    val oldestRateValidDate: Long? = null
)
