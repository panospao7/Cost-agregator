package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget

/**
 * S4-001: Canonical registry for dashboard widget IDs.
 * Single source of truth — replaces the local `getWidgetId` in HomeViewModel.
 * All widget config IDs, style IDs, and render branches must use these constants.
 */
object DashboardWidgetRegistry {

    const val SAFE_TO_SPEND           = "safe_to_spend"
    const val SPENDING_PACE           = "spending_pace"
    const val REVIEW_ALERT            = "review_alert"
    const val SPENDING_TREND          = "spending_trend"
    const val INSIGHT                 = "insight"
    const val PERIOD_SUMMARY          = "period_summary"
    const val BUDGET_HEALTH           = "budget_health"
    const val TOP_CATEGORIES          = "top_categories"
    const val RECENT_TRANSACTIONS     = "recent_transactions"
    const val FINANCIAL_WEATHER       = "financial_weather"
    const val BUDGET_BLOCK_PARTY      = "budget_block_party"
    const val FINANCIAL_RUNWAY        = "financial_runway"
    const val TOTALS_DASHBOARD        = "totals_dashboard"
    const val MONTE_CARLO_FORECAST    = "monte_carlo_forecast"
    const val NO_SPEND_STREAK         = "no_spend_streak"
    const val FINANCIAL_HEALTH_SCORE  = "financial_health_score"
    const val FINANCIAL_HEALTH_SCORE_V2 = "financial_health_score_v2"
    const val LIFESTYLE_SAVINGS       = "lifestyle_savings_prompt"
    const val MONEY_RADAR             = "money_radar"
    const val FINANCIAL_STRESS        = "financial_stress_forecast"
    const val SAVINGS_SWEEP           = "savings_sweep_prompt"

    /** All known widget IDs — used for contract tests and config validation. */
    val allIds: Set<String> = setOf(
        SAFE_TO_SPEND, SPENDING_PACE, REVIEW_ALERT, SPENDING_TREND, INSIGHT,
        PERIOD_SUMMARY, BUDGET_HEALTH, TOP_CATEGORIES, RECENT_TRANSACTIONS,
        FINANCIAL_WEATHER, BUDGET_BLOCK_PARTY, FINANCIAL_RUNWAY, TOTALS_DASHBOARD,
        MONTE_CARLO_FORECAST, NO_SPEND_STREAK, FINANCIAL_HEALTH_SCORE,
        FINANCIAL_HEALTH_SCORE_V2, LIFESTYLE_SAVINGS, MONEY_RADAR,
        FINANCIAL_STRESS, SAVINGS_SWEEP
    )

    /** Maps a DashboardWidget instance to its canonical ID. */
    fun idFor(widget: DashboardWidget): String = when (widget) {
        is DashboardWidget.SafeToSpend               -> SAFE_TO_SPEND
        is DashboardWidget.SpendingPaceWidget        -> SPENDING_PACE
        is DashboardWidget.PendingReviewAlert        -> REVIEW_ALERT
        is DashboardWidget.SpendingTrend             -> SPENDING_TREND
        is DashboardWidget.NaturalLanguageInsight    -> INSIGHT
        is DashboardWidget.PeriodSummary             -> PERIOD_SUMMARY
        is DashboardWidget.BudgetHealthWidget        -> BUDGET_HEALTH
        is DashboardWidget.TopCategories             -> TOP_CATEGORIES
        is DashboardWidget.RecentTransactions        -> RECENT_TRANSACTIONS
        is DashboardWidget.FinancialWeatherWidget    -> FINANCIAL_WEATHER
        is DashboardWidget.BudgetBlockParty          -> BUDGET_BLOCK_PARTY
        is DashboardWidget.FinancialRunway           -> FINANCIAL_RUNWAY
        is DashboardWidget.TotalsDashboard           -> TOTALS_DASHBOARD
        is DashboardWidget.MonteCarloForecast        -> MONTE_CARLO_FORECAST
        is DashboardWidget.NoSpendStreak             -> NO_SPEND_STREAK
        is DashboardWidget.FinancialHealthScoreWidget -> FINANCIAL_HEALTH_SCORE
        is DashboardWidget.FinancialHealthScoreV2Widget -> FINANCIAL_HEALTH_SCORE_V2
        is DashboardWidget.LifestyleSavingsPrompt    -> LIFESTYLE_SAVINGS
        is DashboardWidget.MoneyRadar                -> MONEY_RADAR
        is DashboardWidget.FinancialStressForecast   -> FINANCIAL_STRESS
        is DashboardWidget.SavingsSweepPrompt        -> SAVINGS_SWEEP
    }
}
