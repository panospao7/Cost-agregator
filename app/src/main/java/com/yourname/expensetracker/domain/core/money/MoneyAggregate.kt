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
 * @see MoneyAmount The single-currency counterpart for non-aggregated values.
 */
data class MoneyAggregate(
    val displayAmount: Double,
    val displayCurrency: CurrencyCode,
    val sourceBuckets: List<MoneyBucket>,
    val conversionFailures: List<ConversionFailure>,
    val isPartial: Boolean = conversionFailures.isNotEmpty(),
    val warningMessage: String? = null
) {

    /** Format the display total with currency symbol. */
    fun formatDisplay(): String = MoneyAmount(displayAmount, displayCurrency).formatDisplay()

    /** The total number of transactions across all buckets. */
    val totalTransactionCount: Int
        get() = sourceBuckets.sumOf { it.transactionCount }

    /** Number of transactions that failed conversion. */
    val failedTransactionCount: Int
        get() = conversionFailures.size

    /** Whether all source buckets are in the same currency (no conversion needed). */
    val isSingleCurrency: Boolean
        get() = sourceBuckets.map { it.currency }.distinct().size <= 1

    companion object {
        /** Create an aggregate from a single currency (no conversion needed). */
        fun singleCurrency(
            amount: Double,
            currency: CurrencyCode,
            transactionCount: Int = 0
        ): MoneyAggregate = MoneyAggregate(
            displayAmount = amount,
            displayCurrency = currency,
            sourceBuckets = listOf(MoneyBucket(currency, amount, transactionCount)),
            conversionFailures = emptyList(),
            isPartial = false
        )

        /** Create an empty aggregate with zero total. */
        fun empty(currency: CurrencyCode): MoneyAggregate = MoneyAggregate(
            displayAmount = 0.0,
            displayCurrency = currency,
            sourceBuckets = emptyList(),
            conversionFailures = emptyList(),
            isPartial = false
        )

        /** Create a partial aggregate with failures. */
        fun partial(
            displayAmount: Double,
            displayCurrency: CurrencyCode,
            sourceBuckets: List<MoneyBucket>,
            failures: List<ConversionFailure>
        ): MoneyAggregate = MoneyAggregate(
            displayAmount = displayAmount,
            displayCurrency = displayCurrency,
            sourceBuckets = sourceBuckets,
            conversionFailures = failures,
            isPartial = true,
            warningMessage = if (failures.isNotEmpty()) {
                "Total excludes ${failures.size} transaction(s) due to missing exchange rates"
            } else null
        )
    }
}
