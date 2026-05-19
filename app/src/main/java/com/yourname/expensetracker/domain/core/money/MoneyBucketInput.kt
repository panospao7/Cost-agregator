package com.yourname.expensetracker.domain.core.money

/**
 * Input for bucket-level currency conversion in [MoneyNormalizationEngine].
 */
data class MoneyBucketInput(
    val amount: Double,
    val currency: CurrencyCode,
    val transactionCount: Int,
    val bucketDate: Long? = null,
    val sourceEntityIds: List<Long> = emptyList()
)
