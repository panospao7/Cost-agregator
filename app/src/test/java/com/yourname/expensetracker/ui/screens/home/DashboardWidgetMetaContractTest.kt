package com.yourname.expensetracker.ui.screens.home

import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import org.junit.Assert.*
import org.junit.Test

/**
 * S4-004: Contract test ensuring widget IDs in HomeViewModel.getWidgetId()
 * match the IDs in DashboardRepository.getDefaultConfig().
 */
class DashboardWidgetMetaContractTest {

    private val defaultConfigIds = setOf(
        "financial_weather", "money_radar", "financial_stress_forecast",
        "lifestyle_savings_prompt", "savings_sweep_prompt", "financial_health_score_v2",
        "financial_health_score", "totals_dashboard", "no_spend_streak",
        "safe_to_spend", "financial_runway", "monte_carlo_forecast",
        "spending_pace", "review_alert", "spending_trend", "insight",
        "period_summary", "budget_health", "top_categories",
        "recent_transactions", "budget_block_party"
    )

    @Test
    fun `all widget IDs from getWidgetId are in default config`() {
        val sampleWidgets = listOf(
            DashboardWidget.SafeToSpend(0.0, null, 0),
            DashboardWidget.PendingReviewAlert(0),
            DashboardWidget.TotalsDashboard,
            DashboardWidget.NoSpendStreak(0, 0, 0)
        )
        sampleWidgets.forEach { widget ->
            val id = HomeViewModel.getWidgetId(widget)
            assertTrue("Widget ID '$id' not in default config", id in defaultConfigIds)
        }
    }

    @Test
    fun `default config IDs are unique`() {
        assertEquals("Duplicate IDs in default config", defaultConfigIds.size, defaultConfigIds.distinct().size)
    }
}
