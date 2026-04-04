package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.data.database.entity.Budget as BudgetEntity
import com.yourname.expensetracker.data.database.entity.Expense

/**
 * Sealed class representing all navigation destinations in the app.
 * Single source of truth for type-safe navigation.
 * 
 * All navigation flows use this sealed class - no more boolean flags or callbacks.
 */
sealed class NavigationDestination {
    
    // Main Tabs
    data object Home : NavigationDestination()
    data object Transactions : NavigationDestination()
    data object Analytics : NavigationDestination()
    data object Assistant : NavigationDestination()
    data object Review : NavigationDestination()
    data object Budget : NavigationDestination()
    data class BudgetDetail(
        val categoryId: Long? = null,
        val categoryName: String? = null
    ) : NavigationDestination()
    data object SpendingMap : NavigationDestination()
    
    // Overlay Screens (previously boolean flags)
    data object AddExpense : NavigationDestination()
    data object ScanReceipt : NavigationDestination()
    data object RecurringExpenses : NavigationDestination()
    data object ManualRecurringExpense : NavigationDestination()
    
    // Feature Screens
    data object SavingsGoals : NavigationDestination()
    data object CarbonFootprint : NavigationDestination()
    data object WarrantyTracker : NavigationDestination()
    data object PriceProtection : NavigationDestination()
    data object BillNegotiation : NavigationDestination()
    data object SmartSearch : NavigationDestination()
    data object ReceiptMatching : NavigationDestination()
    data object InvestmentPortfolio : NavigationDestination()
    data object BankConnections : NavigationDestination()
    data object BillReminders : NavigationDestination()
    data object SpendingChallenges : NavigationDestination()
    data object AdvancedAnalytics : NavigationDestination()
    data object CashFlowCalendar : NavigationDestination()
    data object LifestyleInflation : NavigationDestination()
    data object SplitTemplates : NavigationDestination()
    data class VisualSplitEditor(
        val templateId: Long? = null,
        val expenseId: Long? = null,
        val expenseAmount: Double? = null,
        val expenseCurrency: String? = null,
        // Backward-compatible optional payload for in-memory navigation callers.
        val expense: Expense? = null
    ) : NavigationDestination()
    data object CurrencyManagement : NavigationDestination()
    data object SubscriptionManagement : NavigationDestination()
    data object TaxConfiguration : NavigationDestination()
    data object ExportOptions : NavigationDestination()
    data object SharedExpenseGroups : NavigationDestination()
    data class BudgetForecasting(val budget: BudgetEntity? = null) : NavigationDestination()
    
    // Settings / Management Screens (previously orphaned)
    data object AiSettings : NavigationDestination()
    data object CategoryManagement : NavigationDestination()
    
    companion object {
        /**
         * List of all feature destinations for the FeaturesMenu.
         * Ordered as they should appear in the menu.
         */
        val featureDestinations = listOf(
            SavingsGoals,
            CarbonFootprint,
            WarrantyTracker,
            PriceProtection,
            BillNegotiation,
            SmartSearch,
            ReceiptMatching,
            InvestmentPortfolio,
            BankConnections,
            BillReminders,
            SpendingChallenges,
            AdvancedAnalytics,
            CashFlowCalendar,
            LifestyleInflation,
            SplitTemplates,
            VisualSplitEditor(),
            CurrencyManagement,
            SubscriptionManagement,
            TaxConfiguration,
            ExportOptions,
            RecurringExpenses,
            ManualRecurringExpense,
            SharedExpenseGroups,
            // Settings / Management features (previously accessed only via Quick Settings)
            AiSettings,
            CategoryManagement
        )
    }
}

/**
 * Type alias for backward compatibility during migration
 */
typealias Destination = NavigationDestination
