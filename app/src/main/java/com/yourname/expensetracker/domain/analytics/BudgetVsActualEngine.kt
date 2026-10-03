package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetVsActualEngine @Inject constructor() {

    data class BudgetVsActualItem(
        val categoryId: Long?,
        val categoryName: String?,
        val budgetLimit: Double,
        val actualSpent: Double,
        val currency: String,
        val percentageUsed: Double, // 0.0-1.0+
        val isOverBudget: Boolean
    )

    data class BudgetVsActualResult(
        val items: List<BudgetVsActualItem>,
        val totalBudget: Double,
        val totalActual: Double,
        val dataQuality: AnalyticsDataQuality
    )

    fun compute(
        actuals: NormalizedAnalyticsInput,
        budgets: List<BudgetSnapshot>,
        homeCurrency: String
    ): BudgetVsActualResult {
        val items = mutableListOf<BudgetVsActualItem>()

        // Aggregate actual spending by category
        val purchaseExpenses = actuals.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
        val categorySpending = purchaseExpenses
            .groupBy { it.categoryId }
            .mapValues { (_, expenses) ->
                MoneyAggregateBuilder.fromHomeCurrencyAmounts(
                    amounts = expenses.asSequence().map { it.normalizedAmount }.asIterable(),
                    homeCurrency = homeCurrency
                ).displayAmount
            }
        val overallActual = MoneyAggregateBuilder.fromHomeCurrencyAmounts(
            amounts = categorySpending.values,
            homeCurrency = homeCurrency
        ).displayAmount
        val hasOverallBudget = budgets.any { it.categoryId == null }

        // Build a category-name map from the actuals for display purposes
        val categoryNames = purchaseExpenses
            .associate { it.categoryId to it.categoryNameSnapshot }
            .filterValues { it != null }
            .mapValues { it.value!! }

        for (budget in budgets) {
            val actual = if (budget.categoryId == null) {
                overallActual
            } else {
                categorySpending[budget.categoryId] ?: 0.0
            }
            val limit = budget.amount
            val percentage = if (limit > 0) actual / limit else 0.0
            val catName = budget.categoryId?.let { categoryNames[it] }
                ?: actuals.includedExpenses.firstOrNull { exp ->
                    exp.categoryId == budget.categoryId && exp.categoryNameSnapshot != null
                }?.categoryNameSnapshot
                ?: "Unknown"
            items.add(
                BudgetVsActualItem(
                    categoryId = budget.categoryId,
                    categoryName = catName,
                    budgetLimit = limit,
                    actualSpent = actual,
                    currency = homeCurrency,
                    percentageUsed = percentage,
                    isOverBudget = actual > limit
                )
            )
        }

        val totalBudget = if (hasOverallBudget) {
            MoneyAggregateBuilder.fromHomeCurrencyAmounts(
                amounts = budgets.filter { it.categoryId == null }.map { it.amount },
                homeCurrency = homeCurrency
            ).displayAmount
        } else {
            MoneyAggregateBuilder.fromHomeCurrencyAmounts(
                amounts = items.map { it.budgetLimit },
                homeCurrency = homeCurrency
            ).displayAmount
        }
        val totalActual = if (hasOverallBudget) {
            overallActual
        } else {
            MoneyAggregateBuilder.fromHomeCurrencyAmounts(
                amounts = items.map { it.actualSpent },
                homeCurrency = homeCurrency
            ).displayAmount
        }

        return BudgetVsActualResult(
            items = items,
            totalBudget = totalBudget,
            totalActual = totalActual,
            dataQuality = actuals.dataQuality
        )
    }
}
