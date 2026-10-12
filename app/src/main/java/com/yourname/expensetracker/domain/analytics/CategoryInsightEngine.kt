package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes per-category spending insights (totals, changes from previous month).
 *
 * ## Enforced currency boundary
 * Normalized inputs retain their home currency and rate provenance. The legacy
 * snapshot adapter accepts only one supported source currency matching the
 * requested target; it never converts or relabels mixed-currency rows.
 * Unavailable conversion data is not projected as zero spending.
 *
 * ## Known limitation: stale/deleted category IDs
 * When an expense references a [categoryId] that no longer exists in the
 * [categoryMap] (because the user deleted the category after the transaction
 * was recorded), this engine silently falls back to [FALLBACK_CATEGORY] (a
 * hard-coded "Uncategorized" ref). This means:
 *
 * - Category names in past insights snapshots may be stale (the fallback shows
 *   "Uncategorized" instead of the original name).
 * - Aggregations across time are broken: two identical categoryIds that are
 *   both missing from the map will be merged under the same fallback, even if
 *   they referred to different deleted categories.
 *
 * A future fix should either:
 *   (a) Persist a snapshot of the category name at query time, or
 *   (b) Soft-delete categories so the ID-to-name mapping remains available
 *       for historical rollups.
 */
@Singleton
class CategoryInsightEngine @Inject constructor() {

    companion object {
        private const val MAX_MISSING_CATEGORY_TRACKED = 100

        private val FALLBACK_CATEGORY = AnalyticsCategoryRef(
            id = -1,
            name = "Uncategorized",
            icon = "❓",
            color = "#9E9E9E"
        )
    }

    private val missingCategoryHitCount = ConcurrentHashMap<Long, Int>()

    @Deprecated("Use normalized input; compatibility snapshots must all match the explicit currency")
    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        categoryMap: Map<Long, AnalyticsCategoryRef>,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses)
    ): List<CategoryInsight> {
        val input = SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
        return calculate(currentMonth, previousMonth, categoryMap, input, input, displayCurrency)
    }

    /**
     * Calculates category insights from canonical normalized analytics inputs.
     *
     * Dimension-level exclusion ownership is not available in the normalized
     * contract. If a conversion exclusion belongs to the requested month, each
     * category aggregate therefore fails closed instead of attaching the
     * whole-month exclusion count to one category or silently redistributing it.
     */
    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        categoryMap: Map<Long, AnalyticsCategoryRef>,
        currentInput: NormalizedAnalyticsInput,
        previousInput: NormalizedAnalyticsInput? = null,
        displayCurrency: String = currentInput.homeCurrency
    ): List<CategoryInsight> {
        val currentExpenses = currentInput.includedExpenses.filter {
            it.date >= currentMonth.startMs &&
                it.date < currentMonth.endMs &&
                currentInput.period?.contains(it.date) != false &&
                it.transactionType == DomainTransactionType.PURCHASE.name &&
                !it.isNotMine
        }
        val previousSource = previousInput ?: currentInput
        val previousExpenses = previousMonth?.let { month ->
            previousSource.includedExpenses.filter {
                it.date >= month.startMs &&
                    it.date < month.endMs &&
                    previousSource.period?.contains(it.date) != false &&
                    it.transactionType == DomainTransactionType.PURCHASE.name &&
                    !it.isNotMine
            }
        }.orEmpty()

        val currentTotalResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
            currentInput, currentExpenses, currentMonth.startMs, currentMonth.endMs, displayCurrency = displayCurrency
        )
        val currentTotal = availableAmount(currentTotalResult)

        val missingCategoryUsage = currentExpenses
            .asSequence()
            .mapNotNull { it.categoryId }
            .filter { !categoryMap.containsKey(it) }
            .groupingBy { it }
            .eachCount()
        if (missingCategoryUsage.isNotEmpty()) {
            val affectedTransactions = missingCategoryUsage.values.sum()
            missingCategoryUsage.forEach { (categoryId, count) ->
                missingCategoryHitCount.merge(categoryId, count, Int::plus)
            }
            pruneMissingCategoryHitsIfNeeded()
            val sample = missingCategoryUsage.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString { "${it.key}:${it.value}" }
            val cumulativeSample = missingCategoryHitCount.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString { "${it.key}:${it.value}" }
            Timber.tag("CategoryInsightEngine").w(
                "Missing category mappings detected; fallback used for %d txs (%s), cumulative (%s)",
                affectedTransactions,
                sample,
                cumulativeSample
            )
        }

        val currentByCategory = currentExpenses.groupBy { it.categoryId }
        val previousByCategory = previousExpenses.groupBy { it.categoryId }
        return currentByCategory.map { (categoryId, expenses) ->
            val category = categoryId?.let { categoryMap[it] } ?: FALLBACK_CATEGORY
            val categoryResult = aggregateMonth(currentInput, expenses, currentMonth, displayCurrency)
            val previousCategoryExpenses = previousByCategory[categoryId]
            val previousCategoryResult = previousMonth?.let {
                aggregateMonth(previousSource, previousCategoryExpenses.orEmpty(), it, displayCurrency)
            }
            val currentAmount = availableAmount(categoryResult)
            val previousAmount = previousCategoryExpenses?.let {
                (previousCategoryResult as? MoneyAggregateResult.Available)?.aggregate?.displayAmount
            }
            val changeFromPrevious = if (currentAmount != null && previousAmount != null && previousAmount > 0) {
                ((currentAmount - previousAmount) / previousAmount * 100).toFloat()
            } else {
                null
            }
            val percentageOfTotal = if (currentTotal != null && currentAmount != null) {
                if (currentTotal > 0) (currentAmount / currentTotal * 100).toFloat() else 0f
            } else {
                null
            }
            CategoryInsight(
                category = category,
                currentTotal = currentAmount,
                currentCount = expenses.size,
                previousTotal = previousAmount,
                previousCount = previousCategoryExpenses?.size,
                averageOverMonths = null,
                monthsOfData = 1,
                percentageOfTotal = percentageOfTotal,
                changeFromPrevious = changeFromPrevious,
                changeFromAverage = null,
                displayCurrency = displayCurrency,
                moneyResult = categoryResult,
                previousMoneyResult = previousCategoryResult
            )
        }.sortedByDescending { it.currentTotal }
    }

    private fun aggregateMonth(
        input: NormalizedAnalyticsInput,
        expenses: Iterable<NormalizedExpense>,
        month: MonthPeriod,
        displayCurrency: String
    ): MoneyAggregateResult = NormalizedAnalyticsMoneyAdapter.aggregateUnattributedSlice(
        input = input.copy(
            excludedExpenses = input.excludedExpenses.filter {
                it.date == null || (it.date >= month.startMs && it.date < month.endMs && input.period?.contains(it.date) != false)
            }
        ),
        includedExpenses = expenses,
        displayCurrency = displayCurrency
    )

    private fun availableAmount(result: MoneyAggregateResult): Double? =
        (result as? MoneyAggregateResult.Available)?.aggregate?.displayAmount


    private fun pruneMissingCategoryHitsIfNeeded() {
        if (missingCategoryHitCount.size <= MAX_MISSING_CATEGORY_TRACKED) return

        val retainedKeys = missingCategoryHitCount.entries
            .sortedByDescending { it.value }
            .take(MAX_MISSING_CATEGORY_TRACKED)
            .map { it.key }
            .toHashSet()

        missingCategoryHitCount.keys.forEach { key ->
            if (!retainedKeys.contains(key)) {
                missingCategoryHitCount.remove(key)
            }
        }
    }

}
