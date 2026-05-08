package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.currency.CurrencyConverter

/**
 * Assembles a [NormalizedAnalyticsInput] by fetching expenses once, normalizing
 * them to the home currency via [AnalyticsCurrencyNormalizer], and partitioning
 * the result into included and excluded expense lists.
 *
 * This is the canonical entry point for analytics pipelines that need a
 * self-contained, normalized dataset with full currency conversion metadata.
 */
object AnalyticsInputAssembler {

    /**
     * Build a [NormalizedAnalyticsInput] for the given [period].
     *
     * 1. Fetches all expenses in the period via [ExpenseRepository].
     * 2. Normalises to EUR using [AnalyticsCurrencyNormalizer.normalizeExpenses].
     * 3. Maps the normalised result to the [NormalizedAnalyticsInput] contract.
     *
     * @param period            The date range to analyse.
     * @param expenseRepository Repository providing raw [Expense] entities.
     * @param normalizer        Currency normalizer that converts to home currency.
     * @param converter         Currency converter for MoneyAggregate fallback.
     * @return A fully populated [NormalizedAnalyticsInput].
     */
    suspend fun build(
        period: PeriodRange,
        expenseRepository: ExpenseRepository,
        normalizer: AnalyticsCurrencyNormalizer,
        converter: CurrencyConverter
    ): NormalizedAnalyticsInput {
        // 1. Fetch expenses once
        val expenses = expenseRepository.getExpensesBetween(period.startInclusiveMillis, period.endExclusiveMillis)

        // 2. Normalise via AnalyticsCurrencyNormalizer
        val normalized = normalizer.normalizeExpenses(expenses, "EUR")

        // 3. Map normalised expenses to the canonical contract
        val included = normalized.normalizedExpenses.map { normExp ->
            val snap = normExp.snapshot
            NormalizedExpense(
                id = snap.id,
                originalAmount = normExp.originalEffectiveAmount,
                originalCurrency = normExp.originalCurrency,
                normalizedAmount = snap.effectiveAmount, // already converted by the normalizer
                normalizedCurrency = "EUR",
                date = snap.date,
                merchant = snap.merchant,
                merchantKey = snap.merchantKey,
                categoryId = snap.categoryId,
                transactionType = snap.transactionType.name,
                isNotMine = snap.isNotMine,
                // TODO: isSharedExpense is not preserved by AnalyticsCurrencyNormalizer
                // (ExpenseSnapshot lacks this field). When the normalizer is updated to
                // carry through isSharedExpense, wire it here.
                isSharedExpense = false
            )
        }

        // TODO: Build individual ExcludedExpense entries from normalizer warnings.
        // The normalizer's AnalyticsConversionWarning tracks affectedTransactionCount
        // but does not expose individual expense IDs. Consider enhancing the normalizer
        // to return the set of excluded IDs, then map each to ExcludedExpense with
        // reason = ExclusionReason.CONVERSION_FAILED.
        val excludedCount = normalized.excludedCount
        val excluded: List<ExcludedExpense> = emptyList()

        return NormalizedAnalyticsInput(
            period = period,
            homeCurrency = "EUR",
            includedExpenses = included,
            excludedExpenses = excluded,
            dataQuality = AnalyticsDataQuality(
                isPartial = excludedCount > 0 || normalized.hasWarnings,
                excludedCount = excludedCount,
                staleRateCount = 0,
                missingRateCount = normalized.severeWarnings.count {
                    it.type == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
                },
                invalidCurrencyCount = normalized.severeWarnings.count {
                    it.type == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
                },
                conversionWarnings = normalized.warnings.map { it.message }
            )
        )
    }
}
