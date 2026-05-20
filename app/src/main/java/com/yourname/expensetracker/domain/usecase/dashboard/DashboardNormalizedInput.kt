package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.RateBasis

/**
 * CURR-9A6-02: Typed result for dashboard normalization.
 * Unavailable state is explicit — no fake EUR money container.
 */
sealed interface DashboardNormalizedInputResult {
    data class Available(val input: DashboardNormalizedInput) : DashboardNormalizedInputResult
    data class Unavailable(
        val reason: String,
        val periodStart: Long,
        val periodEnd: Long
    ) : DashboardNormalizedInputResult
}

/**
 * CURR-70F-13: Canonical normalized input for all dashboard widgets.
 *
 * All dashboard widgets should consume from this single source to ensure
 * basis-consistent totals. No widget should recalculate from raw Expense.effectiveAmount.
 */
data class DashboardNormalizedInput(
    val homeCurrency: CurrencyCode,
    val periodStart: Long,
    val periodEnd: Long,
    val normalizedExpenses: List<NormalizedExpense>,
    val periodAggregate: MoneyAggregate,
    val todayAggregate: MoneyAggregate,
    val weekAggregate: MoneyAggregate,
    val monthAggregate: MoneyAggregate,
    val previousMonthAggregate: MoneyAggregate? = null,
    val categoryAggregates: Map<Long?, MoneyAggregate>,
    val depositAggregate: MoneyAggregate? = null,
    val dataQuality: CurrencyDataQuality
)

/**
 * Quality metadata about the currency normalization applied to dashboard data.
 */
data class CurrencyDataQuality(
    val isPartial: Boolean,
    val conversionQuality: ConversionQuality,
    val missingRateCount: Int,
    val staleRateCount: Int,
    val invalidCurrencyCount: Int,
    val excludedTransactionCount: Int,
    val warningMessage: String?,
    val requestedRateBasis: RateBasis,
    val actualRateBasis: RateBasis
) {
    companion object {
        fun fromAggregate(aggregate: MoneyAggregate, requestedBasis: RateBasis) = CurrencyDataQuality(
            isPartial = aggregate.isPartial,
            conversionQuality = aggregate.conversionQuality,
            missingRateCount = aggregate.metadata.missingRateCount,
            staleRateCount = aggregate.metadata.staleRateCount,
            invalidCurrencyCount = aggregate.metadata.invalidCurrencyCount,
            excludedTransactionCount = aggregate.metadata.excludedTransactionCount,
            warningMessage = aggregate.warningMessage,
            requestedRateBasis = requestedBasis,
            actualRateBasis = aggregate.actualRateBasis
        )

        fun fromAggregates(aggregates: Collection<MoneyAggregate>, requestedBasis: RateBasis = RateBasis.TRANSACTION_DATE): CurrencyDataQuality {
            if (aggregates.isEmpty()) return UNAVAILABLE
            return aggregates.map { fromAggregate(it, requestedBasis) }.reduce { a, b ->
                CurrencyDataQuality(
                    isPartial = a.isPartial || b.isPartial,
                    conversionQuality = if (a.conversionQuality == ConversionQuality.COMPLETE && b.conversionQuality == ConversionQuality.COMPLETE) ConversionQuality.COMPLETE else ConversionQuality.PARTIAL,
                    missingRateCount = a.missingRateCount + b.missingRateCount,
                    staleRateCount = a.staleRateCount + b.staleRateCount,
                    invalidCurrencyCount = a.invalidCurrencyCount + b.invalidCurrencyCount,
                    excludedTransactionCount = a.excludedTransactionCount + b.excludedTransactionCount,
                    warningMessage = listOfNotNull(a.warningMessage, b.warningMessage).firstOrNull(),
                    requestedRateBasis = a.requestedRateBasis,
                    actualRateBasis = a.actualRateBasis
                )
            }
        }

        val UNAVAILABLE = CurrencyDataQuality(
            isPartial = true,
            conversionQuality = ConversionQuality.UNAVAILABLE,
            missingRateCount = 0,
            staleRateCount = 0,
            invalidCurrencyCount = 0,
            excludedTransactionCount = 0,
            warningMessage = "Home currency unavailable",
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = RateBasis.TRANSACTION_DATE
        )
    }
}

/**
 * Currency quality info for UI display on individual widgets.
 */
data class CurrencyQualityUi(
    val isPartial: Boolean,
    val isUnavailable: Boolean = false,
    val quality: ConversionQuality,
    val warningMessage: String?
) {
    companion object {
        fun from(dataQuality: CurrencyDataQuality) = CurrencyQualityUi(
            isPartial = dataQuality.isPartial,
            isUnavailable = dataQuality.conversionQuality == ConversionQuality.UNAVAILABLE,
            quality = dataQuality.conversionQuality,
            warningMessage = dataQuality.warningMessage
        )

        fun unavailable(reason: String) = CurrencyQualityUi(
            isPartial = true,
            isUnavailable = true,
            quality = ConversionQuality.UNAVAILABLE,
            warningMessage = reason
        )
    }
}
