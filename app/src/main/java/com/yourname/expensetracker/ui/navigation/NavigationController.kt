package com.yourname.expensetracker.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque

data class NavigationControllerSnapshot(
    val destination: NavigationDestination,
    val backStack: List<NavigationDestination>,
    val previousMainTab: Int?
)

private data class PersistedNavigationState(
    val destinationToken: String,
    val backStackTokens: List<String>,
    val previousMainTab: Int?
) {
    companion object {
        val Saver: Saver<PersistedNavigationState, Any> = listSaver(
            save = {
                listOf(
                    it.destinationToken,
                    it.backStackTokens,
                    it.previousMainTab
                )
            },
            restore = { restored ->
                PersistedNavigationState(
                    destinationToken = restored[0] as String,
                    backStackTokens = restored[1] as List<String>,
                    previousMainTab = restored[2] as Int?
                )
            }
        )

        fun fromDestination(destination: NavigationDestination): PersistedNavigationState {
            return PersistedNavigationState(
                destinationToken = destination.toSaveToken(),
                backStackTokens = emptyList(),
                previousMainTab = null
            )
        }

        fun fromSnapshot(snapshot: NavigationControllerSnapshot): PersistedNavigationState {
            return PersistedNavigationState(
                destinationToken = snapshot.destination.toSaveToken(),
                backStackTokens = snapshot.backStack.map { it.toSaveToken() },
                previousMainTab = snapshot.previousMainTab
            )
        }
    }
}

private fun buildSaveToken(base: String, params: Map<String, String?>): String {
    val query = params
        .filterValues { !it.isNullOrBlank() }
        .entries
        .joinToString("&") { (key, value) ->
            "${Uri.encode(key)}=${Uri.encode(value)}"
        }

    return if (query.isBlank()) base else "$base?$query"
}

private fun parseSaveToken(token: String): Pair<String, Map<String, String>> {
    val base = token.substringBefore('?')
    val query = token.substringAfter('?', "")
    if (query.isBlank()) return base to emptyMap()

    val params = query
        .split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = pair.substringAfter('=', "")
            Uri.decode(key) to Uri.decode(value)
        }
        .toMap()

    return base to params
}

private fun NavigationDestination.toSaveToken(): String = when (this) {
    is NavigationDestination.Home -> "home"
    is NavigationDestination.Transactions -> buildSaveToken(
        base = "transactions",
        params = mapOf("expenseId" to initialExpenseId?.toString())
    )
    is NavigationDestination.Analytics -> buildSaveToken(
        base = "analytics",
        params = mapOf("period" to initialPeriod)
    )
    is NavigationDestination.Assistant -> "assistant"
    is NavigationDestination.Review -> "review"
    is NavigationDestination.Budget -> "budget"
    is NavigationDestination.BudgetDetail -> buildSaveToken(
        base = "budget_detail",
        params = mapOf(
            "categoryId" to categoryId?.toString(),
            "categoryName" to categoryName
        )
    )
    is NavigationDestination.SpendingMap -> buildSaveToken(
        base = "spending_map",
        params = mapOf("location" to initialLocationQuery)
    )
    is NavigationDestination.AddExpense -> "add_expense"
    is NavigationDestination.ScanReceipt -> "scan_receipt"
    is NavigationDestination.RecurringExpenses -> "recurring_expenses"
    is NavigationDestination.ManualRecurringExpense -> "manual_recurring_expense"
    is NavigationDestination.SavingsGoals -> "savings_goals"
    is NavigationDestination.CarbonFootprint -> "carbon_footprint"
    is NavigationDestination.WarrantyTracker -> "warranty_tracker"
    is NavigationDestination.PriceProtection -> "price_protection"
    is NavigationDestination.BillNegotiation -> "bill_negotiation"
    is NavigationDestination.SmartSearch -> "smart_search"
    is NavigationDestination.ReceiptMatching -> "receipt_matching"
    is NavigationDestination.InvestmentPortfolio -> "investment_portfolio"
    is NavigationDestination.BankConnections -> "bank_connections"
    is NavigationDestination.BillReminders -> "bill_reminders"
    is NavigationDestination.SpendingChallenges -> buildSaveToken(
        base = "spending_challenges",
        params = mapOf("create" to showCreateDialog.takeIf { it }?.toString())
    )
    is NavigationDestination.AdvancedAnalytics -> "advanced_analytics"
    is NavigationDestination.CashFlowCalendar -> "cash_flow_calendar"
    is NavigationDestination.LifestyleInflation -> "lifestyle_inflation"
    is NavigationDestination.SplitTemplates -> "split_templates"
    is NavigationDestination.VisualSplitEditor -> buildSaveToken(
        base = "visual_split_editor",
        params = mapOf(
            "templateId" to templateId?.toString(),
            "expenseId" to (expenseId ?: expense?.id)?.toString(),
            "expenseAmount" to (expenseAmount ?: expense?.amount)?.toString(),
            "expenseCurrency" to (expenseCurrency ?: expense?.currency)
        )
    )
    is NavigationDestination.CurrencyManagement -> "currency_management"
    is NavigationDestination.SubscriptionManagement -> "subscription_management"
    is NavigationDestination.TaxConfiguration -> "tax_configuration"
    is NavigationDestination.ExportOptions -> "export_options"
    is NavigationDestination.BackupRestore -> "backup_restore"
    is NavigationDestination.SharedExpenseGroups -> "shared_expense_groups"
    // Budget entity is intentionally not serialized; reopening null-budget route is safe.
    is NavigationDestination.BudgetForecasting -> "budget_forecasting"
    is NavigationDestination.AiSettings -> "ai_settings"
    is NavigationDestination.CategoryManagement -> "category_management"
}

