package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot

/**
 * CURR-587-06: Canonical normalized input for forecast/runway pipeline.
 *
 * All values are already normalized to home currency — no re-normalization
 * should occur downstream. This replaces the legacy path that accepted
 * raw ExpenseSnapshot and re-normalized via AnalyticsCurrencyNormalizer.
 */
data class NormalizedForecastInput(
    val homeCurrency: CurrencyCode,
    /** Normalized past expenses (already in home currency). */
    val normalizedExpenses: List<NormalizedExpense>,
    /** Cumulative daily spending totals (already in home currency). */
    val pastSumDaily: List<Double>,
    /** Recurring patterns (amounts still in original currency — normalization planned). */
    val recurringPatterns: List<RecurringPattern>,
    /** Planned expenses (amounts still in original currency — normalization planned). */
    val plannedExpenses: List<PlannedExpense>,
    val savingsGoals: List<SavingsGoal>,
    val budgetStatuses: List<BudgetStatusSnapshot>,
    val spendingPace: SpendingPace,
    val dataQuality: ForecastDataQuality = ForecastDataQuality()
)
