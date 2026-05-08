package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
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
     * 2. Normalises to [homeCurrency] using [AnalyticsCurrencyNormalizer.normalizeExpenses].
     * 3. Maps the normalised result to the [NormalizedAnalyticsInput] contract.
     *
     * @param period            The date range to analyse.
     * @param homeCurrency      The user's home currency code (e.g. "EUR", "USD"),
     *                          obtained from [CurrencySettingsRepository.homeCurrency].
     * @param expenseRepository Repository providing raw [Expense] entities.
     * @param normalizer        Currency normalizer that converts to home currency.
     * @param currencyConverter Converter for direct per-expense conversion fallback
     *                          (used when building ExcludedExpense entries with IDs).
     * @return A fully populated [NormalizedAnalyticsInput].
     */
    suspend fun build(
        period: PeriodRange,
        homeCurrency: String,
        expenseRepository: ExpenseRepository,
        normalizer: AnalyticsCurrencyNormalizer,
        currencyConverter: CurrencyConverter? = null
    ): NormalizedAnalyticsInput {
        // 1. Fetch expenses once
        val expenses = expenseRepository.getExpensesBetween(period.startInclusiveMillis, period.endExclusiveMillis)

        // 2. Normalise via AnalyticsCurrencyNormalizer
        val normalized = normalizer.normalizeExpenses(expenses, homeCurrency)

        // 3. Map normalised expenses to the canonical contract
        val included = normalized.normalizedExpenses.map { normExp ->
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
                // TODO (PR-E23): isSharedExpense is not preserved by AnalyticsCurrencyNormalizer.
                // IMPLEMENTATION PLAN:
                // 1. Add isSharedExpense: Boolean field to ExpenseSnapshot (data class in domain/model).
                // 2. In AnalyticsCurrencyNormalizer.toExpenseSnapshot(), pass the original expense's
                //    isSharedExpense through (the NormalizableAnalyticsExpense already carries it as a
                //    field, but it's not mapped to ExpenseSnapshot).
                // 3. Once ExpenseSnapshot.isSharedExpense is populated, wire it here:
                //    isSharedExpense = snap.isSharedExpense
                isSharedExpense = false
            )
        }

        // IMPLEMENTATION PLAN: Build individual ExcludedExpense entries from normalizer warnings.
        //
        // Current gap: AnalyticsConversionWarning tracks affectedTransactionCount but does NOT
        // expose individual expense IDs. To build precise ExcludedExpense entries:
        //
        // 1. Enhance AnalyticsCurrencyNormalizer (or its WarningAccumulator) to expose the set of
        //    excluded expense IDs per warning type. This is already collected internally as
        //    `accumulator.expenseIds` — add a public accessor or return a structured result.
        //
        // 2. For each excluded ID, find the original Expense in [expenses], build an
        //    ExcludedExpense with the appropriate ExclusionReason:
        //    - MISSING_EXCHANGE_RATE -> ExclusionReason.CONVERSION_FAILED
        //    - INVALID_TRANSACTION_CURRENCY -> ExclusionReason.INVALID_CURRENCY
        //    - INVALID_HOME_CURRENCY -> ExclusionReason.INVALID_CURRENCY (all excluded)
        //
        // 3. If currencyConverter is provided, also map expenses whose conversion would have
        //    failed (detected by re-running convertAsOf on each expense). This catches edge cases
        //    where the normalizer includes the expense but with a stale/warning conversion.
        //
        // 4. Remove the intermediate approach below (empty list) once step 1-3 are done.
        val excludedExpenseIds: Set<Long> = emptySet()
        val excluded: List<ExcludedExpense> = if (excludedExpenseIds.isNotEmpty() && expenses.isNotEmpty()) {
            val expenseById = expenses.associateBy { it.id }
            excludedExpenseIds.mapNotNull { id ->
                expenseById[id]?.let { exp ->
                    ExcludedExpense(
                        id = exp.id,
                        originalAmount = exp.effectiveAmount,
                        originalCurrency = exp.currency,
                        reason = ExclusionReason.CONVERSION_FAILED
                    )
                }
            }
        } else {
            emptyList()
        }

        val excludedCount = normalized.excludedCount

        return NormalizedAnalyticsInput(
            period = period,
            homeCurrency = homeCurrency,
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
