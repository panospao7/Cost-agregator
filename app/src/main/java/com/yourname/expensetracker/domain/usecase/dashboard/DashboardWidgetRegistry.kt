package com.yourname.expensetracker.domain.usecase.dashboard

/**
 * S4-001R: Full metadata registry — single source of truth for every widget property.
 */
data class DashboardWidgetMeta(
    val id: String,
    val defaultOrder: Int,
    val defaultVisible: Boolean = true,
    val fullSpan: Boolean = true,
    val styleable: Boolean = false,
    /** S4-D914-001: Stable test tag for UI automation selectors. */
    val testTag: String = "widget_$id"
)

object DashboardWidgetRegistry {

    // ── ID constants ──────────────────────────────────────────────────────────
    const val SAFE_TO_SPEND             = "safe_to_spend"
    const val SPENDING_PACE             = "spending_pace"
    const val REVIEW_ALERT              = "review_alert"
    const val SPENDING_TREND            = "spending_trend"
    const val INSIGHT                   = "insight"
    const val PERIOD_SUMMARY            = "period_summary"
    const val BUDGET_HEALTH             = "budget_health"
    const val TOP_CATEGORIES            = "top_categories"
    const val RECENT_TRANSACTIONS       = "recent_transactions"
    const val FINANCIAL_WEATHER         = "financial_weather"
    const val BUDGET_BLOCK_PARTY        = "budget_block_party"
    const val FINANCIAL_RUNWAY          = "financial_runway"
    const val TOTALS_DASHBOARD          = "totals_dashboard"
    const val MONTE_CARLO_FORECAST      = "monte_carlo_forecast"
    const val NO_SPEND_STREAK           = "no_spend_streak"
    const val FINANCIAL_HEALTH_SCORE    = "financial_health_score"
    const val FINANCIAL_HEALTH_SCORE_V2 = "financial_health_score_v2"
    const val LIFESTYLE_SAVINGS         = "lifestyle_savings_prompt"
    const val MONEY_RADAR               = "money_radar"
    const val FINANCIAL_STRESS          = "financial_stress_forecast"
    const val SAVINGS_SWEEP             = "savings_sweep_prompt"

    // ── Metadata table ────────────────────────────────────────────────────────
    val all: List<DashboardWidgetMeta> = listOf(
        DashboardWidgetMeta(FINANCIAL_WEATHER,         0,  fullSpan = true),
        DashboardWidgetMeta(MONEY_RADAR,               1,  fullSpan = true),
        DashboardWidgetMeta(FINANCIAL_STRESS,          2,  fullSpan = true),
        DashboardWidgetMeta(LIFESTYLE_SAVINGS,         3,  fullSpan = true),
        DashboardWidgetMeta(SAVINGS_SWEEP,             4,  fullSpan = true),
        DashboardWidgetMeta(FINANCIAL_HEALTH_SCORE_V2, 5,  fullSpan = true),
        DashboardWidgetMeta(FINANCIAL_HEALTH_SCORE,    6,  fullSpan = true),
        DashboardWidgetMeta(TOTALS_DASHBOARD,          7,  fullSpan = true,  styleable = true),
        DashboardWidgetMeta(NO_SPEND_STREAK,           8,  fullSpan = true),
        DashboardWidgetMeta(SAFE_TO_SPEND,             9,  fullSpan = true),
        DashboardWidgetMeta(FINANCIAL_RUNWAY,          10, fullSpan = true),
        DashboardWidgetMeta(MONTE_CARLO_FORECAST,      11, fullSpan = true),
        DashboardWidgetMeta(SPENDING_PACE,             12, fullSpan = false),
        DashboardWidgetMeta(REVIEW_ALERT,              13, fullSpan = false),
        DashboardWidgetMeta(SPENDING_TREND,            14, fullSpan = true),
        DashboardWidgetMeta(INSIGHT,                   15, fullSpan = true),
        DashboardWidgetMeta(PERIOD_SUMMARY,            16, fullSpan = true),
        DashboardWidgetMeta(BUDGET_HEALTH,             17, fullSpan = true),
        DashboardWidgetMeta(TOP_CATEGORIES,            18, fullSpan = true,  styleable = true),
        DashboardWidgetMeta(RECENT_TRANSACTIONS,       19, fullSpan = true),
        DashboardWidgetMeta(BUDGET_BLOCK_PARTY,        20, fullSpan = true,  styleable = true),
    )

    /** All known IDs — for contract tests and unknown-ID detection. */
    val allIds: Set<String> = all.map { it.id }.toSet()

    /** IDs of widgets that support style switching — replaces StyledWidgets.all. */
    val styleableIds: Set<String> = all.filter { it.styleable }.map { it.id }.toSet()

    /** Metadata lookup by ID. */
    private val byId: Map<String, DashboardWidgetMeta> = all.associateBy { it.id }
    fun metaFor(id: String): DashboardWidgetMeta? = byId[id]

    /** Maps a DashboardWidget instance to its canonical ID. */
    fun idFor(widget: DashboardWidget): String = when (widget) {
        is DashboardWidget.SafeToSpend                  -> SAFE_TO_SPEND
        is DashboardWidget.SpendingPaceWidget           -> SPENDING_PACE
        is DashboardWidget.PendingReviewAlert           -> REVIEW_ALERT
        is DashboardWidget.SpendingTrend                -> SPENDING_TREND
        is DashboardWidget.NaturalLanguageInsight       -> INSIGHT
        is DashboardWidget.PeriodSummary                -> PERIOD_SUMMARY
        is DashboardWidget.BudgetHealthWidget           -> BUDGET_HEALTH
        is DashboardWidget.TopCategories                -> TOP_CATEGORIES
        is DashboardWidget.RecentTransactions           -> RECENT_TRANSACTIONS
        is DashboardWidget.FinancialWeatherWidget       -> FINANCIAL_WEATHER
        is DashboardWidget.BudgetBlockParty             -> BUDGET_BLOCK_PARTY
        is DashboardWidget.FinancialRunway              -> FINANCIAL_RUNWAY
        is DashboardWidget.TotalsDashboard              -> TOTALS_DASHBOARD
        is DashboardWidget.MonteCarloForecast           -> MONTE_CARLO_FORECAST
        is DashboardWidget.NoSpendStreak                -> NO_SPEND_STREAK
        is DashboardWidget.FinancialHealthScoreWidget   -> FINANCIAL_HEALTH_SCORE
        is DashboardWidget.FinancialHealthScoreV2Widget -> FINANCIAL_HEALTH_SCORE_V2
        is DashboardWidget.LifestyleSavingsPrompt       -> LIFESTYLE_SAVINGS
        is DashboardWidget.MoneyRadar                   -> MONEY_RADAR
        is DashboardWidget.FinancialStressForecast      -> FINANCIAL_STRESS
        is DashboardWidget.SavingsSweepPrompt           -> SAVINGS_SWEEP
    }

    /** Whether a widget should span both grid columns. Derived from metadata. */
    fun isFullSpan(widget: DashboardWidget): Boolean =
        metaFor(idFor(widget))?.fullSpan ?: true
}
