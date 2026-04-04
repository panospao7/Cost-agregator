package com.yourname.expensetracker.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for HomeScreen widget dispatch.
 *
 * These are source-level tests to guard dispatch and routing branches,
 * especially around FinancialHealthScoreV2Widget regressions.
 */
class HomeScreenWidgetTest {

    @Test
    fun `FinancialHealthScoreV2Widget branch exists once only`() {
        val source = homeScreenSource()
        val needle = "is DashboardWidget.FinancialHealthScoreV2Widget ->"
        val count = Regex(Regex.escape(needle)).findAll(source).count()

        assertEquals("Expected exactly one when-branch for FinancialHealthScoreV2Widget", 1, count)
    }

    @Test
    fun `all dashboard widget types have explicit dispatch branch or fallback`() {
        val source = homeScreenSource()

        val widgetBranches = listOf(
            "SafeToSpend",
            "BudgetBlockParty",
            "SpendingPaceWidget",
            "PendingReviewAlert",
            "SpendingTrend",
            "NaturalLanguageInsight",
            "PeriodSummary",
            "BudgetHealthWidget",
            "TopCategories",
            "RecentTransactions",
            "FinancialWeatherWidget",
            "TotalsDashboard",
            "FinancialRunway",
            "MonteCarloForecast",
            "NoSpendStreak",
            "FinancialHealthScoreWidget",
            "FinancialHealthScoreV2Widget",
            "LifestyleSavingsPrompt",
            "MoneyRadar",
            "FinancialStressForecast",
            "SavingsSweepPrompt"
        )

        widgetBranches.forEach { widgetName ->
            assertTrue(
                "Missing dispatch for DashboardWidget.$widgetName",
                source.contains("is DashboardWidget.$widgetName ->")
            )
        }

        assertTrue(
            "Fallback branch for unhandled widget types should exist",
            source.contains("else -> {") && source.contains("Fallback for any unhandled widget types")
        )
    }

    @Test
    fun `navigation wiring exists for key widget actions`() {
        val source = homeScreenSource()

        assertTrue(source.contains("onClick = onNavigateToReview"))
        assertTrue(source.contains("onManageClick = onNavigateToRecurring"))

        // Transactions drilldown routes
        assertTrue(source.contains("is DashboardWidget.BudgetBlockParty ->"))
        assertTrue(source.contains("is DashboardWidget.SpendingTrend ->"))
        assertTrue(source.contains("onNavigateToTransactions("))

        // Feature destinations
        assertTrue(source.contains("NavigationDestination.SavingsGoals"))

        // Money Radar action routing
        assertTrue(source.contains("MoneyRadarAction.ViewBills"))
        assertTrue(source.contains("MoneyRadarAction.ReviewAnomalies"))
        assertTrue(source.contains("MoneyRadarAction.AdjustBudget"))
        assertTrue(source.contains("onNavigateToAnalytics()"))

        // Stress forecast recommendation routing
        assertTrue(source.contains("is DashboardWidget.FinancialStressForecast ->"))
        assertTrue(source.contains("recommendation.contains(\"subscriptions\""))
        assertTrue(source.contains("recommendation.contains(\"spending\""))
        assertTrue(source.contains("recommendation.contains(\"emergency\""))
    }

    @Test
    fun `edge-case guards exist for nullable or missing handlers`() {
        val source = homeScreenSource()

        // Nullable widget data handling
        assertTrue(source.contains("widget.summary?.asString() ?:"))
        assertTrue(source.contains("totalsDashboardWidget?.let"))
        assertTrue(source.contains("} ?: false"))

        // Missing handler defaults on HomeScreen params
        assertTrue(source.contains("onNavigateToAnalytics: () -> Unit = {}"))
        assertTrue(source.contains("onNavigateToMap: () -> Unit = {}"))
        assertTrue(source.contains("onNavigateToBudgetDetail: (String) -> Unit = {}"))
        assertTrue(source.contains("onNavigateToFeature: (NavigationDestination) -> Unit = {}"))

        // Explicit no-op branch for widget rendered elsewhere
        assertTrue(source.contains("is DashboardWidget.SavingsSweepPrompt ->"))
        assertTrue(source.contains("Savings Sweep Prompt widget - handled elsewhere"))
    }

    private fun homeScreenSource(): String {
        val candidates = listOf(
            "app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt",
            "src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt"
        )
        val file = candidates.asSequence().map(::File).firstOrNull { it.exists() }

        requireNotNull(file) {
            "Unable to locate HomeScreen.kt. Checked: ${candidates.joinToString()}"
        }

        return file.readText()
    }
}
