package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * ## Enforced currency boundary
 * Normalized inputs retain their home currency and rate provenance. The legacy
 * snapshot adapter accepts only one supported source currency matching the
 * requested target; it never converts or relabels mixed-currency rows.
 * Unavailable conversion data is not projected as zero spending.
 *
 * ## Pace formula
 *   pace% = (currentDailyRate / baselineDailyRate) × 100
 *   where currentDailyRate = monthSpent / daysElapsed
 *   and   baselineDailyRate = previousMonthSpent / daysInPreviousMonth
 */
@Singleton
class SpendingPaceCalculator @Inject constructor(
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val PACE_UNDER_THRESHOLD = 90f
        private const val PACE_OVER_THRESHOLD = 110f
    }

    // A13: Add referenceNow parameter; use period.endMs for historical, timeProvider.now() for live.
    // Currently uses timeProvider.now() unconditionally, which makes the pace calculation
    // wrong when run against a past (historical) period — it compares to "now" instead of
    // the period's end.
    // A13-FIXED: Historical periods use period end, not timeProvider.now().
    @Deprecated("Use normalized input; compatibility snapshots must all match the explicit currency")
    fun calculate(
        currentMonthStart: Long,
        previousMonthStart: Long,
        previousMonthEnd: Long,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses),
        referenceNowMs: Long = timeProvider.now()
    ): SpendingPace {
        val input = SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
        return calculate(currentMonthStart, previousMonthStart, previousMonthEnd, input, input, displayCurrency, referenceNowMs)
    }

    /**
     * Calculates pace from canonical normalized analytics inputs.
     *
     * The scalar fields remain available for legacy dashboard consumers, while
     * the typed results preserve unavailable/partial state and rate provenance.
     */
    fun calculate(
        currentMonthStart: Long,
        previousMonthStart: Long,
        previousMonthEnd: Long,
        currentInput: NormalizedAnalyticsInput,
        previousInput: NormalizedAnalyticsInput? = null,
        displayCurrency: String = currentInput.homeCurrency,
        referenceNowMs: Long = timeProvider.now()
    ): SpendingPace {
        val currentMonthEnd = TimePeriodUtils.getEndOfMonth(currentMonthStart)
        val currentWindowEnd = minOf(referenceNowMs, currentMonthEnd)
        val daysInMonth = TimePeriodUtils.getDaysInMonth(currentMonthStart)
        val currentDay = (TimePeriodUtils.daysBetween(currentMonthStart, currentWindowEnd) + 1)
            .coerceAtLeast(1)

        val currentExpenses = currentInput.includedExpenses.filter {
            it.date >= currentMonthStart &&
                it.date < currentWindowEnd &&
                it.transactionType == DomainTransactionType.PURCHASE.name &&
                !it.isNotMine
        }
        val currentResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
            input = currentInput,
            includedExpenses = currentExpenses,
            startInclusiveMillis = currentMonthStart,
            endExclusiveMillis = currentWindowEnd,
            displayCurrency = displayCurrency
        )

        val previousExpenses = previousInput?.includedExpenses?.filter {
            it.date >= previousMonthStart &&
                it.date < previousMonthEnd &&
                it.transactionType == DomainTransactionType.PURCHASE.name &&
                !it.isNotMine
        }
        val previousResult = previousInput?.let { input ->
            NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
                input = input,
                includedExpenses = previousExpenses.orEmpty(),
                startInclusiveMillis = previousMonthStart,
                endExclusiveMillis = previousMonthEnd,
                displayCurrency = displayCurrency
            )
        }

        val monthSpent = availableAmount(currentResult)
        val previousMonthSpent = previousResult?.let(::availableAmount)
        val previousMonthDays = TimePeriodUtils.getDaysInMonth(previousMonthStart)
        val currentDailyRate = monthSpent?.div(currentDay)
        val baselineDailyRate = if (previousMonthDays > 0 && previousMonthSpent != null) {
            previousMonthSpent / previousMonthDays
        } else {
            0.0
        }
        val projectedTotal = monthSpent?.let { availableSpent -> calculateProjectedTotal(
            monthSpent = availableSpent,
            daysElapsed = currentDay,
            daysInMonth = daysInMonth,
            baselineDailyRate = baselineDailyRate
        ) }
        val hasBaseline = currentResult is MoneyAggregateResult.Available &&
            previousResult is MoneyAggregateResult.Available &&
            currentDailyRate != null && baselineDailyRate > 0.0
        val pacePercentage = if (hasBaseline) {
            (currentDailyRate!! / baselineDailyRate * 100.0).toFloat()
        } else {
            -1f
        }
        val paceStatus = when {
            !hasBaseline -> PaceStatus.NO_BASELINE
            pacePercentage < PACE_UNDER_THRESHOLD -> PaceStatus.UNDER_PACE
            pacePercentage > PACE_OVER_THRESHOLD -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }

        return SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = currentDay,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (hasBaseline) previousMonthSpent else null,
            averageMonthlyTotal = null,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus,
            displayCurrency = displayCurrency,
            currentMoneyResult = currentResult,
            previousMoneyResult = previousResult
        )
    }

    private fun availableAmount(result: MoneyAggregateResult): Double? =
        (result as? MoneyAggregateResult.Available)?.aggregate?.displayAmount

    
    private fun calculateProjectedTotal(
        monthSpent: Double,
        daysElapsed: Int,
        daysInMonth: Int,
        baselineDailyRate: Double
    ): Double = SpendingPaceProjection.calculateProjectedTotal(
        monthSpent = monthSpent,
        daysElapsed = daysElapsed,
        daysInMonth = daysInMonth,
        baselineDailyRate = baselineDailyRate
    )

}
