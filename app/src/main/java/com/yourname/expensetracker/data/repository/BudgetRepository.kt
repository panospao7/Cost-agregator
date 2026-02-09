package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val budgetMonitor: BudgetMonitor
) {
    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()

    fun getBudgetStatuses(): Flow<List<BudgetStatus>> {
        return combine(
            budgetDao.getActiveBudgetsFlow(),
            categoryDao.getAllFlow()
        ) { budgets, categories ->
            val categoryMap = categories.associateBy { it.id }
            budgets.map { budget ->
                val window = budgetMonitor.calculatePeriodWindow(budget.period, budget.startDate)
                val spent = if (budget.categoryId != null) {
                    expenseDao.getCategorySpentInPeriod(budget.categoryId, window.first, window.second)
                } else {
                    expenseDao.getTotalForPeriod(window.first, window.second)
                }

                val percent = if (budget.amount > 0) (spent / budget.amount).toFloat() else 0f
                val remaining = (budget.amount - spent).coerceAtLeast(0.0)

                val health = when {
                    percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
                    percent >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
                    percent >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
                    else -> BudgetHealthStatus.ON_TRACK
                }

                BudgetStatus(
                    budget = budget,
                    category = categoryMap[budget.categoryId],
                    spentAmount = spent,
                    remainingAmount = remaining,
                    percentUsed = percent,
                    healthStatus = health,
                    periodStart = window.first,
                    periodEnd = window.second
                )
            }
        }
    }

    suspend fun addBudget(budget: Budget): Long {
        val id = budgetDao.insert(budget)
        budgetMonitor.checkBudgets()
        return id
    }

    suspend fun updateBudget(budget: Budget) {
        budgetDao.update(budget)
        budgetMonitor.checkBudgets()
    }

    suspend fun deleteBudget(budget: Budget) {
        budgetDao.delete(budget)
    }

    suspend fun toggleBudget(id: Long, isActive: Boolean) {
        budgetDao.setActive(id, isActive)
        budgetMonitor.checkBudgets()
    }

    suspend fun deleteAll() {
        budgetDao.deleteAll()
    }

    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        val suggestions = mutableListOf<BudgetSuggestion>()
        
        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()

        val now = System.currentTimeMillis()
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        
        for (category in categories) {
            if (categoriesWithBudget.contains(category.id)) continue

            val spent = expenseDao.getCategorySpentInPeriod(category.id, threeMonthsAgo, now)
            if (spent > 50.0) { // Only suggest for categories with significant spend
                val monthlyAvg = spent / 3.0
                suggestions.add(
                    BudgetSuggestion(
                        categoryId = category.id,
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        suggestedAmount = (monthlyAvg * 1.1).coerceAtLeast(20.0), // 10% buffer
                        basedOnMonths = 3,
                        reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
                    )
                )
            }
        }
        return suggestions.sortedByDescending { it.suggestedAmount }.take(3)
    }
}
