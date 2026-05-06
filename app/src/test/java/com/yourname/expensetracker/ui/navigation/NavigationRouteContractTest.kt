package com.yourname.expensetracker.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test verifying that every [NavigationDestination] variant can roundtrip
 * through [NavigationDestination.toSaveToken] / [destinationFromSaveToken].
 *
 * These tests ensure the serialisation contract is preserved — any change to the
 * route format or to a destination's parameters MUST also update the serialisation
 * logic so that persisted state (savedInstance, deep links) does not break.
 */
class NavigationRouteContractTest {

    // ── Test 1: Simple data objects ────────────────────────────────────────────

    @Test
    fun `home destination roundtrips`() {
        val original = NavigationDestination.Home
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals("home", token)
        assertEquals(original, restored)
    }

    // ── Test 2: Parameterised destinations ──────────────────────────────────────

    @Test
    fun `parameterized destinations roundtrip`() {
        val original = NavigationDestination.Analytics(initialPeriod = "MONTH")
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertTrue(token.startsWith("analytics?"))
        assertTrue(token.contains("period=MONTH"))
        assertEquals(original, restored)
    }

    // ── Test 3: Transactions with expenseId ─────────────────────────────────────

    @Test
    fun `transactions destination with expenseId roundtrips`() {
        val original = NavigationDestination.Transactions(initialExpenseId = 123L)
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertTrue(token.startsWith("transactions?"))
        assertTrue(token.contains("expenseId=123"))
        assertNotNull(restored)
        restored as NavigationDestination.Transactions
        assertEquals(123L, restored.initialExpenseId)
    }

    // ── Test 4: Unknown route fails safely ──────────────────────────────────────

    @Test
    fun `unknown route fails safely`() {
        val result = destinationFromSaveToken("nonexistent_route")
        assertNull(result)
    }

    @Test
    fun `malformed tokens return null`() {
        assertNull(destinationFromSaveToken(""))
        assertNull(destinationFromSaveToken("?key=value"))
        assertNull(destinationFromSaveToken(" "))
        assertNull(destinationFromSaveToken("   "))
        assertNull(destinationFromSaveToken("home?"))   // base token "home?" — no match
    }

    // ── Test 5: Edge-case parameters ────────────────────────────────────────────

    @Test
    fun `transactions destination with null expenseId roundtrips`() {
        val original = NavigationDestination.Transactions(initialExpenseId = null)
        val token = original.toSaveToken()
        assertEquals("transactions", token)   // no params → bare token
        val restored = destinationFromSaveToken(token)
        restored as NavigationDestination.Transactions
        assertNull(restored.initialExpenseId)
    }

    @Test
    fun `analytics destination with null period roundtrips`() {
        val original = NavigationDestination.Analytics(initialPeriod = null)
        val token = original.toSaveToken()
        assertEquals("analytics", token)
        val restored = destinationFromSaveToken(token)
        restored as NavigationDestination.Analytics
        assertNull(restored.initialPeriod)
    }

    @Test
    fun `analytics destination with empty period roundtrips`() {
        // Empty string should be treated like null (filtered by buildSaveToken)
        val original = NavigationDestination.Analytics(initialPeriod = "")
        val token = original.toSaveToken()
        assertEquals("analytics", token)
        val restored = destinationFromSaveToken(token)
        restored as NavigationDestination.Analytics
        assertNull(restored.initialPeriod)
    }

