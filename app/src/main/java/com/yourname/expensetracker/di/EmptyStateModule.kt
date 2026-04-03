package com.yourname.expensetracker.di

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.TrendingUp
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides and initializes the [ContextualActionRegistry]
 * with default contextual actions for all empty states in the app.
 */
@Module
@InstallIn(SingletonComponent::class)
object EmptyStateModule {

    @Provides
    @Singleton
    fun provideContextualActionRegistry(): ContextualActionRegistry {
        return ContextualActionRegistry().apply {
            // Register default actions for all screens
            registerWarrantyActions()
            registerSubscriptionActions()
            registerSavingsActions()
            registerChallengesActions()
            registerCarbonActions()
            registerLifestyleActions()
        }
    }

    private fun ContextualActionRegistry.registerWarrantyActions() {
        registerActions(
            EmptyStateScreenKeys.WARRANTY,
            listOf(
                EmptyStateAction(
                    id = "scan_receipt",
                    title = "Scan Receipt",
                    description = "Scan receipt to detect warranties",
                    icon = Icons.Default.CameraAlt,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.ScanReceipt),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "add_warranty",
                    title = "Add Manually",
                    description = "Manually add a warranty",
                    icon = Icons.Default.Add,
                    action = EmptyStateActionType.OpenFeature("add_warranty"),
                    priority = 5
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerSubscriptionActions() {
        registerActions(
            EmptyStateScreenKeys.SUBSCRIPTION,
            listOf(
                EmptyStateAction(
                    id = "connect_notifications",
                    title = "Connect Notifications",
                    description = "Connect notifications to detect subscriptions",
                    icon = Icons.Default.Notifications,
                    action = EmptyStateActionType.OpenFeature("notification_settings"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "add_subscription",
                    title = "Add Subscription",
                    description = "Add a subscription manually",
                    icon = Icons.Default.Add,
                    action = EmptyStateActionType.OpenFeature("add_subscription"),
                    priority = 8
                ),
                EmptyStateAction(
                    id = "scan_bank",
                    title = "Scan Bank Statements",
                    description = "Scan bank statements for subscriptions",
                    icon = Icons.Default.ReceiptLong,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.BankConnections),
                    priority = 5
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerSavingsActions() {
        registerActions(
            EmptyStateScreenKeys.SAVINGS,
            listOf(
                EmptyStateAction(
                    id = "create_goal",
                    title = "Create First Goal",
                    description = "Create your first savings goal",
                    icon = Icons.Default.Savings,
                    action = EmptyStateActionType.OpenFeature("create_savings_goal"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_recommendations",
                    title = "View Recommendations",
                    description = "See how much you can save",
                    icon = Icons.Default.ShowChart,
                    action = EmptyStateActionType.OpenFeature("savings_recommendations"),
                    priority = 7
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerChallengesActions() {
        registerActions(
            EmptyStateScreenKeys.CHALLENGES,
            listOf(
                EmptyStateAction(
                    id = "start_challenge",
                    title = "Start Challenge",
                    description = "Start a spending challenge",
                    icon = Icons.Default.EmojiEvents,
                    action = EmptyStateActionType.OpenFeature("create_challenge"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_no_spend",
                    title = "No-Spend Streak",
                    description = "View your no-spend streak progress",
                    icon = Icons.Default.TrendingUp,
                    action = EmptyStateActionType.OpenFeature("no_spend_streak"),
                    priority = 5
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerCarbonActions() {
        registerActions(
            EmptyStateScreenKeys.CARBON,
            listOf(
                EmptyStateAction(
                    id = "track_carbon",
                    title = "Track Footprint",
                    description = "Track your carbon footprint",
                    icon = Icons.Default.Spa,
                    action = EmptyStateActionType.ExecuteAction {
                        // Trigger carbon calculation
                    },
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_offset",
                    title = "Offset Options",
                    description = "View carbon offset options",
                    icon = Icons.Default.Forest,
                    action = EmptyStateActionType.OpenFeature("carbon_offset"),
                    priority = 7
                ),
                EmptyStateAction(
                    id = "add_transactions",
                    title = "Add Transactions",
                    description = "Add transactions to calculate footprint",
                    icon = Icons.Default.ReceiptLong,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.AddExpense),
                    priority = 5
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerLifestyleActions() {
        registerActions(
            EmptyStateScreenKeys.LIFESTYLE,
            listOf(
                EmptyStateAction(
                    id = "analyze_patterns",
                    title = "Analyze Patterns",
                    description = "Analyze your lifestyle patterns",
                    icon = Icons.Default.ShowChart,
                    action = EmptyStateActionType.ExecuteAction {
                        // Trigger lifestyle analysis
                    },
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_income",
                    title = "Set Income",
                    description = "Set your income for tracking",
                    icon = Icons.Default.TrendingUp,
                    action = EmptyStateActionType.OpenFeature("income_settings"),
                    priority = 8
                ),
                EmptyStateAction(
                    id = "add_expenses",
                    title = "Add Expenses",
                    description = "Add expenses for analysis",
                    icon = Icons.Default.ReceiptLong,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.AddExpense),
                    priority = 5
                )
            )
        )
    }
}
