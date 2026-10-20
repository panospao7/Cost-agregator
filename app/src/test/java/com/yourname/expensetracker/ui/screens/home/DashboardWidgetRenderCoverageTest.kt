package com.yourname.expensetracker.ui.screens.home

import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Contract test ensuring every DashboardWidget subclass is explicitly handled
 * in the HomeScreen render block.
 *
 * When a new DashboardWidget variant is added, this test fails until the
 * developer adds it to the RENDERED_WIDGETS set — forcing them to also
 * add render logic in HomeScreen.
 */
class DashboardWidgetRenderCoverageTest {

    /**
     * Every DashboardWidget subclass that has render logic in HomeScreen.
     * Update this set when adding a new widget AND its render code.
     */
    private val RENDERED_WIDGETS: Set<KClass<out DashboardWidget>> = setOf(
        DashboardWidget.SafeToSpend::class,
        DashboardWidget.BudgetBlockParty::class,
        DashboardWidget.SpendingPaceWidget::class,
        DashboardWidget.PendingReviewAlert::class,
        DashboardWidget.PeriodSummary::class,
        DashboardWidget.TopCategories::class,
        DashboardWidget.BudgetHealthWidget::class,
        DashboardWidget.RecentTransactions::class,
        DashboardWidget.NaturalLanguageInsight::class,
        DashboardWidget.SpendingTrend::class,
        DashboardWidget.FinancialWeatherWidget::class,
        DashboardWidget.FinancialRunway::class,
        DashboardWidget.MonteCarloForecast::class,
        DashboardWidget.TotalsDashboard::class,
        DashboardWidget.NoSpendStreak::class,
        DashboardWidget.FinancialHealthScoreWidget::class,
        DashboardWidget.FinancialHealthScoreV2Widget::class,
        DashboardWidget.LifestyleSavingsPrompt::class,
        DashboardWidget.SavingsSweepPrompt::class,
        DashboardWidget.SavingsSweepUnavailable::class,
        DashboardWidget.MoneyRadar::class,
        DashboardWidget.FinancialStressForecast::class
    )

    @Test
    fun `RENDERED_WIDGETS equals the DashboardWidget sealed subclasses exactly`() {
        val sealedSubclasses: Set<KClass<out DashboardWidget>> =
            DashboardWidget::class.sealedSubclasses.toSet()
        val missing = (sealedSubclasses - RENDERED_WIDGETS).map { it.simpleName ?: it.toString() }.sorted()
        val stale = (RENDERED_WIDGETS - sealedSubclasses).map { it.simpleName ?: it.toString() }.sorted()

        assertEquals(
            "RENDERED_WIDGETS is out of sync with DashboardWidget sealed subclasses. " +
                "New DashboardWidget subclass(es) without render coverage: $missing. " +
                "Stale entries in RENDERED_WIDGETS (widget removed but test not updated): $stale. " +
                "Add render logic in HomeScreen AND add to RENDERED_WIDGETS in this test.",
            sealedSubclasses,
            RENDERED_WIDGETS
        )
    }
}
