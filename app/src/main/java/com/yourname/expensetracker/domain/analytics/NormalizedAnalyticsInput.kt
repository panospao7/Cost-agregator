package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.time.PeriodRange

data class NormalizedAnalyticsInput(
    val period: PeriodRange? = null,
    val homeCurrency: String = "EUR",
    val includedExpenses: List<NormalizedExpense> = emptyList(),
    val excludedExpenses: List<ExcludedExpense> = emptyList(),
    val dataQuality: AnalyticsDataQuality = AnalyticsDataQuality()
)

data class NormalizedExpense(
    val id: Long,
    val originalAmount: Double,
    val originalCurrency: String,
    val normalizedAmount: Double,
    val normalizedCurrency: String,
    val date: Long,
    val merchant: String,
    val merchantKey: String?,
    val categoryId: Long?,
    val transactionType: String,
    val isNotMine: Boolean,
    val isSharedExpense: Boolean
)

data class ExcludedExpense(
    val id: Long,
    val originalAmount: Double,
    val originalCurrency: String,
    val reason: ExclusionReason
)

enum class ExclusionReason {
    CONVERSION_FAILED, INVALID_CURRENCY, NOT_SPENDING, IS_NOT_MINE
}

data class AnalyticsDataQuality(
    val isPartial: Boolean = false,
    val excludedCount: Int = 0,
    val staleRateCount: Int = 0,
    val missingRateCount: Int = 0,
    val invalidCurrencyCount: Int = 0,
    val conversionWarnings: List<String> = emptyList()
)
