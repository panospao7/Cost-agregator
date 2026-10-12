package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayOfWeekAnalyzer @Inject constructor() {

    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    @Deprecated("Use normalized input; compatibility snapshots must all match the explicit currency")
    fun analyze(
        startDate: Long,
        endDate: Long,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses)
    ): List<DayOfWeekInsight> {
        return analyze(
            startDate,
            endDate,
            SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency),
            displayCurrency = displayCurrency
        ).map { insight ->
            if (
                insight.totalSpent == null &&
                insight.moneyResult is MoneyAggregateResult.Available
            ) {
                insight.copy(totalSpent = 0.0, avgPerTransaction = 0.0)
            } else {
                insight
            }
        }
    }

    /**
     * Analyzes weekday spending from normalized inputs without repeating money
     * arithmetic in this dimension consumer. Current and historical inputs are
     * merged only for the requested date window; no conversion is performed.
     */
    fun analyze(
        startDate: Long,
        endDate: Long,
        input: NormalizedAnalyticsInput,
        historicalInput: NormalizedAnalyticsInput? = null,
        displayCurrency: String = input.homeCurrency
    ): List<DayOfWeekInsight> {
        val mergedInput = mergeInputs(input, historicalInput)
        val expenses = mergedInput.includedExpenses.filter {
            it.date >= startDate &&
                it.date < endDate &&
                it.transactionType == DomainTransactionType.PURCHASE.name &&
                !it.isNotMine
        }
        val byDayOfWeek = expenses.groupBy { dayIndex(it.date) }

        return DAY_NAMES.indices.map { dayIndex ->
            val dayExpenses = byDayOfWeek[dayIndex].orEmpty()
            val result = NormalizedAnalyticsMoneyAdapter.aggregateSelectedSlice(
                input = mergedInput,
                includedExpenses = dayExpenses,
                displayCurrency = displayCurrency,
                exclusionAttribution = ExclusionAttribution.TEMPORAL,
                includeExcluded = { excluded ->
                    val date = excluded.date
                    date == null || (
                        date >= startDate &&
                            date < endDate &&
                            this.dayIndex(date) == dayIndex
                        )
                }
            )
            val total = availableAmount(result)
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = total,
                transactionCount = dayExpenses.size,
                avgPerTransaction = total?.let { if (dayExpenses.isNotEmpty()) it / dayExpenses.size else 0.0 },
                displayCurrency = displayCurrency,
                moneyResult = result
            )
        }
    }

    private fun mergeInputs(
        current: NormalizedAnalyticsInput,
        historical: NormalizedAnalyticsInput?
    ): NormalizedAnalyticsInput {
        val currentIncluded = current.includedExpenses.filter { current.period?.contains(it.date) != false }
        val currentExcluded = current.excludedExpenses.filter { it.date == null || current.period?.contains(it.date) != false }
        if (historical == null) return current.copy(includedExpenses = currentIncluded, excludedExpenses = currentExcluded)
        val currentIds = (currentIncluded.map { it.id } + currentExcluded.map { it.id }).toSet()
        val included = currentIncluded + historical.includedExpenses.filter {
            it.id !in currentIds && historical.period?.contains(it.date) != false
        }
        val excluded = (currentExcluded + historical.excludedExpenses.filter {
            it.id !in currentIds && (it.date == null || historical.period?.contains(it.date) != false)
        }).distinctBy { it.id }
        return NormalizedAnalyticsInput(
            period = null,
            homeCurrency = current.homeCurrency,
            includedExpenses = included.distinctBy { it.id },
            excludedExpenses = excluded,
            dataQuality = AnalyticsDataQuality(
                isPartial = excluded.isNotEmpty(),
                excludedCount = excluded.size,
                staleRateCount = excluded.count { it.warningType == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE },
                missingRateCount = excluded.count { it.warningType == AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE },
                invalidCurrencyCount = excluded.count { it.warningType == AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY },
                conversionWarnings = (current.dataQuality.conversionWarnings + historical.dataQuality.conversionWarnings).distinct(),
                confidencePenalty = maxOf(current.dataQuality.confidencePenalty, historical.dataQuality.confidencePenalty),
                confidenceMultiplier = minOf(current.dataQuality.confidenceMultiplier, historical.dataQuality.confidenceMultiplier),
                warnings = (current.dataQuality.warnings + historical.dataQuality.warnings).distinct()
            )
        )
    }

    private fun dayIndex(date: Long): Int =
        Instant.ofEpochMilli(date)
            .atZone(ZoneId.systemDefault())
            .dayOfWeek
            .value - 1

    private fun availableAmount(result: MoneyAggregateResult): Double? =
        (result as? MoneyAggregateResult.Available)?.aggregate?.displayAmount


}
