package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

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
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val daysInMonth = TimePeriodUtils.getDaysInMonth(now)
        val currentDay = (((now - monthStart) / 86400000L).toInt() + 1).coerceAtLeast(1)
        
        val monthSpent = allExpenses
            .filter { 
                it.date >= monthStart && 
                it.transactionType == TransactionType.PURCHASE && 
                !it.isNotMine 
            }
            .sumOf { it.amount }
        
        val previousMonthSpent = allExpenses
            .filter { 
                it.date >= previousMonthStart && 
                it.date < previousMonthEnd &&
                it.transactionType == TransactionType.PURCHASE && 
                !it.isNotMine 
            }
            .sumOf { it.amount }
        
        val previousMonthAvg = allExpenses
            .filter { 
                it.date >= previousMonthStart && 
                it.date < previousMonthEnd &&
                it.transactionType == TransactionType.PURCHASE && 
                !it.isNotMine 
            }
            .groupBy { expense ->
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = expense.date
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            }
            .values
            .map { dayExpenses -> dayExpenses.sumOf { it.amount } }
            .average()
            .takeIf { !it.isNaN() }
        
        val projectedTotal = calculateProjectedTotal(monthSpent, currentDay, daysInMonth)
        val pacePercentage = if (previousMonthSpent > 0) {
            (monthSpent / previousMonthSpent * 100).toFloat()
        } else if (previousMonthAvg != null) {
            (monthSpent / (previousMonthAvg * currentDay) * 100).toFloat()
        } else {
            100f
        }
        
        val paceStatus = when {
            pacePercentage < PACE_UNDER_THRESHOLD -> PaceStatus.UNDER_PACE
            pacePercentage > PACE_OVER_THRESHOLD -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }
        
        return SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = currentDay,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = previousMonthSpent,
            averageMonthlyTotal = previousMonthSpent,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus
        )
    }
    
    private fun calculateProjectedTotal(monthSpent: Double, dayOfMonth: Int, daysInMonth: Int): Double {
        return if (dayOfMonth >= 4) {
            monthSpent * daysInMonth.toDouble() / dayOfMonth
        } else if (dayOfMonth > 0) {
            monthSpent * (daysInMonth.toDouble() / 10.0).coerceAtLeast(1.0)
        } else {
            monthSpent
        }
    }
}
