package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class MerchantInsightEngine @Inject constructor() {

    companion object {
        private const val TOP_MERCHANTS_LIMIT = 10
        private const val RECURRING_THRESHOLD = 3
    }

    @Deprecated("Use normalized input; compatibility snapshots must all match the explicit currency")
    fun calculate(
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses)
    ): List<MerchantInsight> {
        val input = SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
        return calculate(input)
    }

    fun calculate(input: NormalizedAnalyticsInput): List<MerchantInsight> {
        val displayCurrency = input.homeCurrency
        val purchases = input.includedExpenses
            .filter {
                it.transactionType == DomainTransactionType.PURCHASE.name &&
                    !it.isNotMine &&
                    input.period?.contains(it.date) != false
            }
            .map { it.toExpenseSnapshot() }
        
        val merchantGroups = purchases.groupBy { it.canonicalMerchantKey() }
        val normalizedById = input.includedExpenses.associateBy { it.id }
        
        return merchantGroups.mapNotNull { (_, expenses) ->
            val merchantName = resolveMerchantDisplayName(expenses)
            val normalizedExpenses = expenses.mapNotNull { normalizedById[it.id] }
            if (normalizedExpenses.size != expenses.size) return@mapNotNull null
            val totalResult = NormalizedAnalyticsMoneyAdapter.aggregateUnattributedSlice(
                input = input,
                includedExpenses = normalizedExpenses,
                displayCurrency = displayCurrency
            )
            val total = (totalResult as? com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available)
                ?.aggregate?.displayAmount
                ?: return@mapNotNull null
            val amounts = normalizedExpenses.map { it.normalizedAmount }
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
                stdDeviation = stdDev,
                displayCurrency = displayCurrency
            )
        }
        .sortedByDescending { it.totalSpent }
        .take(TOP_MERCHANTS_LIMIT)
    }


}
