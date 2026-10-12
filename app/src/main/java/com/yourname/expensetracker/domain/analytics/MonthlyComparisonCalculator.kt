package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonthlyComparisonCalculator @Inject constructor() {

    @Deprecated("Use normalized input; compatibility snapshots must all match the explicit currency")
    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses)
    ): MonthlyComparison {
        val input = SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
        return calculate(currentMonth, previousMonth, input, input, displayCurrency)
    }

    /**
     * Calculates the comparison from canonical normalized analytics inputs.
     *
     * The legacy scalar fields remain populated for existing consumers, while
     * the typed results preserve unavailable/partial state and rate provenance.
     */
    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        currentInput: NormalizedAnalyticsInput,
        previousInput: NormalizedAnalyticsInput? = null,
        displayCurrency: String = currentInput.homeCurrency
    ): MonthlyComparison {
        val currentExpenses = currentInput.includedExpenses.filter {
            it.date >= currentMonth.startMs &&
                it.date < currentMonth.endMs &&
                it.transactionType == DomainTransactionType.PURCHASE.name &&
                !it.isNotMine
        }
        val currentResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
            input = currentInput,
            includedExpenses = currentExpenses,
            startInclusiveMillis = currentMonth.startMs,
            endExclusiveMillis = currentMonth.endMs,
            displayCurrency = displayCurrency
        )

        val previousExpenses = previousMonth?.let { period ->
            previousInput?.includedExpenses?.filter {
                it.date >= period.startMs &&
                    it.date < period.endMs &&
                    it.transactionType == DomainTransactionType.PURCHASE.name &&
                    !it.isNotMine
            }
        }
        val previousResult = previousMonth?.let { period ->
            previousInput?.let { input ->
                NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
                    input = input,
                    includedExpenses = previousExpenses.orEmpty(),
                    startInclusiveMillis = period.startMs,
                    endExclusiveMillis = period.endMs,
                    displayCurrency = displayCurrency
                )
            }
        }

        val currentAvailable = currentResult is MoneyAggregateResult.Available
        val previousAvailable = previousResult is MoneyAggregateResult.Available
        val currentTotal = availableAmount(currentResult)
        val previousTotal = previousResult?.let(::availableAmount)
        val changeAmount = if (currentTotal != null && previousAvailable && previousTotal != null && previousTotal > 0.0) {
            currentTotal - previousTotal
        } else {
            null
        }
        val changePercentage = if (currentTotal != null && previousAvailable && previousTotal != null && previousTotal > 0.0) {
            ((currentTotal - previousTotal) / previousTotal * 100.0).toFloat()
        } else {
            null
        }

        return MonthlyComparison(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            currentTotal = currentTotal,
            previousTotal = previousTotal,
            changeAmount = changeAmount,
            changePercentage = changePercentage,
            currentCount = currentExpenses.size,
            previousCount = previousExpenses?.size,
            displayCurrency = displayCurrency,
            currentMoneyResult = currentResult,
            previousMoneyResult = previousResult
        )
    }

    private fun availableAmount(result: MoneyAggregateResult): Double? =
        (result as? MoneyAggregateResult.Available)?.aggregate?.displayAmount


}
