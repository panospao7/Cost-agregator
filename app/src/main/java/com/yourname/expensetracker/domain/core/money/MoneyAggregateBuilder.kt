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
     * CURR-70F-06: This overload uses latest-rate [convertMultiple] internally,
     * so it MUST NOT claim a historical rate basis. Use the typed overload or
     * [MoneyNormalizationEngine] for historical conversions.
     */
    @Deprecated(
        "Use typed fromBuckets with BucketDatePolicy or MoneyNormalizationEngine for historical basis",
        level = DeprecationLevel.WARNING
    )
    suspend fun fromBuckets(
        buckets: List<Pair<Double, String>>,  // amount, currency
        homeCurrency: String,
        converter: CurrencyConverter,
        transactionCounts: List<Int> = emptyList(),
        rateBasis: RateBasis = RateBasis.LATEST_AVAILABLE
    ): MoneyAggregate {
        require(rateBasis == RateBasis.LATEST_AVAILABLE) {
            "Legacy fromBuckets uses latest-rate conversion only. Use typed overload for $rateBasis."
        }
        if (buckets.isEmpty()) {
            return MoneyAggregate.empty(CurrencyCode(homeCurrency), rateBasis)
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
                return MoneyAggregate.singleCurrency(entry.value.first, CurrencyCode(entry.key), entry.value.second, rateBasis)
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
                // VERIFIED (PR-E22 / E1): Warning correctly uses failedTransactionCount
                // (sum of transactionCount across all ConversionFailure entries), NOT
                // conversionFailures.size (bucket count). The variable totalFailedTx
                // equals MoneyAggregate.failedTransactionCount.
                val totalFailedTx = conversionFailures.sumOf { it.transactionCount }
                val bucketCount = conversionFailures.size
                "Total excludes $totalFailedTx transaction(s) across $bucketCount currency bucket(s)"
            } else null,
            rateBasis = rateBasis,
            requestedRateBasis = rateBasis,
            actualRateBasis = rateBasis
        )
    }

    /**
     * Rate-basis-aware overload that accepts typed [MoneyBucketInput] and a [RateBasis].
     * Uses [CurrencyConverter.convertOutcome] for each bucket, respecting the date policy.
     */
    suspend fun fromBuckets(
        buckets: List<MoneyBucketInput>,
        homeCurrency: CurrencyCode,
        converter: CurrencyConverter,
        rateBasis: RateBasis,
        bucketDatePolicy: BucketDatePolicy
    ): MoneyAggregate {
        if (buckets.isEmpty()) return MoneyAggregate.empty(homeCurrency, rateBasis)

        var total = 0.0
        val sourceBuckets = mutableListOf<MoneyBucket>()
        val failures = mutableListOf<ConversionFailure>()

        for (bucket in buckets) {
            if (bucket.currency == homeCurrency) {
                total += bucket.amount
                sourceBuckets.add(MoneyBucket(bucket.currency, bucket.amount, bucket.transactionCount))
                continue
            }

            val atMillis = when (bucketDatePolicy) {
                is BucketDatePolicy.RequireBucketDate -> {
                    // CURR-70F-07: Fail explicitly before calling converter
                    if (bucket.bucketDate == null) {
                        failures.add(ConversionFailure(
                            originalAmount = MoneyAmount(bucket.amount, bucket.currency),
                            targetCurrency = homeCurrency,
                            reason = FailureReason.MISSING_RATE,
                            transactionCount = bucket.transactionCount
                        ))
                        continue
                    }
                    bucket.bucketDate
                }
                is BucketDatePolicy.FixedDate -> bucketDatePolicy.atMillis
                is BucketDatePolicy.Latest -> null
            }
            val effectiveBasis = if (bucketDatePolicy is BucketDatePolicy.Latest) RateBasis.LATEST_AVAILABLE else rateBasis

            val outcome = converter.convertOutcome(
                amount = bucket.amount,
                fromCurrency = bucket.currency.code,
                toCurrency = homeCurrency.code,
                rateBasis = effectiveBasis,
                atMillis = atMillis,
                stalePolicy = StaleRatePolicy.None
            )

            when (outcome) {
                is ConversionOutcome.Converted -> {
                    total += outcome.convertedAmount
                    sourceBuckets.add(MoneyBucket(bucket.currency, bucket.amount, bucket.transactionCount))
                }
                is ConversionOutcome.Failed -> {
                    failures.add(ConversionFailure(
                        originalAmount = MoneyAmount(bucket.amount, bucket.currency),
                        targetCurrency = homeCurrency,
                        reason = when (outcome.failureType) {
                            ConversionFailureType.STALE_RATE -> FailureReason.RATE_STALE
                            ConversionFailureType.MISSING_RATE, ConversionFailureType.MISSING_HISTORICAL_RATE -> FailureReason.MISSING_RATE
                            else -> FailureReason.UNKNOWN
                        },
                        transactionCount = bucket.transactionCount
                    ))
                }
            }
        }

        return MoneyAggregate(
            displayAmount = total,
            displayCurrency = homeCurrency,
            sourceBuckets = sourceBuckets,
            conversionFailures = failures,
            isPartial = failures.isNotEmpty(),
            warningMessage = if (failures.isNotEmpty()) {
                val txCount = failures.sumOf { it.transactionCount }
                "Total excludes $txCount transaction(s) across ${failures.size} currency bucket(s)"
            } else null,
            rateBasis = rateBasis,
            requestedRateBasis = rateBasis,
            actualRateBasis = rateBasis
        )
    }
}
