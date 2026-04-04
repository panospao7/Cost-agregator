package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val budgetCalculator: BudgetCalculator,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
    private val offsetEngine: SharedExpenseBudgetOffsetEngine
) {
    data class DebugBudgetSnapshot(
        val budgets: List<Budget>
    )

    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()

    suspend fun getActiveBudgets(): List<Budget> = budgetDao.getActiveBudgets()
    
    suspend fun getById(id: Long): Budget? = budgetDao.getById(id)

    fun getBudgetStatuses(): Flow<List<BudgetStatus>> {
        // We fetch the last 25 months to cover yearly budgets + rollover (need 24 months for full yearly history)
        val twentyFiveMonthsAgo = TimePeriodUtils.addMonths(timeProvider.now(), -25)
        val endExclusive = TimePeriodUtils.getEndOfDay(timeProvider.now())
        
        return combine(
            budgetDao.getActiveBudgetsFlow(),
            categoryDao.getAllFlow(),
            expenseDao.getExpensesBetweenFlow(twentyFiveMonthsAgo, endExclusive)
        ) { budgets, categories, allExpenses ->
            val purchases = allExpenses.filter { 
                it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE &&
                !it.isNotMine 
            }
            val categoryMap = categories.associateBy { it.id }
            
            budgets.map { budget ->
                val now = timeProvider.now()
                val (periodStart, periodEnd) = budgetCalculator.calculatePeriodRange(budget, now)
                val window = PeriodRange(periodStart, periodEnd)
                
                fun getSpentInRange(start: Long, end: Long): Double {
                    return purchases
                        .filter { 
                            (budget.categoryId == null || it.categoryId == budget.categoryId) && 
                            it.date >= start && it.date < end 
                        }
                        .sumOf { it.effectiveAmount }
                }

                // Calculate effective spend using offset engine for shared expenses
                // Note: We can't call suspend function here, so we use the regular calculation
                // The offset calculation happens in the background via a separate flow
                val rawSpent = getSpentInRange(window.start, window.end)
                
                // For now, use the raw spend. The adjusted spend will be calculated
                // asynchronously by the BudgetMonitor or ViewModel
                val spent = rawSpent
                var limit = budget.amount
                
                // LOG-002: Implement Compounding Rollover - BUG-2 FIX
                if (budget.rollover) {
                    val budgetFirstStart = budget.startDate
                    val periods = mutableListOf<PeriodRange>()
                    var currentWindow = budgetCalculator.calculatePeriodWindow(budget.period, budgetFirstStart)
                    while (currentWindow.end <= window.start) {
                        periods.add(currentWindow)
                        currentWindow = budgetCalculator.calculatePeriodWindow(budget.period, currentWindow.end)
                    }
                    var effectiveLimit = budget.amount
                    for (period in periods) {
                        val spentInPeriod = getSpentInRange(period.start, period.end)
                        val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
                        effectiveLimit = budget.amount + surplus
                    }
                    limit = effectiveLimit
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
                    periodStart = periodStart,
                    periodEnd = periodEnd
                )
            }
        }
    }

    suspend fun addBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Long> {
        return try {
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            if (budget.startDate <= 0) throw IllegalArgumentException("Invalid budget start date")
            val id = budgetDao.insert(budget)
            // budgetMonitor.checkBudgets() // Removed to avoid circular dependency. Monitor should observe flow.
            com.yourname.expensetracker.domain.model.Result.Success(id)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add budget")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to add budget")
        }
    }

    suspend fun updateBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            // Reset notifications when budget is edited so user gets fresh alerts (BUG-7 Fix)
            val resetBudget = budget.copy(
                lastWarningNotifiedAt = null,
                lastCriticalNotifiedAt = null,
                lastExceededNotifiedAt = null
            )
            budgetDao.update(resetBudget)
            // budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update budget ${budget.id}")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to update budget")
        }
    }

    suspend fun deleteBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.delete(budget)
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete budget ${budget.id}")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete budget")
        }
    }

    suspend fun toggleBudget(id: Long, isActive: Boolean): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.setActive(id, isActive)
            // budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle budget $id")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to toggle budget")
        }
    }

    suspend fun deleteAll(): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.deleteAll()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all budgets")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete all budgets")
        }
    }

    suspend fun createDebugSnapshot(): DebugBudgetSnapshot {
        return DebugBudgetSnapshot(budgets = budgetDao.getAll())
    }

    suspend fun restoreDebugSnapshot(snapshot: DebugBudgetSnapshot): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.deleteAll()
            if (snapshot.budgets.isNotEmpty()) {
                budgetDao.insertAll(snapshot.budgets)
            }
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore debug budget snapshot")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to restore budgets")
        }
    }

    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        val suggestions = mutableListOf<BudgetSuggestion>()
        
        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()

        val now = timeProvider.now()
        val endExclusive = TimePeriodUtils.getEndOfDay(now)
        val oldestDate = expenseDao.getOldestExpenseDate() ?: now
        
        // Use up to 3 months of history, but at least 1 month if available
        // If data is less than 15 days, results might be unreliable, but we'll try to extrapolate conservatively
        val (threeMonthsAgo, _) = TimePeriodUtils.getLastNDaysRange(now, 90)
        val effectiveStart = maxOf(oldestDate, threeMonthsAgo)

        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(effectiveStart).atZone(zone).toLocalDate()
        val endDateExclusive = Instant.ofEpochMilli(endExclusive).atZone(zone).toLocalDate()
        val daysDiff = ChronoUnit.DAYS.between(startDate, endDateExclusive).toInt().coerceAtLeast(1)
        
        // If we have very little data (e.g. < 7 days), skip suggestions to avoid noise (LOG-010)
        if (daysDiff < 7) return emptyList()

        val daysInCurrentMonth = TimePeriodUtils.getDaysInMonth(now).coerceAtLeast(1)
        val monthsDivisor = daysDiff.toDouble() / daysInCurrentMonth.toDouble()
        
        for (category in categories) {
            if (categoriesWithBudget.contains(category.id)) continue

            val spent = expenseDao.getCategorySpentInPeriod(category.id, effectiveStart, endExclusive)
            
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

    suspend fun updateExceededNotification(id: Long, timestamp: Long) {
        budgetDao.updateExceededNotification(id, timestamp)
    }

    suspend fun updateCriticalNotification(id: Long, timestamp: Long) {
        budgetDao.updateCriticalNotification(id, timestamp)
    }

    suspend fun updateWarningNotification(id: Long, timestamp: Long) {
        budgetDao.updateWarningNotification(id, timestamp)
    }
}
