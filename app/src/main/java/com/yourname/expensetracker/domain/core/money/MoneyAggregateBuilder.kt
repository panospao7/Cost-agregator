package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import timber.log.Timber

/**
 * Common helper for building MoneyAggregate from per-currency buckets.
 * Fixes: single non-home currency conversion, stale-rate vs missing-rate mapping.
 *
 * All 5 engine methods (WarrantyTrackerRepository, SubscriptionManagerEngine,
 * InvestmentTracker, TaxEstimator, AnalyticsRepository) use this builder to
 * ensure consistent conversion logic and failure handling.
 */
object MoneyAggregateBuilder {

    fun fromSourceCurrencyAmounts(
        amounts: Iterable<Double>,
        sourceCurrency: String?
    ): MoneyAggregateResult {
        val currency = CurrencyCode.parse(sourceCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
        var count = 0
        var total = 0.0
        var invalidAmount = false
        for (amount in amounts) {
            count++
            if (!amount.isFinite()) {
                invalidAmount = true
            } else if (!invalidAmount) {
                total += amount
                invalidAmount = !total.isFinite()
            }
        }
        if (currency == null) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
                requestedRateBasis = RateBasis.IDENTITY,
                metadata = MoneyAggregateMetadata(
                    excludedTransactionCount = count,
                    invalidCurrencyCount = if (count == 0) 0 else 1
                )
            )
        }
        if (invalidAmount) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name,
                requestedRateBasis = RateBasis.IDENTITY,
                metadata = MoneyAggregateMetadata(excludedTransactionCount = count)
            )
        }
        val aggregate = if (count == 0) {
            MoneyAggregate.empty(currency, RateBasis.IDENTITY)
        } else {
            MoneyAggregate.singleCurrency(total, currency, count, RateBasis.IDENTITY)
        }
        return MoneyAggregateResult.Available(
            aggregate.copy(metadata = MoneyAggregateMetadata(includedTransactionCount = count))
        )
    }

    /**
     * Builds a typed aggregate for values that are already normalized to the
     * requested home currency. Callers must use this boundary instead of
     * performing raw collection sums in consumer engines.
     */
    fun fromHomeCurrencyAmounts(
        amounts: Iterable<Double>,
        homeCurrency: String
    ): MoneyAggregate {
        val currency = CurrencyCode.parse(homeCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: throw IllegalArgumentException("Unsupported home currency")
        var total = 0.0
        var count = 0
        for (amount in amounts) {
            require(amount.isFinite()) { "Normalized amount must be finite" }
            total += amount
            count++
        }
        return if (count == 0) {
            MoneyAggregate.empty(currency, RateBasis.IDENTITY)
        } else {
            MoneyAggregate.singleCurrency(total, currency, count, RateBasis.IDENTITY)
        }
    }

    /**
     * Aggregates values that are already normalized to [targetCurrency].
     *
     * This operation never invokes a converter. A row with a different
     * normalized currency is rejected instead of being converted implicitly,
     * which prevents double conversion and mixed-currency totals. Exclusion
     * and quality metadata must describe the same slice as [rows].
     */
    fun fromNormalizedRows(
        rows: Iterable<NormalizedMoneyRow>,
        targetCurrency: CurrencyCode,
        quality: NormalizedMoneyQuality = NormalizedMoneyQuality(),
        requestedRateBasis: RateBasis = RateBasis.IDENTITY,
        actualRateBasis: RateBasis = requestedRateBasis
    ): MoneyAggregateResult {
        if (targetCurrency !in CurrencyCode.ALL_SUPPORTED) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis = requestedRateBasis,
                metadata = MoneyAggregateMetadata(
                    excludedTransactionCount = quality.excludedTransactionCount,
                    staleRateCount = quality.staleRateCount,
                    missingRateCount = quality.missingRateCount,
                    invalidCurrencyCount = quality.invalidCurrencyCount
                )
            )
        }

        var rowCount = 0
        var includedTransactionCount = 0
        var total = 0.0
        var invalidAmount = false
        var invalidCounts = false
        var currencyMismatchCount = 0
        val transactionIds = mutableSetOf<Long>()
        val sourceCurrencies = linkedSetOf<CurrencyCode>()
        val rateValidDates = sortedSetOf<Long>()

        for (row in rows) {
            rowCount++
            if (row.transactionCount != 1 ||
                row.transactionId?.let { !transactionIds.add(it) } == true
            ) {
                invalidCounts = true
            } else {
                includedTransactionCount += row.transactionCount
            }
            if (row.normalizedCurrency != targetCurrency) {
                currencyMismatchCount += row.transactionCount.coerceAtLeast(0)
            }
            if (!row.amount.isFinite()) {
                invalidAmount = true
            } else if (!invalidAmount) {
                total += row.amount
                invalidAmount = !total.isFinite()
            }
            row.sourceCurrency?.let(sourceCurrencies::add)
            row.rateValidDate?.let(rateValidDates::add)
        }

        val metadata = MoneyAggregateMetadata(
            includedTransactionCount = includedTransactionCount,
            excludedTransactionCount = quality.excludedTransactionCount,
            staleRateCount = quality.staleRateCount,
            missingRateCount = quality.missingRateCount,
            invalidCurrencyCount = quality.invalidCurrencyCount + currencyMismatchCount,
            latestRateValidDate = rateValidDates.maxOrNull(),
            oldestRateValidDate = rateValidDates.minOrNull(),
            sourceCurrencyCodes = sourceCurrencies,
            rateValidDates = rateValidDates
        )

        if (invalidCounts) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name,
                requestedRateBasis = requestedRateBasis,
                metadata = metadata
            )
        }
        if (currencyMismatchCount > 0 || quality.invalidCurrencyCount > 0 && rowCount == 0) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
                requestedRateBasis = requestedRateBasis,
                metadata = metadata
            )
        }
        if (invalidAmount) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name,
                requestedRateBasis = requestedRateBasis,
                metadata = metadata
            )
        }

        val effectiveQuality = when {
            quality.conversionQuality == ConversionQuality.UNAVAILABLE -> ConversionQuality.UNAVAILABLE
            includedTransactionCount == 0 && quality.excludedTransactionCount > 0 -> ConversionQuality.UNAVAILABLE
            quality.excludedTransactionCount > 0 || quality.conversionQuality == ConversionQuality.PARTIAL ->
                ConversionQuality.PARTIAL
            else -> quality.conversionQuality
        }
        if (effectiveQuality == ConversionQuality.UNAVAILABLE) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                requestedRateBasis = requestedRateBasis,
                metadata = metadata,
                warningMessage = quality.warningMessage
                    ?: MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name
            )
        }

        val aggregate = if (includedTransactionCount == 0) {
            MoneyAggregate.empty(targetCurrency, actualRateBasis).copy(
                requestedRateBasis = requestedRateBasis,
                actualRateBasis = actualRateBasis,
                rateBasis = actualRateBasis,
                conversionQuality = effectiveQuality,
                warningMessage = quality.warningMessage,
                metadata = metadata
            )
        } else {
            MoneyAggregate(
                displayAmount = total,
                displayCurrency = targetCurrency,
                sourceBuckets = listOf(
                    MoneyBucket(targetCurrency, total, includedTransactionCount)
                ),
                conversionFailures = emptyList(),
                isPartial = effectiveQuality != ConversionQuality.COMPLETE,
                warningMessage = quality.warningMessage,
                rateBasis = actualRateBasis,
                requestedRateBasis = requestedRateBasis,
                actualRateBasis = actualRateBasis,
                conversionQuality = effectiveQuality,
                metadata = metadata
            )
        }
        return MoneyAggregateResult.Available(aggregate.copy(metadata = metadata))
    }

    /** NEW-P5-009: controlled warning text for count/bucket list mismatches. */
    private const val NEW_P5_009_WARNING =
        "Transaction counts incomplete: count list does not match bucket list; per-bucket counts are approximate"

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
            return MoneyAggregate.empty(CurrencyCode.parse(homeCurrency) ?: CurrencyCode.EUR, rateBasis)
        }

        // Group by currency
        // NEW-P5-009: A counts/buckets size mismatch in EITHER direction is
        // count-integrity damage — shorter means buckets were silently defaulted
        // to 0; longer means counts exist that have no bucket. In both cases the
        // aggregate is marked partial with countsIncomplete metadata (controlled
        // counts in the warning; no negative sentinels, no fabricated counts).
        // An EMPTY counts list is the caller's explicit count-agnostic opt-out
        // (the parameter default): counts are unknown by design, not damaged, so
        // it is NOT flagged — pinning that semantic keeps every count-agnostic
        // caller's behavior unchanged (consumer changes are out of slice scope).
        val countsIncomplete = transactionCounts.isNotEmpty() && transactionCounts.size != buckets.size
        if (countsIncomplete) {
            Timber.w(
                "MoneyAggregateBuilder size mismatch: %d buckets but %d transactionCounts. " +
                    "Count integrity incomplete (countsIncomplete=true).",
                buckets.size, transactionCounts.size
            )
        }
        val byCurrency = mutableMapOf<String, Pair<Double, Int>>()
        buckets.forEachIndexed { index, (amount, currency) ->
            val ccy = currency.uppercase()
            val existing = byCurrency.getOrDefault(ccy, Pair(0.0, 0))
            val count = transactionCounts.getOrElse(index) { 0 }
            byCurrency[ccy] = Pair(existing.first + amount, existing.second + count)
        }

        val sourceBuckets = byCurrency.map { (ccy, pair) ->
            MoneyBucket(CurrencyCode.parse(ccy) ?: CurrencyCode.EUR, pair.first, pair.second)
        }

        // If all same currency and it's home currency, return directly
        if (byCurrency.size == 1) {
            val entry = byCurrency.entries.first()
            if (entry.key == homeCurrency.uppercase()) {
                return MoneyAggregate.singleCurrency(
                    entry.value.first,
                    CurrencyCode.parse(entry.key) ?: CurrencyCode.EUR,
                    entry.value.second,
                    rateBasis
                ).let { aggregate ->
                    if (!countsIncomplete) aggregate
                    else aggregate.copy(
                        isPartial = true,
                        warningMessage = NEW_P5_009_WARNING,
                        metadata = aggregate.metadata.copy(countsIncomplete = true)
                    )
                }
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

        // NEW-P5-009: converted amounts stay trustworthy; the counts do not.
        // Combine conversion-failure warnings with the count-integrity warning.
        val conversionWarning = if (conversionFailures.isNotEmpty()) {
            // VERIFIED (PR-E22 / E1): Warning correctly uses failedTransactionCount
            // (sum of transactionCount across all ConversionFailure entries), NOT
            // conversionFailures.size (bucket count). The variable totalFailedTx
            // equals MoneyAggregate.failedTransactionCount.
            val totalFailedTx = conversionFailures.sumOf { it.transactionCount }
            val bucketCount = conversionFailures.size
            "Total excludes $totalFailedTx transaction(s) across $bucketCount currency bucket(s)"
        } else null

        return MoneyAggregate(
            displayAmount = conversionResult.total,
            displayCurrency = CurrencyCode.parse(homeCurrency) ?: CurrencyCode.EUR,
            sourceBuckets = sourceBuckets,
            conversionFailures = conversionFailures,
            isPartial = conversionFailures.isNotEmpty() || countsIncomplete,
            warningMessage = if (countsIncomplete) {
                if (conversionWarning != null) "$NEW_P5_009_WARNING $conversionWarning"
                else NEW_P5_009_WARNING
            } else conversionWarning,
            rateBasis = rateBasis,
            requestedRateBasis = rateBasis,
            actualRateBasis = rateBasis,
            conversionQuality = when {
                conversionFailures.isEmpty() -> ConversionQuality.COMPLETE
                conversionFailures.size == byCurrency.size -> ConversionQuality.UNAVAILABLE
                else -> ConversionQuality.PARTIAL
            },
            metadata = MoneyAggregateMetadata(countsIncomplete = countsIncomplete)
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
        var includedCount = 0
        var excludedCount = 0
        var successfulBucketCount = 0

        for (bucket in buckets) {
            sourceBuckets.add(MoneyBucket(bucket.currency, bucket.amount, bucket.transactionCount))
            if (bucket.currency == homeCurrency) {
                total += bucket.amount
                includedCount += bucket.transactionCount
                successfulBucketCount++
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
                        excludedCount += bucket.transactionCount
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
                stalePolicy = StaleRatePolicy.forBasis(effectiveBasis)
            )

            when (outcome) {
                is ConversionOutcome.Converted -> {
                    total += outcome.convertedAmount
                    includedCount += bucket.transactionCount
                    successfulBucketCount++
                }
                is ConversionOutcome.Failed -> {
                    failures.add(ConversionFailure(
                        originalAmount = MoneyAmount(bucket.amount, bucket.currency),
                        targetCurrency = homeCurrency,
                        reason = when (outcome.failureType) {
                            ConversionFailureType.STALE_RATE -> FailureReason.RATE_STALE
                            ConversionFailureType.MISSING_RATE, ConversionFailureType.MISSING_HISTORICAL_RATE -> FailureReason.MISSING_RATE
                            ConversionFailureType.INVALID_SOURCE_CURRENCY, ConversionFailureType.INVALID_TARGET_CURRENCY -> FailureReason.INVALID_CURRENCY
                            else -> FailureReason.UNKNOWN
                        },
                        transactionCount = bucket.transactionCount
                    ))
                    excludedCount += bucket.transactionCount
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
            actualRateBasis = rateBasis,
            conversionQuality = when {
                failures.isEmpty() -> ConversionQuality.COMPLETE
                successfulBucketCount == 0 -> ConversionQuality.UNAVAILABLE
                else -> ConversionQuality.PARTIAL
            },
            metadata = MoneyAggregateMetadata(
                includedTransactionCount = includedCount,
                excludedTransactionCount = excludedCount,
                missingRateCount = failures.count { it.reason == FailureReason.MISSING_RATE },
                staleRateCount = failures.count { it.reason == FailureReason.RATE_STALE },
                invalidCurrencyCount = failures.count {
                    it.reason == FailureReason.INVALID_AMOUNT || it.reason == FailureReason.INVALID_CURRENCY
                }
            )
        )
    }
}
