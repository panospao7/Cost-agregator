package com.yourname.expensetracker.ui.navigation

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Contract test verifying that every [NavigationDestination] variant can roundtrip
 * through [NavigationDestination.toSaveToken] / [destinationFromSaveToken].
 *
 * These tests ensure the serialisation contract is preserved — any change to the
 * route format or to a destination's parameters MUST also update the serialisation
 * logic so that persisted state (savedInstance, deep links) does not break.
 */
class NavigationRouteContractTest {

    @Before
    fun setup() {
        // Uri.encode/Uri.decode are used by buildSaveToken/parseSaveToken.
        // In unit tests android.net.Uri methods return null by default, so we
        // mock them to be identity functions (pass-through).
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
        every { Uri.decode(any()) } answers { firstArg<String>() }
    }

    // Simple data objects (Home etc.) roundtrip in `all data object destinations roundtrip`
    // and their exact tokens are pinned in `route tokens match expected format`.
    // Parameterised destinations (Analytics/Transactions/...) roundtrip with exact tokens in
    // `all parameterized destinations roundtrip`.

    // ── Unknown route fails safely ──────────────────────────────────────────────

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
        // "home?" parses as base="home" with empty query → valid Home destination
        assertEquals(NavigationDestination.Home, destinationFromSaveToken("home?"))
    }

    // ── Test 5: Edge-case parameters ────────────────────────────────────────────

    // Null-parameter destinations (bare "transactions" / "analytics" tokens) are rows of
    // `all parameterized destinations roundtrip`.

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

    // SpendingMap / BudgetDetail / VisualSplitEditor(templateId) roundtrips are rows of
    // `all parameterized destinations roundtrip`.

    @Test
    fun `visual split editor financial fields stay out of persisted tokens`() {
        val original = NavigationDestination.VisualSplitEditor(
            templateId = 1L,
            expenseId = 2L,
            expenseAmount = 100.50,
            expenseCurrency = "USD",
            expense = null
        )
        val token = original.toSaveToken()
        val restored = destinationFromSaveToken(token)
        assertTrue(token.contains("templateId=1"))
        assertTrue(token.contains("expenseId=2"))
        assertFalse(token.contains("expenseAmount"))
        assertFalse(token.contains("expenseCurrency"))
        assertEquals(100.50, original.resolvedExpenseAmount ?: -1.0, 0.001)
        assertEquals("USD", original.resolvedExpenseCurrency)
        restored as NavigationDestination.VisualSplitEditor
        assertNull(restored.expenseAmount)
        assertNull(restored.expenseCurrency)
        assertEquals(2L, restored.resolvedExpenseId)
    }

    // forTemplateCreation / forTemplateEdit, SpendingChallenges (true/false; false is not
    // serialised) and BudgetForecasting (entity intentionally not serialised) roundtrips are
    // rows of `all parameterized destinations roundtrip`.

    // ── All data object (parameterless) destinations ────────────────────────────

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
            NavigationDestination.BudgetCreate,
            NavigationDestination.AiSettings,
            NavigationDestination.PrivacySettings,
            NavigationDestination.AppPinSettings,
            NavigationDestination.CategoryManagement,
            NavigationDestination.Installments,
            NavigationDestination.InstallmentHistory,
            NavigationDestination.Debug
        )

        // Completeness: the hand-written list must cover exactly the object subclasses of the
        // sealed hierarchy, so a newly added (or forgotten) destination fails here.
        val expectedObjectClasses = leafDestinationClasses(NavigationDestination::class)
            .filter { it.objectInstance != null }
            .toSet()
        val coveredClasses = destinations.map { it::class }.toSet()
        assertEquals(
            "Object destinations missing from list: " +
                "${(expectedObjectClasses - coveredClasses).map { it.simpleName }}; " +
                "unexpected entries: ${(coveredClasses - expectedObjectClasses).map { it.simpleName }}",
            expectedObjectClasses,
            coveredClasses
        )
        assertEquals(
            "Duplicate entries in object destination list",
            destinations.size,
            coveredClasses.size
        )

        for (dest in destinations) {
            val token = dest.toSaveToken()
            val restored = destinationFromSaveToken(token)
            assertEquals("Roundtrip failed for $dest (token=$token)", dest, restored)
        }
    }

    /** Flattens nested sealed sub-hierarchies down to concrete (non-sealed) subclasses. */
    private fun leafDestinationClasses(
        root: KClass<out NavigationDestination>
    ): Set<KClass<out NavigationDestination>> =
        root.sealedSubclasses.flatMap { sub ->
            if (sub.isSealed) leafDestinationClasses(sub) else listOf(sub)
        }.toSet()

    private class RoundtripCase(
        val destination: NavigationDestination,
        val expectedToken: String? = null
    )

    // ── Test 9: All parameterised destinations (varied params) ──────────────────

    @Test
    fun `all parameterized destinations roundtrip`() {
        // Each row is (destination, exactToken). A null expectedToken means only the roundtrip
        // is asserted for that row; a non-null token pins the exact persisted format.
        val cases = listOf(
            // Transactions (null id -> bare token, no params)
            RoundtripCase(NavigationDestination.Transactions(initialExpenseId = null), "transactions"),
            RoundtripCase(NavigationDestination.Transactions(initialExpenseId = 0L)),
            RoundtripCase(NavigationDestination.Transactions(initialExpenseId = Long.MAX_VALUE)),
            RoundtripCase(NavigationDestination.Transactions(initialExpenseId = 123L), "transactions?expenseId=123"),
            // Analytics (empty string is equivalent to null by design)
            RoundtripCase(NavigationDestination.Analytics(initialPeriod = null), "analytics"),
            RoundtripCase(NavigationDestination.Analytics(initialPeriod = "MONTH"), "analytics?period=MONTH"),
            RoundtripCase(NavigationDestination.Analytics(initialPeriod = "YEAR")),
            RoundtripCase(NavigationDestination.Analytics(initialPeriod = "CUSTOM_2026-01_2026-03")),
            // BudgetDetail
            RoundtripCase(NavigationDestination.BudgetDetail(categoryId = null, categoryName = null), "budget_detail"),
            RoundtripCase(NavigationDestination.BudgetDetail(categoryId = 1L, categoryName = null)),
            RoundtripCase(NavigationDestination.BudgetDetail(categoryId = null, categoryName = "Food")),
            RoundtripCase(NavigationDestination.BudgetDetail(categoryId = 5L, categoryName = "Transport")),
            RoundtripCase(NavigationDestination.BudgetDetail(categoryId = 42L, categoryName = "Groceries")),
            RoundtripCase(NavigationDestination.BudgetDetail(categoryId = 7L, categoryName = null)),
            // SpendingMap (empty string is equivalent to null by design)
            RoundtripCase(NavigationDestination.SpendingMap(initialLocationQuery = null), "spending_map"),
            RoundtripCase(NavigationDestination.SpendingMap(initialLocationQuery = "New York, NY")),
            RoundtripCase(NavigationDestination.SpendingMap(initialLocationQuery = "London")),
            // SpendingChallenges (false is not serialised)
            RoundtripCase(NavigationDestination.SpendingChallenges(showCreateDialog = false), "spending_challenges"),
            RoundtripCase(NavigationDestination.SpendingChallenges(showCreateDialog = true)),
            // InstallmentPlanDetail (loan-installments S10a)
            RoundtripCase(NavigationDestination.InstallmentPlanDetail(planId = 42L), "installment_plan?id=42"),
            RoundtripCase(NavigationDestination.InstallmentPlanDetail(planId = 1L)),
            // BudgetForecasting (entity intentionally not serialised)
            RoundtripCase(NavigationDestination.BudgetForecasting(budget = null), "budget_forecasting"),
            // VisualSplitEditor
            RoundtripCase(
                NavigationDestination.VisualSplitEditor(
                    templateId = null, expenseId = null,
                    expenseAmount = null, expenseCurrency = null, expense = null
                )
            ),
            RoundtripCase(
                NavigationDestination.VisualSplitEditor(
                    templateId = 1L, expenseId = null,
                    expenseAmount = null, expenseCurrency = null, expense = null
                )
            ),
            RoundtripCase(
                NavigationDestination.VisualSplitEditor(
                    templateId = 15L, expenseId = null,
                    expenseAmount = null, expenseCurrency = null, expense = null
                )
            ),
            RoundtripCase(
                NavigationDestination.VisualSplitEditor(
                    templateId = null, expenseId = 2L,
                    expenseAmount = null, expenseCurrency = null, expense = null
                )
            ),
            RoundtripCase(
                NavigationDestination.VisualSplitEditor(
                    templateId = 3L, expenseId = 4L,
                    expenseAmount = null, expenseCurrency = null, expense = null
                )
            ),
            RoundtripCase(NavigationDestination.VisualSplitEditor.forTemplateCreation()),
            RoundtripCase(NavigationDestination.VisualSplitEditor.forTemplateEdit(88L)),
            RoundtripCase(NavigationDestination.VisualSplitEditor.forTemplateEdit(templateId = 99L))
        )

        // Completeness: the rows must cover exactly the parameterised (non-object) subclasses of
        // the sealed hierarchy, so a newly added data-class destination fails here until a row exists.
        val expectedParameterizedClasses = leafDestinationClasses(NavigationDestination::class)
            .filter { it.objectInstance == null }
            .toSet()
        val coveredClasses = cases.map { it.destination::class }.toSet()
        assertEquals(
            "Parameterized destinations missing from table: " +
                "${(expectedParameterizedClasses - coveredClasses).map { it.simpleName }}; " +
                "unexpected entries: ${(coveredClasses - expectedParameterizedClasses).map { it.simpleName }}",
            expectedParameterizedClasses,
            coveredClasses
        )

        for (case in cases) {
            val dest = case.destination
            val token = dest.toSaveToken()
            case.expectedToken?.let { expected ->
                assertEquals("Unexpected token for $dest", expected, token)
            }
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

    @Test
    fun `installment_plan token with a non-positive or malformed id does not restore`() {
        assertNull(destinationFromSaveToken("installment_plan?id=0"))
        assertNull(destinationFromSaveToken("installment_plan?id=-7"))
        assertNull(destinationFromSaveToken("installment_plan?id=abc"))
        assertNull(destinationFromSaveToken("installment_plan"))
        assertEquals(
            NavigationDestination.InstallmentPlanDetail(planId = 5L),
            destinationFromSaveToken("installment_plan?id=5")
        )
    }

    // ── R19: deferred destinations are not restorable when unavailable ──────────

    @Test
    fun `deferred destinations do not restore when deferred surface unavailable`() {
        listOf(
            NavigationDestination.TaxConfiguration,
            NavigationDestination.InvestmentPortfolio,
            NavigationDestination.SpendingChallenges(),
            NavigationDestination.SpendingChallenges(showCreateDialog = true)
        ).forEach { destination ->
            val token = destination.toSaveToken()
            assertNull(
                "Token '$token' must not restore when deferred unavailable",
                destinationFromSaveToken(token, deferredAvailable = false)
            )
            assertNotNull(
                "Token '$token' must restore when deferred available",
                destinationFromSaveToken(token, deferredAvailable = true)
            )
        }
    }

    @Test
    fun `non-deferred destinations still restore when deferred surface unavailable`() {
        listOf(
            NavigationDestination.Home,
            NavigationDestination.SavingsGoals,
            NavigationDestination.PrivacySettings,
            NavigationDestination.BackupRestore
        ).forEach { destination ->
            assertEquals(
                destination,
                destinationFromSaveToken(destination.toSaveToken(), deferredAvailable = false)
            )
        }
    }

    @Test
    fun `restored back stack drops deferred entries and falls back when deferred unavailable`() {
        val tokens = listOf("home", "tax_configuration", "savings_goals", "spending_challenges?create=true")
        val restoredStack = tokens.mapNotNull {
            destinationFromSaveToken(it, bankSyncAvailable = true, deferredAvailable = false)
        }
        assertEquals(
            listOf(NavigationDestination.Home, NavigationDestination.SavingsGoals),
            restoredStack
        )
        // Process-death restore of a deferred current destination: null -> caller uses Home.
        val current = destinationFromSaveToken("investment_portfolio", deferredAvailable = false)
            ?: NavigationDestination.Home
        assertEquals(NavigationDestination.Home, current)
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
        assertEquals("budget_create", NavigationDestination.BudgetCreate.toSaveToken())
        assertEquals("privacy_settings", NavigationDestination.PrivacySettings.toSaveToken())
        assertEquals("debug", NavigationDestination.Debug.toSaveToken())
        assertEquals("app_pin_settings", NavigationDestination.AppPinSettings.toSaveToken())
    }

    @Test
    fun `S27 b2 the app_pin_settings token restores AppPinSettings and the screen is not a features-menu entry`() {
        assertEquals(NavigationDestination.AppPinSettings, destinationFromSaveToken("app_pin_settings"))
        assertEquals(
            NavigationDestination.AppPinSettings,
            destinationFromSaveToken(NavigationDestination.AppPinSettings.toSaveToken())
        )
        // Control: the sibling privacy token still resolves to its own screen, not to the PIN screen.
        assertEquals(NavigationDestination.PrivacySettings, destinationFromSaveToken("privacy_settings"))
        assertFalse(NavigationDestination.featureDestinations.contains(NavigationDestination.AppPinSettings))
        // Control: the parent screen stays a features-menu entry.
        assertTrue(NavigationDestination.featureDestinations.contains(NavigationDestination.PrivacySettings))
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
