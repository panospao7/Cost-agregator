package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Expense

/**
 * Sealed class representing all navigation destinations in the app.
 * Replaces the boolean flag system with a type-safe navigation state.
 */
sealed class NavigationDestination {
    
    // Main Tabs
    data object Home : NavigationDestination()
    data object Transactions : NavigationDestination()
    data object Analytics : NavigationDestination()
    data object Assistant : NavigationDestination()
    
    // Feature Screens
    data object AddExpense : NavigationDestination()
    data object ScanReceipt : NavigationDestination()
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
        val expense: Expense? = null,
        val templateId: Long? = null
    ) : NavigationDestination()
    data object CurrencyManagement : NavigationDestination()
    data object SubscriptionManagement : NavigationDestination()
    data object TaxConfiguration : NavigationDestination()
    data object ExportOptions : NavigationDestination()
    data object ManualRecurringExpense : NavigationDestination()
    data object SharedExpenseGroups : NavigationDestination()
    data class BudgetForecasting(val budget: Budget? = null) : NavigationDestination()
    data object Review : NavigationDestination()
    data object SpendingMap : NavigationDestination()
    data object Budget : NavigationDestination()
    
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
            ManualRecurringExpense,
            SharedExpenseGroups
        )
    }
}

/**
 * Type alias for backward compatibility during migration
 */
typealias Destination = NavigationDestination
