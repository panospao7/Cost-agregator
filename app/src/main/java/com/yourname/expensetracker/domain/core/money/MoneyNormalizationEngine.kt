package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.DomainTransactionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical normalizer for all currency conversion across the app.
 *
 * Every pipeline (dashboard, analytics, budgets, forecasts, exports) should
 * use this engine rather than ad-hoc inline conversion. This ensures:
 * - Consistent rate-basis selection
 * - No raw foreign-amount fallback on failure
 * - Proper partial/failure tracking via [MoneyAggregate]
 */
@Singleton
class MoneyNormalizationEngine @Inject constructor(
    private val currencyConverter: CurrencyConverter
) {

    /**
     * Normalize a single expense to the home currency.
     */
    suspend fun normalizeExpense(
        expense: Expense,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis = RateBasis.TRANSACTION_DATE
    ): NormalizationResult<NormalizedExpense> {
        val from = expense.currency.uppercase()
        val to = homeCurrency.code

        if (from == to) {
            return NormalizationResult.Included(expense.toNormalizedExpense(homeCurrency, 1.0, rateBasis, ConversionPath.IDENTITY))
        }

        val atMillis = if (rateBasis == RateBasis.TRANSACTION_DATE) expense.date else null
        val outcome = currencyConverter.convertOutcome(
            amount = expense.effectiveAmount,
            fromCurrency = from,
            toCurrency = to,
            rateBasis = rateBasis,
            atMillis = atMillis,
            stalePolicy = StaleRatePolicy.None // historical rates are never "stale"
        )

        return when (outcome) {
            is ConversionOutcome.Converted -> NormalizationResult.Included(
                expense.toNormalizedExpense(homeCurrency, outcome.rateUsed, rateBasis, outcome.conversionPath)
            )
            is ConversionOutcome.Failed -> NormalizationResult.Excluded(
                sourceEntityId = expense.id,
                failure = ConversionFailure(
                    originalAmount = MoneyAmount(expense.effectiveAmount, CurrencyCode(from)),
                    targetCurrency = homeCurrency,
                    reason = outcome.failureType.toFailureReason(),
                    transactionCount = 1
                )
            )
        }
    }

    /**
     * Aggregate a list of expenses into a single [MoneyAggregate].
     */
    suspend fun aggregateExpenses(
        expenses: List<Expense>,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis,
        transactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
    ): MoneyAggregate {
        val filtered = expenses.filter { it.matchesFilter(transactionTypeFilter) }
        if (filtered.isEmpty()) return MoneyAggregate.empty(homeCurrency)

        var total = 0.0
        val failures = mutableListOf<ConversionFailure>()
        val bucketMap = mutableMapOf<CurrencyCode, Pair<Double, Int>>() // currency -> (amount, count)

        for (expense in filtered) {
            val result = normalizeExpense(expense, homeCurrency, rateBasis)
            when (result) {
                is NormalizationResult.Included -> {
                    total += result.value.normalizedAmount
                    val ccy = CurrencyCode(expense.currency.uppercase())
                    val (amt, cnt) = bucketMap.getOrDefault(ccy, 0.0 to 0)
                    bucketMap[ccy] = (amt + expense.effectiveAmount) to (cnt + 1)
                }
                is NormalizationResult.Excluded -> failures.add(result.failure)
            }
        }

        val sourceBuckets = bucketMap.map { (ccy, pair) -> MoneyBucket(ccy, pair.first, pair.second) }

        return MoneyAggregate(
            displayAmount = total,
            displayCurrency = homeCurrency,
            sourceBuckets = sourceBuckets,
            conversionFailures = failures,
            isPartial = failures.isNotEmpty(),
            warningMessage = if (failures.isNotEmpty()) {
                val txCount = failures.sumOf { it.transactionCount }
                "Partial: $txCount transaction(s) could not be converted"
            } else null
        )
    }

    /**
     * Aggregate pre-grouped currency buckets into a single [MoneyAggregate].
     */
    suspend fun aggregateBuckets(
        buckets: List<MoneyBucketInput>,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis,
        bucketDatePolicy: BucketDatePolicy
    ): MoneyAggregate {
        if (buckets.isEmpty()) return MoneyAggregate.empty(homeCurrency)

        var total = 0.0
        val failures = mutableListOf<ConversionFailure>()
        val sourceBuckets = mutableListOf<MoneyBucket>()

        for (bucket in buckets) {
            if (bucket.currency == homeCurrency) {
                total += bucket.amount
                sourceBuckets.add(MoneyBucket(bucket.currency, bucket.amount, bucket.transactionCount))
                continue
            }

            val atMillis = when (bucketDatePolicy) {
                is BucketDatePolicy.RequireBucketDate -> bucket.bucketDate
                is BucketDatePolicy.FixedDate -> bucketDatePolicy.atMillis
                is BucketDatePolicy.Latest -> null
            }

            val effectiveBasis = if (bucketDatePolicy is BucketDatePolicy.Latest) RateBasis.LATEST_AVAILABLE else rateBasis

            val outcome = currencyConverter.convertOutcome(
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
                        reason = outcome.failureType.toFailureReason(),
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
                "Partial: $txCount transaction(s) could not be converted"
            } else null
        )
    }
}

private fun Expense.matchesFilter(filter: TransactionTypeFilter): Boolean = when (filter) {
    TransactionTypeFilter.PURCHASE_ONLY -> transactionType == TransactionType.PURCHASE
    TransactionTypeFilter.INCOME_ONLY -> transactionType == TransactionType.DEPOSIT
    TransactionTypeFilter.TRANSFER_ONLY -> transactionType == TransactionType.TRANSFER
    TransactionTypeFilter.ALL_EXCEPT_TRANSFERS -> transactionType != TransactionType.TRANSFER
    TransactionTypeFilter.ALL_TYPES -> true
}

private fun Expense.toNormalizedExpense(
    homeCurrency: CurrencyCode,
    rateUsed: Double,
    rateBasis: RateBasis,
    path: ConversionPath
): NormalizedExpense {
    val txType = when (transactionType) {
        TransactionType.PURCHASE -> "PURCHASE"
        TransactionType.WITHDRAWAL -> "WITHDRAWAL"
        TransactionType.TRANSFER -> "TRANSFER"
        TransactionType.DEPOSIT -> "DEPOSIT"
        else -> "UNKNOWN"
    }
    return NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = effectiveAmount,
        originalCurrency = currency,
        normalizedAmount = effectiveAmount * rateUsed,
        normalizedCurrency = homeCurrency.code,
        date = date,
        merchant = merchant,
        merchantKey = merchantKey,
        categoryId = categoryId,
        categoryNameSnapshot = null,
        transactionType = txType,
        isNotMine = isNotMine,
        isSharedExpense = false,
        ownershipMode = null,
        source = source
    )
}

private fun ConversionFailureType.toFailureReason(): FailureReason = when (this) {
    ConversionFailureType.MISSING_RATE, ConversionFailureType.MISSING_HISTORICAL_RATE -> FailureReason.MISSING_RATE
    ConversionFailureType.STALE_RATE -> FailureReason.RATE_STALE
    ConversionFailureType.INVALID_SOURCE_CURRENCY, ConversionFailureType.INVALID_TARGET_CURRENCY -> FailureReason.INVALID_AMOUNT
    else -> FailureReason.UNKNOWN
}
