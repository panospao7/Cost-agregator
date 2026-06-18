package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategory
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import kotlinx.coroutines.flow.Flow

interface DashboardExpenseRepository {
    fun observeDashboardExpenses(): Flow<List<DashboardExpense>>
}

interface DashboardCategoryRepository {
    fun observeDashboardCategories(): Flow<List<DashboardCategory>>
}

interface DashboardBudgetRepository {
    fun observeBudgetStatuses(): Flow<List<BudgetStatusSnapshot>>
}

interface DashboardReviewQueueRepository {
    fun observePendingReviewCount(): Flow<Int>
}

interface DashboardFinancialWeatherRepository {
    fun observeFinancialWeather(): Flow<FinancialWeather>
    fun observeRecurringPatterns(): Flow<List<RecurringPattern>>
    fun observePlannedExpenses(): Flow<List<PlannedExpense>>
}

interface DashboardSavingsGoalRepository {
    fun observeSavingsGoals(): Flow<List<SavingsGoal>>
}

interface DashboardAnalyticsRepository {
    fun observeSpendingSummary(start: Long, end: Long): Flow<SpendingSummary>
    fun observeCategoryBreakdown(start: Long, end: Long): Flow<List<DashboardCategoryBreakdown>>
}