private fun destinationFromSaveToken(token: String): NavigationDestination? {
    // Backward compatibility with older persisted format: visual_split_editor:<templateId>
    if (token.startsWith("visual_split_editor:")) {
        val templateId = token.substringAfter(':', "")
            .takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        return if (templateId != null) {
            NavigationDestination.VisualSplitEditor.forTemplateEdit(templateId)
        } else {
            NavigationDestination.VisualSplitEditor.forTemplateCreation()
        }
    }

    val (baseToken, params) = parseSaveToken(token)

    return when {
        baseToken == "home" -> NavigationDestination.Home
        baseToken == "transactions" -> NavigationDestination.Transactions(
            initialExpenseId = params["expenseId"]?.toLongOrNull()
        )
        baseToken == "analytics" -> NavigationDestination.Analytics(
            initialPeriod = params["period"]?.takeIf { it.isNotBlank() }
        )
        baseToken == "assistant" -> NavigationDestination.Assistant
        baseToken == "review" -> NavigationDestination.Review
        baseToken == "budget" -> NavigationDestination.Budget
        baseToken == "budget_detail" -> NavigationDestination.BudgetDetail(
            categoryId = params["categoryId"]?.toLongOrNull(),
            categoryName = params["categoryName"]?.takeIf { it.isNotBlank() }
        )
        baseToken == "spending_map" -> NavigationDestination.SpendingMap(
            initialLocationQuery = params["location"]?.takeIf { it.isNotBlank() }
        )
        baseToken == "add_expense" -> NavigationDestination.AddExpense
        baseToken == "scan_receipt" -> NavigationDestination.ScanReceipt
        baseToken == "recurring_expenses" -> NavigationDestination.RecurringExpenses
        baseToken == "manual_recurring_expense" -> NavigationDestination.ManualRecurringExpense
        baseToken == "savings_goals" -> NavigationDestination.SavingsGoals
        baseToken == "carbon_footprint" -> NavigationDestination.CarbonFootprint
        baseToken == "warranty_tracker" -> NavigationDestination.WarrantyTracker
        baseToken == "price_protection" -> NavigationDestination.PriceProtection
        baseToken == "bill_negotiation" -> NavigationDestination.BillNegotiation
        baseToken == "smart_search" -> NavigationDestination.SmartSearch
        baseToken == "receipt_matching" -> NavigationDestination.ReceiptMatching
        baseToken == "investment_portfolio" -> NavigationDestination.InvestmentPortfolio
        baseToken == "bank_connections" -> NavigationDestination.BankConnections
        baseToken == "bill_reminders" -> NavigationDestination.BillReminders
        baseToken == "spending_challenges" -> NavigationDestination.SpendingChallenges(
            showCreateDialog = params["create"]?.toBooleanStrictOrNull() == true
        )
        baseToken == "advanced_analytics" -> NavigationDestination.AdvancedAnalytics
        baseToken == "cash_flow_calendar" -> NavigationDestination.CashFlowCalendar
        baseToken == "lifestyle_inflation" -> NavigationDestination.LifestyleInflation
        baseToken == "split_templates" -> NavigationDestination.SplitTemplates
        baseToken == "visual_split_editor" -> {
            NavigationDestination.VisualSplitEditor(
                templateId = params["templateId"]?.toLongOrNull(),
                expenseId = params["expenseId"]?.toLongOrNull(),
                expenseAmount = params["expenseAmount"]?.toDoubleOrNull(),
                expenseCurrency = params["expenseCurrency"]?.takeIf { it.isNotBlank() }
            )
        }
        baseToken == "currency_management" -> NavigationDestination.CurrencyManagement
        baseToken == "subscription_management" -> NavigationDestination.SubscriptionManagement
        baseToken == "tax_configuration" -> NavigationDestination.TaxConfiguration
        baseToken == "export_options" -> NavigationDestination.ExportOptions
        baseToken == "backup_restore" -> NavigationDestination.BackupRestore
        baseToken == "shared_expense_groups" -> NavigationDestination.SharedExpenseGroups
        baseToken == "budget_forecasting" -> NavigationDestination.BudgetForecasting()
        baseToken == "ai_settings" -> NavigationDestination.AiSettings
        baseToken == "category_management" -> NavigationDestination.CategoryManagement
        else -> null
    }
}

