package com.yourname.expensetracker.ui.navigation

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

private fun NavigationDestination.toSaveToken(): String = when (this) {
    is NavigationDestination.Home -> "home"
    is NavigationDestination.Transactions -> "transactions"
    is NavigationDestination.Analytics -> "analytics"
    is NavigationDestination.Assistant -> "assistant"
    is NavigationDestination.Review -> "review"
    is NavigationDestination.Budget -> "budget"
    is NavigationDestination.SpendingMap -> "spending_map"
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
    is NavigationDestination.SpendingChallenges -> "spending_challenges"
    is NavigationDestination.AdvancedAnalytics -> "advanced_analytics"
    is NavigationDestination.CashFlowCalendar -> "cash_flow_calendar"
    is NavigationDestination.LifestyleInflation -> "lifestyle_inflation"
    is NavigationDestination.SplitTemplates -> "split_templates"
    is NavigationDestination.VisualSplitEditor -> {
        templateId?.let { "visual_split_editor:$it" } ?: "visual_split_editor"
    }
    is NavigationDestination.CurrencyManagement -> "currency_management"
    is NavigationDestination.SubscriptionManagement -> "subscription_management"
    is NavigationDestination.TaxConfiguration -> "tax_configuration"
    is NavigationDestination.ExportOptions -> "export_options"
    is NavigationDestination.SharedExpenseGroups -> "shared_expense_groups"
    // Budget entity is intentionally not serialized; reopening null-budget route is safe.
    is NavigationDestination.BudgetForecasting -> "budget_forecasting"
    is NavigationDestination.AiSettings -> "ai_settings"
    is NavigationDestination.CategoryManagement -> "category_management"
}

private fun destinationFromSaveToken(token: String): NavigationDestination? {
    return when {
        token == "home" -> NavigationDestination.Home
        token == "transactions" -> NavigationDestination.Transactions
        token == "analytics" -> NavigationDestination.Analytics
        token == "assistant" -> NavigationDestination.Assistant
        token == "review" -> NavigationDestination.Review
        token == "budget" -> NavigationDestination.Budget
        token == "spending_map" -> NavigationDestination.SpendingMap
        token == "add_expense" -> NavigationDestination.AddExpense
        token == "scan_receipt" -> NavigationDestination.ScanReceipt
        token == "recurring_expenses" -> NavigationDestination.RecurringExpenses
        token == "manual_recurring_expense" -> NavigationDestination.ManualRecurringExpense
        token == "savings_goals" -> NavigationDestination.SavingsGoals
        token == "carbon_footprint" -> NavigationDestination.CarbonFootprint
        token == "warranty_tracker" -> NavigationDestination.WarrantyTracker
        token == "price_protection" -> NavigationDestination.PriceProtection
        token == "bill_negotiation" -> NavigationDestination.BillNegotiation
        token == "smart_search" -> NavigationDestination.SmartSearch
        token == "receipt_matching" -> NavigationDestination.ReceiptMatching
        token == "investment_portfolio" -> NavigationDestination.InvestmentPortfolio
        token == "bank_connections" -> NavigationDestination.BankConnections
        token == "bill_reminders" -> NavigationDestination.BillReminders
        token == "spending_challenges" -> NavigationDestination.SpendingChallenges
        token == "advanced_analytics" -> NavigationDestination.AdvancedAnalytics
        token == "cash_flow_calendar" -> NavigationDestination.CashFlowCalendar
        token == "lifestyle_inflation" -> NavigationDestination.LifestyleInflation
        token == "split_templates" -> NavigationDestination.SplitTemplates
        token.startsWith("visual_split_editor") -> {
            val templateId = token.substringAfter(':', "")
                .takeIf { it.isNotBlank() }
                ?.toLongOrNull()
            NavigationDestination.VisualSplitEditor(templateId = templateId)
        }
        token == "currency_management" -> NavigationDestination.CurrencyManagement
        token == "subscription_management" -> NavigationDestination.SubscriptionManagement
        token == "tax_configuration" -> NavigationDestination.TaxConfiguration
        token == "export_options" -> NavigationDestination.ExportOptions
        token == "shared_expense_groups" -> NavigationDestination.SharedExpenseGroups
        token == "budget_forecasting" -> NavigationDestination.BudgetForecasting()
        token == "ai_settings" -> NavigationDestination.AiSettings
        token == "category_management" -> NavigationDestination.CategoryManagement
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
            // No back stack entry, go to previous main tab or Home
            val targetTab = previousMainTab ?: 0
            navigateToTab(targetTab)
            false
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
            1 -> NavigationDestination.Transactions
            2 -> NavigationDestination.Review
            3 -> NavigationDestination.Budget
            4 -> NavigationDestination.Analytics
            5 -> NavigationDestination.SpendingMap
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
            is NavigationDestination.Analytics -> 4
            is NavigationDestination.SpendingMap -> 5
            else -> null
        }
    }
    
    /**
     * Check if there's anything in the back stack.
     */
    fun canNavigateBack(): Boolean {
        return backStack.isNotEmpty() || !isOnMainTab()
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
