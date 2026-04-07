package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class SpendingPaceCalculator @Inject constructor(
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val PACE_UNDER_THRESHOLD = 90f
        private const val PACE_OVER_THRESHOLD = 110f
    }

    fun calculate(
        currentMonthStart: Long,
        previousMonthStart: Long,
        previousMonthEnd: Long,
        allExpenses: List<Expense>
    ): SpendingPace {
        val now = timeProvider.now()
        val currentMonthEnd = TimePeriodUtils.getEndOfMonth(currentMonthStart)
        val currentWindowEnd = minOf(now, currentMonthEnd)
        val daysInMonth = TimePeriodUtils.getDaysInMonth(currentMonthStart)
        val currentDay = (TimePeriodUtils.daysBetween(currentMonthStart, currentWindowEnd) + 1).coerceAtLeast(1)
        
        val monthSpent = allExpenses
            .filter { 
                it.date >= currentMonthStart &&
                it.date < currentWindowEnd &&
                it.transactionType.toDomain() == DomainTransactionType.PURCHASE && 
                !it.isNotMine 
            }
            .sumOf { it.effectiveAmount }
        
        val previousMonthSpent = allExpenses
            .filter {
                it.date >= previousMonthStart &&
                it.date < previousMonthEnd &&
                it.transactionType.toDomain() == DomainTransactionType.PURCHASE &&
                !it.isNotMine
            }
            .sumOf { it.effectiveAmount }

        val projectedTotal = calculateProjectedTotal(monthSpent, currentDay, daysInMonth)

        // Canonical pace formula used across analytics engines:
        // pace% = (currentDailyRate / baselineDailyRate) * 100
        // currentDailyRate = currentSpent / daysElapsed
        // baselineDailyRate = previousMonthTotal / daysInPreviousMonth
        val currentDailyRate = if (currentDay > 0) monthSpent / currentDay else 0.0
        val previousMonthDays = TimePeriodUtils.getDaysInMonth(previousMonthStart)
        val baselineDailyRate = if (previousMonthDays > 0) previousMonthSpent / previousMonthDays else 0.0

        val hasBaseline = baselineDailyRate > 0.0
        val pacePercentage = if (hasBaseline) {
            (currentDailyRate / baselineDailyRate * 100).toFloat()
        } else {
            0f
        }

        Timber.tag("SpendingPaceCalculator").d(
            "Pace calculation: monthSpent=%.2f, daysElapsed=%d, currentDailyRate=%.4f, previousMonthSpent=%.2f, previousMonthDays=%d, baselineDailyRate=%.4f, pacePercentage=%.2f",
            monthSpent,
            currentDay,
            currentDailyRate,
            previousMonthSpent,
            previousMonthDays,
            baselineDailyRate,
            pacePercentage
        )

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
            paceStatus = paceStatus
        )
    }
    
    private fun calculateProjectedTotal(monthSpent: Double, dayOfMonth: Int, daysInMonth: Int): Double {
        if (dayOfMonth <= 0) return monthSpent

        val weight = (dayOfMonth.toDouble() / 7.0).coerceIn(0.0, 1.0)
        val linearProjection = monthSpent * daysInMonth.toDouble() / dayOfMonth
        val conservativeEstimate = monthSpent * 3.0
        return (weight * linearProjection) + ((1.0 - weight) * conservativeEstimate)
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
