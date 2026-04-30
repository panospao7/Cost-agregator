package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonthlyComparisonCalculator @Inject constructor() {

    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = "EUR"
    ): MonthlyComparison {
        val currentExpenses = allExpenses.filter { 
            it.date >= currentMonth.startMs && 
            it.date < currentMonth.endMs &&
            it.transactionType == DomainTransactionType.PURCHASE && 
            !it.isNotMine 
        }
        
        val previousExpenses = previousMonth?.let { pm ->
            allExpenses.filter { 
                it.date >= pm.startMs && 
                it.date < pm.endMs &&
                it.transactionType == DomainTransactionType.PURCHASE && 
                !it.isNotMine 
            }
        }
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val currentTotal = currentExpenses.sumOf { it.effectiveAmount }
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val previousTotal = previousExpenses?.sumOf { it.effectiveAmount }
        
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
            previousCount = previousExpenses?.size,
            displayCurrency = displayCurrency
        )
    }


}
