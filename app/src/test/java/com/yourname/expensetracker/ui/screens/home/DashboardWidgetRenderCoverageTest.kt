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
        DashboardWidget.MoneyRadar::class,
        DashboardWidget.FinancialStressForecast::class
    )

    @Test
    fun `all DashboardWidget subclasses have render coverage`() {
        val sealedSubclasses = DashboardWidget::class.sealedSubclasses
        val missing = sealedSubclasses.filter { it !in RENDERED_WIDGETS }

        assertTrue(
            "New DashboardWidget subclass(es) without render coverage: " +
            "${missing.map { it.simpleName }}. " +
            "Add render logic in HomeScreen AND add to RENDERED_WIDGETS in this test.",
            missing.isEmpty()
        )
    }

    @Test
    fun `RENDERED_WIDGETS does not contain stale entries`() {
        val sealedSubclasses = DashboardWidget::class.sealedSubclasses.toSet()
        val stale = RENDERED_WIDGETS.filter { it !in sealedSubclasses }

        assertTrue(
            "Stale entries in RENDERED_WIDGETS (widget removed but test not updated): " +
            "${stale.map { it.simpleName }}",
            stale.isEmpty()
        )
    }

    @Test
    fun `widget count matches expected`() {
        val count = DashboardWidget::class.sealedSubclasses.size
        assertEquals(
            "DashboardWidget subclass count changed. Update RENDERED_WIDGETS.",
            21, count
        )
    }
}