/**
 * Controller for managing navigation state and providing navigation actions.
 * Use [LocalNavigationController] to access the controller in Composables.
 * 
 * Example usage:
 * ```kotlin
 * val navigation = LocalNavigationController.current
 * navigation.navigateTo(NavigationDestination.AddExpense)
 * ```
 */
class NavigationController(
    private val currentDestination: MutableState<NavigationDestination>,
    initialBackStack: List<NavigationDestination> = emptyList(),
    initialPreviousMainTab: Int? = null,
    private val onStateChanged: ((NavigationControllerSnapshot) -> Unit)? = null
) {
    private val backStack = ArrayDeque(initialBackStack)
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()
    private val _navigationResults = MutableSharedFlow<NavigationResult>(extraBufferCapacity = 1)
    val navigationResults: SharedFlow<NavigationResult> = _navigationResults.asSharedFlow()
    
    // Track the previous main tab to return to when navigating back from feature screens
    var previousMainTab: Int? = initialPreviousMainTab
        private set
    
    val destination: NavigationDestination
        get() = currentDestination.value
    
    /**
     * Navigate to a specific destination.
     * Saves current destination to back stack if it's a feature screen.
     * Also saves the current main tab index when navigating away from a main tab.
     */
    fun navigateTo(destination: NavigationDestination) {
        val current = currentDestination.value
        
        // Save current main tab before navigating away from it
        if (isMainTab(current)) {
            previousMainTab = getCurrentTabIndex()
        }
        
        // Only add to back stack if current is a feature (not main tabs)
        if (!isMainTab(current)) {
            backStack.addLast(current)
        }
        
        currentDestination.value = destination
        _navigationEvents.tryEmit(NavigationEvent.NavigateTo(destination))
        notifyStateChanged()
    }

    fun deliverResult(result: NavigationResult) {
        _navigationResults.tryEmit(result)
    }
    
    /**
     * Navigate back to the previous destination.
     * If no previous destination exists, navigates to the previous main tab or Home.
     */
    fun navigateBack(): Boolean {
        return if (backStack.isNotEmpty()) {
            val previous = backStack.removeLast()
            currentDestination.value = previous
            _navigationEvents.tryEmit(NavigationEvent.NavigateBack)
            notifyStateChanged()
            true
        } else {
            val currentTab = getCurrentTabIndex()
            when {
                // C2: On non-home main tabs, route back to Home before exiting app.
                currentTab != null && currentTab != 0 -> {
                    navigateToTab(0)
                    true
                }
                // Already on Home tab: allow system back to exit app.
                currentTab == 0 -> false
                // Feature screen with no stack: return to previous main tab (or Home).
                else -> {
                    navigateToTab(previousMainTab ?: 0)
                    true
                }
            }
        }
    }
    
    /**
     * Navigate to Home tab and clear feature back stack.
     */
    fun navigateHome() {
        backStack.clear()
        currentDestination.value = NavigationDestination.Home
        _navigationEvents.tryEmit(NavigationEvent.NavigateTo(NavigationDestination.Home))
        notifyStateChanged()
    }
    
    /**
     * Navigate to a specific tab by index (0-5).
     * This clears the feature back stack and updates the destination.
     */
    fun navigateToTab(tabIndex: Int) {
        clearBackStack()
        currentDestination.value = when (tabIndex) {
            0 -> NavigationDestination.Home
            1 -> NavigationDestination.Transactions()
            2 -> NavigationDestination.Review
            3 -> NavigationDestination.Budget
            4 -> NavigationDestination.Analytics()
            5 -> NavigationDestination.SpendingMap()
            else -> NavigationDestination.Home
        }
        _navigationEvents.tryEmit(NavigationEvent.NavigateTo(currentDestination.value))
        notifyStateChanged()
    }
    
    /**
     * Check if currently on a specific destination.
     */
    fun isCurrent(destination: NavigationDestination): Boolean {
        return currentDestination.value == destination
    }
    
    /**
     * Check if currently on any of the main tabs.
     */
    fun isOnMainTab(): Boolean {
        return isMainTab(currentDestination.value)
    }
    
    /**
     * Check if destination is a main tab (0-5).
     */
    private fun isMainTab(destination: NavigationDestination): Boolean {
        return when (destination) {
            is NavigationDestination.Home,
            is NavigationDestination.Transactions,
            is NavigationDestination.Review,
            is NavigationDestination.Budget,
            is NavigationDestination.BudgetDetail,
            is NavigationDestination.Analytics,
            is NavigationDestination.SpendingMap -> true
            else -> false
        }
    }
    
    /**
     * Get the current tab index (0-5) if on a main tab, null otherwise.
     */
    fun getCurrentTabIndex(): Int? {
        return when (currentDestination.value) {
            is NavigationDestination.Home -> 0
            is NavigationDestination.Transactions -> 1
            is NavigationDestination.Review -> 2
            is NavigationDestination.Budget -> 3
            is NavigationDestination.BudgetDetail -> 3
            is NavigationDestination.Analytics -> 4
            is NavigationDestination.SpendingMap -> 5
            else -> null
        }
    }
    
    /**
     * Check if there's anything in the back stack.
     */
    fun canNavigateBack(): Boolean {
        val currentTab = getCurrentTabIndex()
        return backStack.isNotEmpty() || !isOnMainTab() || (currentTab != null && currentTab != 0)
    }
    
    /**
     * Clear the back stack (e.g., when switching tabs).
     */
    fun clearBackStack() {
        backStack.clear()
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        onStateChanged?.invoke(
            NavigationControllerSnapshot(
                destination = currentDestination.value,
                backStack = backStack.toList(),
                previousMainTab = previousMainTab
            )
        )
    }
}

