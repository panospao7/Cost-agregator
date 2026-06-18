package com.yourname.expensetracker.domain.core.money

/**
 * ★ APPROVED TYPE ★ Aggregated money total across multiple currencies,
 * converted to a single display currency.
 *
 * This is the **single approved result type** for all financial aggregation
 * in the app. It replaces raw `Double` totals that silently mixed currencies.
 *
 * Key design decisions:
 * - [displayAmount] and [displayCurrency] are the converted total in a single currency
 * - [sourceBuckets] preserves the per-currency breakdown for transparency
 * - [conversionFailures] lists any currencies that could not be converted
 * - [isPartial] is true when some currencies were excluded due to conversion failures
 *
 * UI consumers should:
 * 1. Display [displayAmount] with [displayCurrency] (use [formatDisplay] for convenience)
 * 2. If [isPartial], show a warning like "Total excludes N transactions due to missing rates"
 * 3. Optionally show [sourceBuckets] for per-currency detail
 *
 * Use [failedTransactionCount] (sum of transaction counts across all failures)
 * and [failedBucketCount] (number of failure entries) for accurate diagnostics.
 *
 * @see MoneyAmount The single-currency counterpart for non-aggregated values.
 */
data class MoneyAggregate(
    val displayAmount: Double,
    val displayCurrency: CurrencyCode,
    val sourceBuckets: List<MoneyBucket>,
    val conversionFailures: List<ConversionFailure>,
    val isPartial: Boolean = conversionFailures.isNotEmpty(),
    val warningMessage: String? = null,
    val rateBasis: RateBasis = RateBasis.LATEST_AVAILABLE,
    val requestedRateBasis: RateBasis = rateBasis,
    val actualRateBasis: RateBasis = rateBasis,
    val conversionQuality: ConversionQuality = if (conversionFailures.isEmpty()) ConversionQuality.COMPLETE else ConversionQuality.PARTIAL,
    val metadata: MoneyAggregateMetadata = MoneyAggregateMetadata()
) {

    init {
        require(displayAmount.isFinite()) { "displayAmount must be finite" }
    }

    /** Format the display total with currency symbol. */
    fun formatDisplay(): String = MoneyAmount(displayAmount, displayCurrency).formatDisplay()

    /** The total number of transactions across all buckets. */
    val totalTransactionCount: Int
        get() = sourceBuckets.sumOf { it.transactionCount }

    /**
     * Total number of transactions affected by conversion failures.
     * This sums [ConversionFailure.transactionCount] across all failure entries.
     * For the count of failure entries (buckets) use [failedBucketCount].
     */
    val failedTransactionCount: Int
        get() = conversionFailures.sumOf { it.transactionCount }

    /** Number of source buckets that failed conversion (same as [conversionFailures].size). */
    val failedBucketCount: Int
        get() = conversionFailures.size

    /** Whether all source buckets are in the same currency (no conversion needed). */
    val isSingleCurrency: Boolean
        get() = sourceBuckets.map { it.currency }.distinct().size <= 1

    companion object {
        /** Create an aggregate from a single currency (no conversion needed). */
        fun singleCurrency(
            amount: Double,
            currency: CurrencyCode,
            transactionCount: Int = 0,
            rateBasis: RateBasis = RateBasis.IDENTITY
        ): MoneyAggregate = MoneyAggregate(
            displayAmount = amount,
            displayCurrency = currency,
            sourceBuckets = listOf(MoneyBucket(currency, amount, transactionCount)),
            conversionFailures = emptyList(),
            isPartial = false,
            rateBasis = rateBasis,
            requestedRateBasis = rateBasis,
            actualRateBasis = RateBasis.IDENTITY
        )

        /** Create an empty aggregate with zero total. */
        fun empty(currency: CurrencyCode, rateBasis: RateBasis = RateBasis.LATEST_AVAILABLE): MoneyAggregate = MoneyAggregate(
            displayAmount = 0.0,
            displayCurrency = currency,
            sourceBuckets = emptyList(),
            conversionFailures = emptyList(),
            isPartial = false,
            rateBasis = rateBasis,
            requestedRateBasis = rateBasis,
            actualRateBasis = rateBasis
        )

        /** Create a partial aggregate with failures. */
        fun partial(
            displayAmount: Double,
            displayCurrency: CurrencyCode,
            sourceBuckets: List<MoneyBucket>,
            failures: List<ConversionFailure>,
            rateBasis: RateBasis = RateBasis.LATEST_AVAILABLE
        ): MoneyAggregate = MoneyAggregate(
            displayAmount = displayAmount,
            displayCurrency = displayCurrency,
            sourceBuckets = sourceBuckets,
            conversionFailures = failures,
            isPartial = true,
            warningMessage = if (failures.isNotEmpty()) {
                val transactionCount = failures.sumOf { it.transactionCount }
                "Partial: $transactionCount transaction(s) from ${failures.size} currency/currencies could not be converted"
            } else null,
            rateBasis = rateBasis,
            requestedRateBasis = rateBasis,
            actualRateBasis = rateBasis
        )
    }
}
