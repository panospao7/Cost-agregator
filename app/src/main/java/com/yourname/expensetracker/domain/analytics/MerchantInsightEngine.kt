package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
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
            it.transactionType.toDomain() == DomainTransactionType.PURCHASE && 
            !it.isNotMine 
        }
        
        val merchantGroups = purchases.groupBy { it.merchant.lowercase() }
        
        return merchantGroups.map { (_, expenses) ->
            val merchantName = expenses.first().merchant
            val amounts = expenses.map { it.effectiveAmount }
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
