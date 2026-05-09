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
    val originalEffectiveAmount: Double,
    val originalCurrency: String,
    val normalizedAmount: Double,
    val normalizedCurrency: String,
    val date: Long,
    val merchant: String,
    val merchantKey: String?,
    val categoryId: Long?,
    val categoryNameSnapshot: String?,
    val transactionType: String,
    val isNotMine: Boolean,
    val isSharedExpense: Boolean,
    val ownershipMode: String?,
    val source: String?
)

/**
 * A01: Expanded exclusion reason with warning type and message.
 */
data class ExcludedExpense(
    val id: Long,
    val originalAmount: Double,
    val originalCurrency: String,
    val reason: ExclusionReason,
    val warningType: AnalyticsConversionWarningType? = null,
    val message: String? = null
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
    val conversionWarnings: List<String> = emptyList(),
    val confidencePenalty: Double = 0.0,
    val confidenceMultiplier: Double = 1.0
)

/**
 * A06: Converts a [NormalizedExpense] back to a minimal [ExpenseSnapshot]
 * for consumption by legacy analytics engines that haven't been migrated yet.
 */
fun NormalizedExpense.toExpenseSnapshot(): com.yourname.expensetracker.domain.model.ExpenseSnapshot {
    val txType = when (transactionType) {
        "PURCHASE" -> com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE
        "WITHDRAWAL" -> com.yourname.expensetracker.domain.model.DomainTransactionType.WITHDRAWAL
        "TRANSFER" -> com.yourname.expensetracker.domain.model.DomainTransactionType.TRANSFER
        "DEPOSIT" -> com.yourname.expensetracker.domain.model.DomainTransactionType.DEPOSIT
        else -> com.yourname.expensetracker.domain.model.DomainTransactionType.UNKNOWN
    }
    return com.yourname.expensetracker.domain.model.ExpenseSnapshot(
        id = id,
        amount = originalAmount,
        effectiveAmount = normalizedAmount,
        currency = originalCurrency,
        merchant = merchant,
        merchantKey = merchantKey,
        categoryId = categoryId,
        date = date,
        transactionType = txType,
        isNotMine = isNotMine,
        transferDirection = null,
        notes = null
    )
}
