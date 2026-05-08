package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.domain.currency.CurrencyConverter

/**
 * Common helper for building MoneyAggregate from per-currency buckets.
 * Fixes: single non-home currency conversion, stale-rate vs missing-rate mapping.
 *
 * All 5 engine methods (WarrantyTrackerRepository, SubscriptionManagerEngine,
 * InvestmentTracker, TaxEstimator, AnalyticsRepository) use this builder to
 * ensure consistent conversion logic and failure handling.
 */
object MoneyAggregateBuilder {

    /**
     * Builds a MoneyAggregate from per-currency buckets, converting to home currency.
     *
     * Rules:
     * - empty → home currency empty aggregate
     * - same as home → no conversion needed
     * - single non-home → convert to home
     * - mixed → convert each bucket via CurrencyConverter
     *
     * Failures correctly map [FailedConversion.STALE_RATE] to [FailureReason.RATE_STALE]
     * and [FailedConversion.MISSING_RATE] to [FailureReason.MISSING_RATE].
     */
    suspend fun fromBuckets(
        buckets: List<Pair<Double, String>>,  // amount, currency
        homeCurrency: String,
        converter: CurrencyConverter,
        transactionCounts: List<Int> = emptyList()
    ): MoneyAggregate {
        if (buckets.isEmpty()) {
            return MoneyAggregate.empty(CurrencyCode(homeCurrency))
        }

        // Group by currency
        val byCurrency = mutableMapOf<String, Pair<Double, Int>>()
        buckets.forEachIndexed { index, (amount, currency) ->
            val ccy = currency.uppercase()
            val existing = byCurrency.getOrDefault(ccy, Pair(0.0, 0))
            val count = transactionCounts.getOrElse(index) { 0 }
            byCurrency[ccy] = Pair(existing.first + amount, existing.second + count)
        }

        val sourceBuckets = byCurrency.map { (ccy, pair) ->
            MoneyBucket(CurrencyCode(ccy), pair.first, pair.second)
        }

        // If all same currency and it's home currency, return directly
        if (byCurrency.size == 1) {
            val entry = byCurrency.entries.first()
            if (entry.key == homeCurrency.uppercase()) {
                return MoneyAggregate.singleCurrency(entry.value.first, CurrencyCode(entry.key), entry.value.second)
            }
        }

        // Convert to home currency (handles single non-home + mixed cases)
        val amounts = byCurrency.map { (ccy, pair) -> Pair(pair.first, ccy) }
        val conversionResult = converter.convertMultiple(amounts, homeCurrency)

        // Uses toConversionFailure() which correctly maps STALE_RATE vs MISSING_RATE
        val conversionFailures = conversionResult.failedConversions.map { failure ->
            val txCount = byCurrency[failure.originalCurrency.uppercase()]?.second ?: 0
            failure.toConversionFailure().copy(transactionCount = txCount)
        }

        return MoneyAggregate(
            displayAmount = conversionResult.total,
            displayCurrency = CurrencyCode(homeCurrency),
            sourceBuckets = sourceBuckets,
            conversionFailures = conversionFailures,
            isPartial = conversionFailures.isNotEmpty(),
            warningMessage = if (conversionFailures.isNotEmpty()) {
                val totalFailedTx = conversionFailures.sumOf { it.transactionCount }
                val bucketCount = conversionFailures.size
                "Total excludes $totalFailedTx transaction(s) across $bucketCount currency bucket(s)"
            } else null
        )
    }
}
