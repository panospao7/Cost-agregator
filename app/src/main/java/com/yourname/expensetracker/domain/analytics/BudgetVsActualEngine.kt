package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.BudgetSnapshot
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
        var totalBudget = 0.0
        var totalActual = 0.0

        // Aggregate actual spending by category
        val categorySpending = actuals.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .groupBy { it.categoryId }
            .mapValues { (_, expenses) -> expenses.sumOf { it.normalizedAmount } }

        // Build a category-name map from the actuals for display purposes
        val categoryNames = actuals.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .associate { it.categoryId to it.categoryNameSnapshot }
            .filterValues { it != null }
            .mapValues { it.value!! }

        for (budget in budgets) {
            val actual = categorySpending[budget.categoryId] ?: 0.0
            val limit = budget.amount
            val percentage = if (limit > 0) actual / limit else 0.0
            val catName = budget.categoryId?.let { categoryNames[it] } ?: "Unknown"
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
            totalBudget += limit
            totalActual += actual
        }

        return BudgetVsActualResult(
            items = items,
            totalBudget = totalBudget,
            totalActual = totalActual,
            dataQuality = actuals.dataQuality
        )
    }
}
