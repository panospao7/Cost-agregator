package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class MerchantInsightEngine @Inject constructor() {

    companion object {
        private const val TOP_MERCHANTS_LIMIT = 10
        private const val RECURRING_THRESHOLD = 3
    }

    fun calculate(allExpenses: List<Expense>): List<MerchantInsight> {
        val purchases = allExpenses.filter { 
            it.transactionType == TransactionType.PURCHASE && 
            !it.isNotMine 
        }
        
        val merchantGroups = purchases.groupBy { it.merchant.lowercase() }
        
        return merchantGroups.map { (_, expenses) ->
            val merchantName = expenses.first().merchant
            val amounts = expenses.map { it.amount }
            val total = amounts.sum()
            val count = amounts.size
            val avg = if (count > 0) total / count else 0.0
            val min = amounts.minOrNull() ?: 0.0
            val max = amounts.maxOrNull() ?: 0.0
            
            val stdDev = if (count >= 3) {
                val mean = amounts.average()
                val variance = amounts.map { (it - mean) * (it - mean) }.average()
                sqrt(variance)
            } else null
            
            MerchantInsight(
                merchant = merchantName,
                avgAmount = avg,
                minAmount = min,
                maxAmount = max,
                totalSpent = total,
                transactionCount = count,
                isLikelyRecurring = count >= RECURRING_THRESHOLD && stdDev != null && stdDev / avg < 0.3,
                stdDeviation = stdDev
            )
        }
        .sortedByDescending { it.totalSpent }
        .take(TOP_MERCHANTS_LIMIT)
    }
}
