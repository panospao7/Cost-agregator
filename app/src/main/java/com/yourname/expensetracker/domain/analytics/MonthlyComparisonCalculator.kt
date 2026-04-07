package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
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
            it.transactionType.toDomain() == DomainTransactionType.PURCHASE && 
            !it.isNotMine 
        }
        
        val previousExpenses = previousMonth?.let { pm ->
            allExpenses.filter { 
                it.date != null &&
                it.date >= pm.startMs && 
                it.date < pm.endMs &&
                it.transactionType.toDomain() == DomainTransactionType.PURCHASE && 
                !it.isNotMine 
            }
        }
        
        val currentTotal = currentExpenses.sumOf { it.effectiveAmount }
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
            previousCount = previousExpenses?.size
        )
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
