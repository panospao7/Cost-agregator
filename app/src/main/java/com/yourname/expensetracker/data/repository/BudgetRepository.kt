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
        // We fetch the last 13 months to cover yearly budgets + rollover
        val thirteenMonthsAgo = System.currentTimeMillis() - (13L * 30 * 24 * 60 * 60 * 1000)
        
        return combine(
            budgetDao.getActiveBudgetsFlow(),
            categoryDao.getAllFlow(),
            expenseDao.getExpensesBetweenFlow(thirteenMonthsAgo, System.currentTimeMillis() + 86400000) // +1 day for safety
        ) { budgets, categories, allExpenses ->
            val purchases = allExpenses.filter { it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE }
            val categoryMap = categories.associateBy { it.id }
            
            budgets.map { budget ->
                val window = budgetMonitor.calculatePeriodWindow(budget.period, budget.startDate)
                
                fun getSpentInRange(start: Long, end: Long): Double {
                    return purchases
                        .filter { 
                            (budget.categoryId == null || it.categoryId == budget.categoryId) && 
                            it.date >= start && it.date < end 
                        }
                        .sumOf { it.amount }
                }

                val spent = getSpentInRange(window.first, window.second)
                var limit = budget.amount
                
                // LOG-002: Implement Rollover
                if (budget.rollover) {
                    val prevWindow = budgetMonitor.getPreviousPeriodWindow(budget.period, budget.startDate)
                    val prevSpent = getSpentInRange(prevWindow.first, prevWindow.second)
                    
                    val rolloverAmount = (budget.amount - prevSpent).coerceAtLeast(0.0)
                    limit += rolloverAmount
                }

                val percent = if (limit > 0) (spent / limit).toFloat() else 0f
                val remaining = (limit - spent).coerceAtLeast(0.0)

                val health = when {
                    percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
                    percent >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
                    percent >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
                    else -> BudgetHealthStatus.ON_TRACK
                }

                BudgetStatus(
                    budget = budget.copy(amount = limit), // Show effective limit
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
        return try {
            val id = budgetDao.insert(budget)
            budgetMonitor.checkBudgets()
            id
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to add budget", e)
            -1L
        }
    }

    suspend fun updateBudget(budget: Budget) {
        try {
            budgetDao.update(budget)
            budgetMonitor.checkBudgets()
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to update budget ${budget.id}", e)
        }
    }

    suspend fun deleteBudget(budget: Budget) {
        try {
            budgetDao.delete(budget)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to delete budget ${budget.id}", e)
        }
    }

    suspend fun toggleBudget(id: Long, isActive: Boolean) {
        try {
            budgetDao.setActive(id, isActive)
            budgetMonitor.checkBudgets()
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to toggle budget $id", e)
        }
    }

    suspend fun deleteAll() {
        try {
            budgetDao.deleteAll()
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to delete all budgets", e)
        }
    }

    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        val suggestions = mutableListOf<BudgetSuggestion>()
        
        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()

        val now = System.currentTimeMillis()
        val oldestDate = expenseDao.getOldestExpenseDate() ?: now
        
        // Use up to 3 months of history, but at least 1 month if available
        // If data is less than 15 days, results might be unreliable, but we'll try to extrapolate conservatively
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        val effectiveStart = maxOf(oldestDate, threeMonthsAgo)
        
        val daysDiff = ((now - effectiveStart) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)
        
        // If we have very little data (e.g. < 7 days), skip suggestions to avoid noise (LOG-010)
        if (daysDiff < 7) return emptyList()

        val monthsDivisor = daysDiff / 30.0
        
        for (category in categories) {
            if (categoriesWithBudget.contains(category.id)) continue

            val spent = expenseDao.getCategorySpentInPeriod(category.id, effectiveStart, now)
            
            // Calculate monthly average
            val monthlyAvg = if (monthsDivisor > 0) spent / monthsDivisor else 0.0
            
            // Only suggest if significant spend (> €20/month)
            if (monthlyAvg > 20.0) { 
                suggestions.add(
                    BudgetSuggestion(
                        categoryId = category.id,
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        // increase buffer to 20% (LOG-016)
                        suggestedAmount = (monthlyAvg * 1.2).coerceAtLeast(20.0), 
                        basedOnMonths = Math.round(monthsDivisor).toInt().coerceAtLeast(1),
                        reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
                    )
                )
            }
        }
        return suggestions.sortedByDescending { it.suggestedAmount }.take(3)
    }
}
