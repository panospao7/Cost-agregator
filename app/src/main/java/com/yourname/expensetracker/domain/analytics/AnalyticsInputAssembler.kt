package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.toCurrencyCodeOrNull
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A01-FIXED: Canonical assembler for normalized analytics input.
 *
 * Fetches expenses once, normalizes them to the home currency via
 * [AnalyticsCurrencyNormalizer], and returns a self-contained
 * [NormalizedAnalyticsInput] with full conversion metadata.
 *
 * Now injected via `@Inject constructor` — no more `object` singleton
 * with manual dependency passing.
 */
@Singleton
class AnalyticsInputAssembler @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val normalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider,
    private val categoryRepository: CategoryRepository
) {

    /**
     * Build a [NormalizedAnalyticsInput] for the given [period].
     *
     * Home currency is resolved from [CurrencySettingsRepository].
     * Spending-only filtering is the default (non-spending excluded).
     */
    suspend fun build(
        period: PeriodRange,
        options: AnalyticsInputOptions = AnalyticsInputOptions()
    ): NormalizedAnalyticsInput {
        val homeCurrency = runCatching {
            currencySettingsRepository.homeCurrency().first()
        }.getOrDefault("EUR")

        val rawExpenses = expenseRepository.getExpensesBetween(
            period.startInclusiveMillis,
            period.endExclusiveMillis
        )

        return buildNormalizedInput(rawExpenses, homeCurrency, period, options)
    }

    /**
     * Build a [NormalizedAnalyticsInput] from pre-fetched [expenses].
     *
     * Skips the expense-fetch step; all filtering and normalisation logic
     * is identical to [build(period, options)].
     */
    suspend fun build(
        expenses: List<Expense>,
        homeCurrency: String,
        period: PeriodRange,
        options: AnalyticsInputOptions = AnalyticsInputOptions()
    ): NormalizedAnalyticsInput {
        return buildNormalizedInput(expenses, homeCurrency, period, options)
    }

    /**
     * Shared normalisation logic used by both [build] overloads.
     */
    private suspend fun buildNormalizedInput(
        expenses: List<Expense>,
        homeCurrency: String,
        period: PeriodRange,
        options: AnalyticsInputOptions
    ): NormalizedAnalyticsInput {
        // 1. Apply pre-filters (spending-only = PURCHASE only, not-mine exclusion)
        val filtered = expenses.filter { exp ->
            if (options.spendingOnly && exp.transactionType != TransactionType.PURCHASE) return@filter false
            if (options.excludeNotMine && exp.isNotMine) return@filter false
            true
        }

        // 2. Fetch categories for name/icon/color snapshot (PR-A11)
        val categories = categoryRepository.getAll()
        val categoryNameById = categories.associate { it.id to it.name }

        // 3. Normalise via AnalyticsCurrencyNormalizer
        val result = normalizer.normalizeExpenses(filtered, homeCurrency)

        // 4. Build a set of IDs that were successfully normalised
        val normalizedIds = result.normalizedExpenses.mapTo(mutableSetOf()) { it.snapshot.id }

        // 5. Map normalised expenses to the canonical contract
        val included = result.normalizedExpenses.map { normExp ->
            val snap = normExp.snapshot
            NormalizedExpense(
                id = snap.id,
                originalAmount = normExp.originalEffectiveAmount,
                originalEffectiveAmount = normExp.originalEffectiveAmount,
                originalCurrency = normExp.originalCurrency,
                normalizedAmount = snap.effectiveAmount,
                normalizedCurrency = homeCurrency,
                date = snap.date,
                merchant = snap.merchant,
                merchantKey = snap.merchantKey,
                categoryId = snap.categoryId,
                categoryNameSnapshot = categoryNameById[snap.categoryId],
                transactionType = snap.transactionType.name,
                isNotMine = snap.isNotMine,
                isSharedExpense = false, // A16: populate when ExpenseSnapshot carries isSharedExpense
                ownershipMode = null,
                source = null
            )
        }

        // 6. Build excluded expenses with detailed reasons
        val excluded = filtered
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

        val excludedCount = result.excludedCount
        val missingWarnings = result.severeWarnings.count {
            it.type == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
        }

        // 7. PR-A9: Compute confidence penalty & multiplier from data quality.
        //   excludedCount/total > 0.1 → penalty up to 0.5
        //   missingRateCount > 0      → multiplier scales down to min 0.8
        val totalCount = filtered.size.coerceAtLeast(1)
        val confidencePenalty = if (excludedCount > 0) {
            (excludedCount.toDouble() / totalCount).coerceAtMost(0.5)
        } else 0.0
        val confidenceMultiplier = if (missingWarnings > 0) {
            maxOf(0.8, 1.0 - missingWarnings * 0.02)
        } else 1.0

        return NormalizedAnalyticsInput(
            period = period,
            homeCurrency = homeCurrency,
            includedExpenses = included,
            excludedExpenses = excluded,
            dataQuality = AnalyticsDataQuality(
                isPartial = excludedCount > 0 || result.hasWarnings,
                excludedCount = excludedCount,
                staleRateCount = 0, // A19: STALE_EXCHANGE_RATE not yet surfaced by normalizer
                missingRateCount = missingWarnings,
                invalidCurrencyCount = result.severeWarnings.count {
                    it.type == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY
                },
                conversionWarnings = result.warnings.map { it.message },
                confidencePenalty = confidencePenalty,
                confidenceMultiplier = confidenceMultiplier,
                warnings = result.warnings
            )
        )
    }
}

/**
 * A01: Options for [AnalyticsInputAssembler.build].
 */
data class AnalyticsInputOptions(
    val spendingOnly: Boolean = true,
    val excludeNotMine: Boolean = true,
    val includeDepositsForBehavior: Boolean = false
)
