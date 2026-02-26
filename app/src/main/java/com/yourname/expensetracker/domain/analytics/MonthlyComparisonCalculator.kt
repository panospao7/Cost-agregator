package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonthlyComparisonCalculator @Inject constructor() {

    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        allExpenses: List<Expense>
    ): MonthlyComparison {
        val currentExpenses = allExpenses.filter { 
            it.date != null &&
            it.date >= currentMonth.startMs && 
            it.date < currentMonth.endMs &&
            it.transactionType == TransactionType.PURCHASE && 
            !it.isNotMine 
        }
        
        val previousExpenses = previousMonth?.let { pm ->
            allExpenses.filter { 
                it.date != null &&
                it.date >= pm.startMs && 
                it.date < pm.endMs &&
                it.transactionType == TransactionType.PURCHASE && 
                !it.isNotMine 
            }
        }
        
        val currentTotal = currentExpenses.sumOf { it.amount }
        val previousTotal = previousExpenses?.sumOf { it.amount }
        
        val changeAmount = if (previousTotal != null && previousTotal > 0) {
            currentTotal - previousTotal
        } else null
        
        val changePercentage = if (previousTotal != null && previousTotal > 0) {
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
        } else null
        
        return MonthlyComparison(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            currentTotal = currentTotal,
            previousTotal = previousTotal,
            changeAmount = changeAmount,
            changePercentage = changePercentage,
            currentCount = currentExpenses.size,
            previousCount = previousExpenses?.size
        )
    }
}