/**
 * Navigation events that can be observed for analytics, logging, etc.
 */
sealed class NavigationEvent {
    data class NavigateTo(val destination: NavigationDestination) : NavigationEvent()
    data object NavigateBack : NavigationEvent()
}

/**
 * CompositionLocal to provide NavigationController throughout the composition.
 * 
 * Usage:
 * ```kotlin
 * val navigation = LocalNavigationController.current
 * Button(onClick = { navigation.navigateTo(NavigationDestination.AddExpense) }) {
 *     Text("Add Expense")
 * }
 * ```
 */
val LocalNavigationController = compositionLocalOf<NavigationController> {
    error("No NavigationController provided. Ensure NavigationController is provided at the root of your composition.")
}

/**
 * Provides a NavigationController to its content.
 * 
 * @param initialDestination The starting destination, defaults to Home
 * @param content The composable content that will have access to the NavigationController
 */
@Composable
fun ProvideNavigationController(
    initialDestination: NavigationDestination = NavigationDestination.Home,
    content: @Composable () -> Unit
) {
    var persistedState by rememberSaveable(stateSaver = PersistedNavigationState.Saver) {
        mutableStateOf(PersistedNavigationState.fromDestination(initialDestination))
    }
    val restoredDestination = destinationFromSaveToken(persistedState.destinationToken) ?: initialDestination
    val restoredBackStack = persistedState.backStackTokens.mapNotNull(::destinationFromSaveToken)

    val currentDestination = remember {
        mutableStateOf(restoredDestination)
    }
    val navigationController = remember {
        NavigationController(
            currentDestination = currentDestination,
            initialBackStack = restoredBackStack,
            initialPreviousMainTab = persistedState.previousMainTab,
            onStateChanged = { snapshot ->
                persistedState = PersistedNavigationState.fromSnapshot(snapshot)
            }
        )
    }
    
    CompositionLocalProvider(
        LocalNavigationController provides navigationController
    ) {
        content()
    }
}

/**
 * Helper function to consume navigation events.
 * Use this to observe navigation events for analytics, logging, etc.
 * 
 * @param onEvent Callback for each navigation event
 */
@Composable
fun ConsumeNavigationEvents(
    onEvent: (NavigationEvent) -> Unit
) {
    val navigation = LocalNavigationController.current
    
    DisposableEffect(navigation) {
        // Note: In a real implementation, you'd collect from navigation.navigationEvents
        // For now, this is a placeholder for future analytics integration
        onDispose { }
    }
}