    @Test
    fun `spending map with location roundtrips`() {
        val original = NavigationDestination.SpendingMap(initialLocationQuery = "New York, NY")
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `spending map with null location roundtrips`() {
        val original = NavigationDestination.SpendingMap(initialLocationQuery = null)
        val token = original.toSaveToken()
        assertEquals("spending_map", token)
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `budget detail with categoryId and categoryName roundtrips`() {
        val original = NavigationDestination.BudgetDetail(
            categoryId = 42L,
            categoryName = "Groceries"
        )
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `budget detail with null fields roundtrips`() {
        val original = NavigationDestination.BudgetDetail(
            categoryId = null,
            categoryName = null
        )
        val token = original.toSaveToken()
        assertEquals("budget_detail", token)
        val restored = destinationFromSaveToken(token)
        restored as NavigationDestination.BudgetDetail
        assertNull(restored.categoryId)
        assertNull(restored.categoryName)
    }

    @Test
    fun `budget detail with only categoryId roundtrips`() {
        val original = NavigationDestination.BudgetDetail(
            categoryId = 7L,
            categoryName = null
        )
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `visual split editor with templateId roundtrips`() {
        val original = NavigationDestination.VisualSplitEditor(
            templateId = 15L,
            expenseId = null,
            expenseAmount = null,
            expenseCurrency = null,
            expense = null
        )
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `visual split editor with all fields roundtrips`() {
        val original = NavigationDestination.VisualSplitEditor(
            templateId = 1L,
            expenseId = 2L,
            expenseAmount = 100.50,
            expenseCurrency = "USD",
            expense = null
        )
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `visual split editor for template creation roundtrips`() {
        val original = NavigationDestination.VisualSplitEditor.forTemplateCreation()
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `visual split editor for template edit roundtrips`() {
        val original = NavigationDestination.VisualSplitEditor.forTemplateEdit(templateId = 99L)
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    // ── Test 6: SpendingChallenges boolean parameter ────────────────────────────

    @Test
    fun `spending challenges with showCreateDialog true roundtrips`() {
        val original = NavigationDestination.SpendingChallenges(showCreateDialog = true)
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    @Test
    fun `spending challenges with showCreateDialog false roundtrips`() {
        val original = NavigationDestination.SpendingChallenges(showCreateDialog = false)
        val token = original.toSaveToken()
        assertEquals("spending_challenges", token)  // false is not serialised
        val restored = destinationFromSaveToken(token)
        assertEquals(original, restored)
    }

    // ── Test 7: BudgetForecasting (entity intentionally not serialised) ─────────

    @Test
    fun `budget forecasting with null budget roundtrips`() {
        val original = NavigationDestination.BudgetForecasting(budget = null)
        val token = original.toSaveToken()
        assertEquals("budget_forecasting", token)
        val restored = destinationFromSaveToken(token)
        restored as NavigationDestination.BudgetForecasting
        assertNull(restored.budget)
    }

    // ── Test 8: All data object (parameterless) destinations ────────────────────

    @Test
    fun `all data object destinations roundtrip`() {
        val destinations = listOf<NavigationDestination>(
            NavigationDestination.Home,
            NavigationDestination.Assistant,
            NavigationDestination.Review,
            NavigationDestination.Budget,
            NavigationDestination.AddExpense,
            NavigationDestination.ScanReceipt,
            NavigationDestination.RecurringExpenses,
            NavigationDestination.ManualRecurringExpense,
            NavigationDestination.SavingsGoals,
            NavigationDestination.CarbonFootprint,
            NavigationDestination.WarrantyTracker,
            NavigationDestination.PriceProtection,
            NavigationDestination.BillNegotiation,
            NavigationDestination.SmartSearch,
            NavigationDestination.ReceiptMatching,
            NavigationDestination.InvestmentPortfolio,
            NavigationDestination.BankConnections,
            NavigationDestination.BillReminders,
            NavigationDestination.AdvancedAnalytics,
            NavigationDestination.CashFlowCalendar,
            NavigationDestination.LifestyleInflation,
            NavigationDestination.SplitTemplates,
            NavigationDestination.CurrencyManagement,
            NavigationDestination.SubscriptionManagement,
            NavigationDestination.TaxConfiguration,
            NavigationDestination.ExportOptions,
            NavigationDestination.BackupRestore,
            NavigationDestination.SharedExpenseGroups,
            NavigationDestination.AiSettings,
            NavigationDestination.CategoryManagement
        )
        for (dest in destinations) {
            val token = dest.toSaveToken()
            val restored = destinationFromSaveToken(token)
            assertEquals("Roundtrip failed for $dest (token=$token)", dest, restored)
        }
    }

    // ── Test 9: All parameterised destinations (varied params) ──────────────────

    @Test
    fun `all parameterized destinations roundtrip`() {
        val destinations = listOf<NavigationDestination>(
            // Transactions
            NavigationDestination.Transactions(initialExpenseId = null),
            NavigationDestination.Transactions(initialExpenseId = 0L),
            NavigationDestination.Transactions(initialExpenseId = Long.MAX_VALUE),
            // Analytics
            NavigationDestination.Analytics(initialPeriod = null),
            NavigationDestination.Analytics(initialPeriod = ""),
            NavigationDestination.Analytics(initialPeriod = "MONTH"),
            NavigationDestination.Analytics(initialPeriod = "YEAR"),
            NavigationDestination.Analytics(initialPeriod = "CUSTOM_2026-01_2026-03"),
            // BudgetDetail
            NavigationDestination.BudgetDetail(categoryId = null, categoryName = null),
            NavigationDestination.BudgetDetail(categoryId = 1L, categoryName = null),
            NavigationDestination.BudgetDetail(categoryId = null, categoryName = "Food"),
            NavigationDestination.BudgetDetail(categoryId = 5L, categoryName = "Transport"),
            // SpendingMap
            NavigationDestination.SpendingMap(initialLocationQuery = null),
            NavigationDestination.SpendingMap(initialLocationQuery = ""),
            NavigationDestination.SpendingMap(initialLocationQuery = "New York, NY"),
            NavigationDestination.SpendingMap(initialLocationQuery = "London"),
            // SpendingChallenges
            NavigationDestination.SpendingChallenges(showCreateDialog = false),
            NavigationDestination.SpendingChallenges(showCreateDialog = true),
            // BudgetForecasting
            NavigationDestination.BudgetForecasting(budget = null),
            // VisualSplitEditor
            NavigationDestination.VisualSplitEditor(
                templateId = null, expenseId = null,
                expenseAmount = null, expenseCurrency = null, expense = null
            ),
            NavigationDestination.VisualSplitEditor(
                templateId = 1L, expenseId = null,
                expenseAmount = null, expenseCurrency = null, expense = null
            ),
            NavigationDestination.VisualSplitEditor(
                templateId = null, expenseId = 2L,
                expenseAmount = null, expenseCurrency = null, expense = null
            ),
            NavigationDestination.VisualSplitEditor(
                templateId = null, expenseId = null,
                expenseAmount = 99.99, expenseCurrency = null, expense = null
            ),
            NavigationDestination.VisualSplitEditor(
                templateId = null, expenseId = null,
                expenseAmount = null, expenseCurrency = "EUR", expense = null
            ),
            NavigationDestination.VisualSplitEditor(
                templateId = 3L, expenseId = 4L,
                expenseAmount = 49.95, expenseCurrency = "USD", expense = null
            ),
            NavigationDestination.VisualSplitEditor.forTemplateCreation(),
            NavigationDestination.VisualSplitEditor.forTemplateEdit(88L)
        )
        for (dest in destinations) {
            val token = dest.toSaveToken()
            val restored = destinationFromSaveToken(token)
            assertEquals("Roundtrip failed for $dest (token=$token)", dest, restored)
        }
    }

    // ── Test 10: Backward-compatible old-format visual_split_editor ──────────────

    @Test
    fun `legacy visual_split_editor colon format is supported`() {
        // Old persisted format: "visual_split_editor:<templateId>"
        val restored = destinationFromSaveToken("visual_split_editor:42")
        assertNotNull(restored)
        restored as NavigationDestination.VisualSplitEditor
        assertEquals(42L, restored.templateId)
    }

    @Test
    fun `legacy visual_split_editor colon format without id defaults to creation`() {
        val restored = destinationFromSaveToken("visual_split_editor:")
        assertNotNull(restored)
        restored as NavigationDestination.VisualSplitEditor
        assertNull(restored.templateId)
    }

    // ── Test 11: Route token format contract (no accidental changes) ────────────

    @Test
    fun `route tokens match expected format`() {
        assertEquals("home", NavigationDestination.Home.toSaveToken())
        assertEquals("assistant", NavigationDestination.Assistant.toSaveToken())
        assertEquals("review", NavigationDestination.Review.toSaveToken())
        assertEquals("budget", NavigationDestination.Budget.toSaveToken())
        assertEquals("add_expense", NavigationDestination.AddExpense.toSaveToken())
        assertEquals("scan_receipt", NavigationDestination.ScanReceipt.toSaveToken())
        assertEquals("recurring_expenses", NavigationDestination.RecurringExpenses.toSaveToken())
        assertEquals("manual_recurring_expense", NavigationDestination.ManualRecurringExpense.toSaveToken())
        assertEquals("savings_goals", NavigationDestination.SavingsGoals.toSaveToken())
        assertEquals("carbon_footprint", NavigationDestination.CarbonFootprint.toSaveToken())
        assertEquals("warranty_tracker", NavigationDestination.WarrantyTracker.toSaveToken())
        assertEquals("price_protection", NavigationDestination.PriceProtection.toSaveToken())
        assertEquals("bill_negotiation", NavigationDestination.BillNegotiation.toSaveToken())
        assertEquals("smart_search", NavigationDestination.SmartSearch.toSaveToken())
        assertEquals("receipt_matching", NavigationDestination.ReceiptMatching.toSaveToken())
        assertEquals("investment_portfolio", NavigationDestination.InvestmentPortfolio.toSaveToken())
        assertEquals("bank_connections", NavigationDestination.BankConnections.toSaveToken())
        assertEquals("bill_reminders", NavigationDestination.BillReminders.toSaveToken())
        assertEquals("advanced_analytics", NavigationDestination.AdvancedAnalytics.toSaveToken())
        assertEquals("cash_flow_calendar", NavigationDestination.CashFlowCalendar.toSaveToken())
        assertEquals("lifestyle_inflation", NavigationDestination.LifestyleInflation.toSaveToken())
        assertEquals("split_templates", NavigationDestination.SplitTemplates.toSaveToken())
        assertEquals("currency_management", NavigationDestination.CurrencyManagement.toSaveToken())
        assertEquals("subscription_management", NavigationDestination.SubscriptionManagement.toSaveToken())
        assertEquals("tax_configuration", NavigationDestination.TaxConfiguration.toSaveToken())
        assertEquals("export_options", NavigationDestination.ExportOptions.toSaveToken())
        assertEquals("backup_restore", NavigationDestination.BackupRestore.toSaveToken())
        assertEquals("shared_expense_groups", NavigationDestination.SharedExpenseGroups.toSaveToken())
        assertEquals("budget_forecasting", NavigationDestination.BudgetForecasting().toSaveToken())
        assertEquals("ai_settings", NavigationDestination.AiSettings.toSaveToken())
        assertEquals("category_management", NavigationDestination.CategoryManagement.toSaveToken())
    }

    @Test
    fun `parameterized route tokens contain expected base`() {
        assertTrue(NavigationDestination.Transactions().toSaveToken().startsWith("transactions"))
        assertTrue(NavigationDestination.Analytics().toSaveToken().startsWith("analytics"))
        assertTrue(NavigationDestination.BudgetDetail(null, null).toSaveToken().startsWith("budget_detail"))
        assertTrue(NavigationDestination.SpendingMap(null).toSaveToken().startsWith("spending_map"))
        assertTrue(NavigationDestination.SpendingChallenges().toSaveToken().startsWith("spending_challenges"))
        assertTrue(NavigationDestination.VisualSplitEditor().toSaveToken().startsWith("visual_split_editor"))
    }
}
