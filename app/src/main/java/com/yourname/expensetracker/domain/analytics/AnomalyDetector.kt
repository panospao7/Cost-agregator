package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class AnomalyDetector @Inject constructor() {

    companion object {
        private const val ANOMALY_MULTIPLIER = 2.5
        private const val MIN_TRANSACTIONS_FOR_ANALYSIS = 5
    }

    fun detect(
        monthPeriod: MonthPeriod,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<AnomalyTransaction> {
        val anomalies = mutableListOf<AnomalyTransaction>()
        
        val monthExpenses = allExpenses.filter { 
            expense -> 
            expense.date != null &&
            expense.date >= monthPeriod.startMs && 
            expense.date < monthPeriod.endMs &&
            expense.transactionType == TransactionType.PURCHASE && 
            !expense.isNotMine 
        }
        
        if (monthExpenses.size < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            return anomalies
        }
        
        val categoryExpenses = monthExpenses.groupBy { it.categoryId }
        
        for ((categoryId, expenses) in categoryExpenses) {
            if (expenses.size < 2) continue
            
            val category = categoryMap[categoryId]
            val amounts = expenses.mapNotNull { it.amount }
            if (amounts.size < 2) continue
            
            val avg = amounts.average()
            val stdDev = calculateStdDev(amounts, avg)
            
            for (expense in expenses) {
                if (expense.amount > avg + (ANOMALY_MULTIPLIER * stdDev)) {
                    anomalies.add(
                        AnomalyTransaction(
                            expense = expense,
                            merchantAvg = avg,
                            deviationMultiple = (expense.amount / avg).toFloat(),
                            category = category
                        )
                    )
                }
            }
        }
        
        return anomalies.sortedByDescending { it.expense.amount }
    }
    
    private fun calculateStdDev(amounts: List<Double>, mean: Double): Double {
        if (amounts.size < 2) return 0.0
        val variance = amounts.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
}
