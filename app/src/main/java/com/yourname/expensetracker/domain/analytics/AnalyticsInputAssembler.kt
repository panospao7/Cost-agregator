package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.toCurrencyCodeOrNull
import com.yourname.expensetracker.domain.core.time.PeriodRange

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
     * 1. Fetches all expenses in the period via [ExpenseRepository.getExpensesBetween].
     * 2. Normalises to [homeCurrency] using [AnalyticsCurrencyNormalizer.normalizeExpenses].
     * 3. Maps the normalised result to the [NormalizedAnalyticsInput] contract,
     *    computing excluded expenses from the difference between the raw expense set
     *    and the successfully normalised expense set.
     *
     * @param period            The date range to analyse.
     * @param homeCurrency      The user's home currency code (e.g. "EUR", "USD"),
     *                          obtained from [CurrencySettingsRepository.homeCurrency].
     * @param expenseRepository Repository providing raw [Expense] entities.
     * @param normalizer        Currency normalizer that converts to home currency.
     * @return A fully populated [NormalizedAnalyticsInput].
     */
    suspend fun build(
        period: PeriodRange,
        homeCurrency: String,
        expenseRepository: ExpenseRepository,
        normalizer: AnalyticsCurrencyNormalizer
    ): NormalizedAnalyticsInput {
        // 1. Fetch expenses once
        val expenses = expenseRepository.getExpensesBetween(
            period.startInclusiveMillis,
            period.endExclusiveMillis
        )

        // 2. Normalise via AnalyticsCurrencyNormalizer
        val result = normalizer.normalizeExpenses(expenses, homeCurrency)

        // 3. Build a set of IDs that were successfully normalised
        val normalizedIds = result.normalizedExpenses.mapTo(mutableSetOf()) { it.snapshot.id }

        // 4. Map normalised expenses to the canonical contract
        val included = result.normalizedExpenses.map { normExp ->
            val snap = normExp.snapshot
            NormalizedExpense(
                id = snap.id,
                originalAmount = normExp.originalEffectiveAmount,
                originalCurrency = normExp.originalCurrency,
                normalizedAmount = snap.effectiveAmount, // already converted by the normalizer
                normalizedCurrency = homeCurrency,
                date = snap.date,
                merchant = snap.merchant,
                merchantKey = snap.merchantKey,
                categoryId = snap.categoryId,
                transactionType = snap.transactionType.name,
                isNotMine = snap.isNotMine,
                isSharedExpense = false // TODO: populate when ExpenseSnapshot carries isSharedExpense
            )
        }

        // 5. Build excluded expenses from the set of raw expenses whose IDs are NOT in
        //    the successfully normalised set. Determine the exclusion reason by checking
        //    whether the original currency code is parseable.
        val expenseById = expenses.associateBy { it.id }
        val excluded: List<ExcludedExpense> = expenses
            .asSequence()
            .filter { it.id !in normalizedIds }
            .map { exp ->
                val reason = if (exp.currency.toCurrencyCodeOrNull() == null) {
                    ExclusionReason.INVALID_CURRENCY
                } else {
                    ExclusionReason.CONVERSION_FAILED
                }
                ExcludedExpense(
                    id = exp.id,
                    originalAmount = exp.effectiveAmount,
                    originalCurrency = exp.currency,
                    reason = reason
                )
            }
            .toList()

        val excludedCount = result.excludedCount

        return NormalizedAnalyticsInput(
            period = period,
            homeCurrency = homeCurrency,
            includedExpenses = included,
            excludedExpenses = excluded,
            dataQuality = AnalyticsDataQuality(
                isPartial = excludedCount > 0 || result.hasWarnings,
                excludedCount = excludedCount,
                staleRateCount = 0,
                missingRateCount = result.severeWarnings.count {
                    it.type == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
                },
                invalidCurrencyCount = result.severeWarnings.count {
                    it.type == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
                },
                conversionWarnings = result.warnings.map { it.message }
            )
        )
    }
}
