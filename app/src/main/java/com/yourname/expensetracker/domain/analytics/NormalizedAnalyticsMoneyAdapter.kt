package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateMetadata
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyQuality
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyRow
import com.yourname.expensetracker.domain.core.money.RateBasis

/**
 * Adapts one explicitly-scoped normalized analytics slice to the canonical
 * money aggregate boundary.
 *
 * [dataQuality] must be calculated for the supplied rows and exclusions. In
 * particular, callers must not pass whole-window exclusion counts when this
 * function is aggregating a day/category slice.
 */
enum class ExclusionAttribution {
    TEMPORAL,
    UNKNOWN_DIMENSION
}

object NormalizedAnalyticsMoneyAdapter {

    /**
     * Aggregates one range from a normalized input while deriving exclusion
     * quality from that same range. Unknown-date exclusions remain explicit
     * and make the result unavailable rather than being assigned to a period.
     */
    fun aggregateInputSlice(
        input: NormalizedAnalyticsInput,
        includedExpenses: Iterable<NormalizedExpense>,
        startInclusiveMillis: Long? = null,
        endExclusiveMillis: Long? = null,
        requestedRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
        actualRateBasis: RateBasis = requestedRateBasis,
        displayCurrency: String = input.homeCurrency
    ): MoneyAggregateResult {
        if (CurrencyCode.parse(displayCurrency) != CurrencyCode.parse(input.homeCurrency)) {
            return MoneyAggregateResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis
            )
        }
        val inputPeriod = input.period
        return aggregateSelectedSlice(
            input = input,
            includedExpenses = includedExpenses.filter { expense ->
                (startInclusiveMillis == null || expense.date >= startInclusiveMillis) &&
                    (endExclusiveMillis == null || expense.date < endExclusiveMillis) &&
                    inputPeriod?.contains(expense.date) != false
            },
            includeExcluded = { excluded ->
                val date = excluded.date
                date == null || (
                    (startInclusiveMillis == null || date >= startInclusiveMillis) &&
                        (endExclusiveMillis == null || date < endExclusiveMillis) &&
                        (inputPeriod == null || date >= inputPeriod.startInclusiveMillis) &&
                        (inputPeriod == null || date < inputPeriod.endExclusiveMillis)
                    )
            },
            requestedRateBasis = requestedRateBasis,
            actualRateBasis = actualRateBasis,
            displayCurrency = displayCurrency,
            exclusionAttribution = ExclusionAttribution.TEMPORAL
        )
    }

    /**
     * Aggregates a non-contiguous or dimension-based slice, such as weekdays,
     * time slots, or a category. Exclusions are selected with the same slice
     * predicate; unknown-date exclusions remain unavailable because their owner
     * cannot be proven.
     */
    fun aggregateSelectedSlice(
        input: NormalizedAnalyticsInput,
        includedExpenses: Iterable<NormalizedExpense>,
        includeExcluded: (ExcludedExpense) -> Boolean,
        requestedRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
        actualRateBasis: RateBasis = requestedRateBasis,
        displayCurrency: String = input.homeCurrency,
        exclusionAttribution: ExclusionAttribution = ExclusionAttribution.UNKNOWN_DIMENSION
    ): MoneyAggregateResult {
        if (CurrencyCode.parse(displayCurrency) != CurrencyCode.parse(input.homeCurrency)) {
            return MoneyAggregateResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis
            )
        }
        val exclusions = if (exclusionAttribution == ExclusionAttribution.TEMPORAL) {
            input.excludedExpenses.filter { it.date == null || includeExcluded(it) }
        } else {
            input.excludedExpenses.filter { it.date == null || input.period?.contains(it.date) != false }
        }
        val attributed = if (exclusionAttribution == ExclusionAttribution.TEMPORAL) {
            exclusions.filter { it.date != null }
        } else {
            emptyList()
        }
        val unattributedCount = exclusions.size - attributed.size
        val dataQuality = AnalyticsDataQuality(
            isPartial = exclusions.isNotEmpty(),
            excludedCount = exclusions.size,
            staleRateCount = attributed.count {
                it.warningType == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
            },
            missingRateCount = attributed.count {
                it.warningType == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
            },
            invalidCurrencyCount = attributed.count {
                it.warningType == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
            }
        )
        return aggregateSlice(
            includedExpenses = includedExpenses,
            excludedExpenses = attributed,
            homeCurrency = input.homeCurrency,
            dataQuality = dataQuality,
            unattributedExcludedCount = unattributedCount,
            requestedRateBasis = requestedRateBasis,
            actualRateBasis = actualRateBasis
        )
    }

    /**
     * Aggregates a dimension slice whose excluded rows cannot be attributed to
     * that dimension (for example a category or merchant). Any exclusion makes
     * the result unavailable instead of attaching a whole-window count to the
     * selected slice.
     */
    fun aggregateUnattributedSlice(
        input: NormalizedAnalyticsInput,
        includedExpenses: Iterable<NormalizedExpense>,
        requestedRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
        actualRateBasis: RateBasis = requestedRateBasis,
        displayCurrency: String = input.homeCurrency
    ): MoneyAggregateResult {
        if (CurrencyCode.parse(displayCurrency) != CurrencyCode.parse(input.homeCurrency)) {
            return MoneyAggregateResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis
            )
        }
        val excludedCount = input.excludedExpenses.size
        val excluded = AnalyticsDataQuality(
            isPartial = excludedCount > 0,
            excludedCount = excludedCount,
            staleRateCount = input.excludedExpenses.count {
                it.warningType == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
            },
            missingRateCount = input.excludedExpenses.count {
                it.warningType == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
            },
            invalidCurrencyCount = input.excludedExpenses.count {
                it.warningType == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
            }
        )
        return aggregateSlice(
            includedExpenses = includedExpenses,
            excludedExpenses = emptyList(),
            homeCurrency = input.homeCurrency,
            dataQuality = excluded,
            unattributedExcludedCount = excludedCount,
            requestedRateBasis = requestedRateBasis,
            actualRateBasis = actualRateBasis
        )
    }

    fun aggregateSlice(
        includedExpenses: Iterable<NormalizedExpense>,
        excludedExpenses: Iterable<ExcludedExpense>,
        homeCurrency: String,
        dataQuality: AnalyticsDataQuality,
        unattributedExcludedCount: Int = 0,
        requestedRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
        actualRateBasis: RateBasis = requestedRateBasis
    ): MoneyAggregateResult {
        val included = includedExpenses.toList()
        val excluded = excludedExpenses.toList()
        val includedStaleRateCount = included.count {
            it.qualityWarning == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
        }
        val includedMissingRateCount = included.count {
            it.qualityWarning == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
        }
        val includedInvalidCurrencyCount = included.count {
            it.qualityWarning == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
        }
        val effectiveDataQuality = dataQuality.copy(
            isPartial = dataQuality.isPartial ||
                includedStaleRateCount > 0 ||
                includedMissingRateCount > 0 ||
                includedInvalidCurrencyCount > 0,
            staleRateCount = maxOf(dataQuality.staleRateCount, includedStaleRateCount + excluded.count {
                it.warningType == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE
            }),
            missingRateCount = maxOf(dataQuality.missingRateCount, includedMissingRateCount + excluded.count {
                it.warningType == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
            }),
            invalidCurrencyCount = maxOf(dataQuality.invalidCurrencyCount, includedInvalidCurrencyCount + excluded.count {
                it.warningType == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
            })
        )
        val excludedIds = excluded.map { it.id }.toSet()
        if (included.map { it.id }.distinct().size != included.size ||
            excluded.map { it.id }.distinct().size != excluded.size ||
            included.any { row -> row.id in excludedIds }
        ) {
            return MoneyAggregateResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name,
                requestedRateBasis
            )
        }
        require(unattributedExcludedCount >= 0) { "unattributedExcludedCount must be non-negative" }
        val expectedExcludedCount = excluded.size + unattributedExcludedCount
        if (effectiveDataQuality.excludedCount != expectedExcludedCount) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name,
                requestedRateBasis = requestedRateBasis,
                metadata = MoneyAggregateMetadata(
                    excludedTransactionCount = expectedExcludedCount,
                    staleRateCount = effectiveDataQuality.staleRateCount,
                    missingRateCount = effectiveDataQuality.missingRateCount,
                    invalidCurrencyCount = effectiveDataQuality.invalidCurrencyCount
                )
            )
        }
        if (unattributedExcludedCount > 0) {
            return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                requestedRateBasis = requestedRateBasis,
                metadata = MoneyAggregateMetadata(
                    includedTransactionCount = included.size,
                    excludedTransactionCount = expectedExcludedCount,
                    staleRateCount = effectiveDataQuality.staleRateCount,
                    missingRateCount = effectiveDataQuality.missingRateCount,
                    invalidCurrencyCount = effectiveDataQuality.invalidCurrencyCount
                ),
                warningMessage = "Analytics slice exclusion provenance is unavailable"
            )
        }

        val targetCurrency = CurrencyCode.parse(homeCurrency)
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: return MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY.name,
                requestedRateBasis = requestedRateBasis,
                metadata = MoneyAggregateMetadata(
                    excludedTransactionCount = included.size + excluded.size,
                    invalidCurrencyCount = 1
                )
            )

        val normalizedRows = mutableListOf<NormalizedMoneyRow>()
        for (expense in included) {
            val normalizedCurrency = CurrencyCode.parse(expense.normalizedCurrency)
                ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
                ?: return unavailableForInvalidRows(
                    requestedRateBasis = requestedRateBasis,
                    includedCount = normalizedRows.size + 1,
                    excludedCount = excluded.size
                )
            val sourceCurrency = CurrencyCode.parse(expense.originalCurrency)
                ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
                ?: return unavailableForInvalidRows(
                    requestedRateBasis = requestedRateBasis,
                    includedCount = normalizedRows.size + 1,
                    excludedCount = excluded.size
                )
            normalizedRows += NormalizedMoneyRow(
                amount = expense.normalizedAmount,
                normalizedCurrency = normalizedCurrency,
                sourceCurrency = sourceCurrency,
                rateValidDate = expense.rateValidDate,
                transactionId = expense.id
            )
        }

        val conversionQuality = when {
            effectiveDataQuality.isPartial || effectiveDataQuality.excludedCount > 0 -> ConversionQuality.PARTIAL
            actualRateBasis == RateBasis.PERIOD_MIDPOINT_ESTIMATE -> ConversionQuality.ESTIMATED
            actualRateBasis != requestedRateBasis -> ConversionQuality.MIXED_BASIS
            else -> ConversionQuality.COMPLETE
        }

        return MoneyAggregateBuilder.fromNormalizedRows(
            rows = normalizedRows,
            targetCurrency = targetCurrency,
            quality = NormalizedMoneyQuality(
                excludedTransactionCount = effectiveDataQuality.excludedCount,
                staleRateCount = effectiveDataQuality.staleRateCount,
                missingRateCount = effectiveDataQuality.missingRateCount,
                invalidCurrencyCount = effectiveDataQuality.invalidCurrencyCount,
                conversionQuality = conversionQuality,
                warningMessage = if (effectiveDataQuality.isPartial || effectiveDataQuality.excludedCount > 0) {
                    "Analytics slice is partial"
                } else {
                    null
                }
            ),
            requestedRateBasis = requestedRateBasis,
            actualRateBasis = actualRateBasis
        )
    }

    private fun unavailableForInvalidRows(
        requestedRateBasis: RateBasis,
        includedCount: Int,
        excludedCount: Int
    ): MoneyAggregateResult.Unavailable = MoneyAggregateResult.Unavailable(
        reason = MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
        requestedRateBasis = requestedRateBasis,
        metadata = MoneyAggregateMetadata(
            excludedTransactionCount = includedCount + excludedCount,
            invalidCurrencyCount = 1
        )
    )
}
