package com.yourname.expensetracker.ui.components.emptystate

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
import com.yourname.expensetracker.R
import com.yourname.expensetracker.di.EmptyStateRegistryInitializer
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import javax.inject.Inject

class DefaultEmptyStateRegistryInitializer @Inject constructor() : EmptyStateRegistryInitializer {

    override fun initialize(registry: ContextualActionRegistry) {
        registry.apply {
            registerWarrantyActions()
            registerSubscriptionActions()
            registerSavingsActions()
            registerChallengesActions()
            registerCarbonActions()
            registerLifestyleActions()
            registerTransactionsActions()
            registerReceiptsActions()
            registerAnalyticsActions()
            registerBudgetActions()
        }
    }

    private fun ContextualActionRegistry.registerWarrantyActions() {
        registerActions(
            EmptyStateScreenKeys.WARRANTY,
            listOf(
                EmptyStateAction(
                    id = "scan_receipt",
                    titleRes = R.string.empty_action_scan_receipt_title,
                    descriptionRes = R.string.empty_action_scan_receipt_desc,
                    icon = Icons.Default.CameraAlt,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.ScanReceipt),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "add_warranty",
                    titleRes = R.string.empty_action_add_manually_title,
                    descriptionRes = R.string.empty_action_add_warranty_desc,
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
                    titleRes = R.string.empty_action_connect_notifications_title,
                    descriptionRes = R.string.empty_action_connect_notifications_desc,
                    icon = Icons.Default.Notifications,
                    action = EmptyStateActionType.OpenFeature("notification_settings"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "add_subscription",
                    titleRes = R.string.empty_action_add_subscription_title,
                    descriptionRes = R.string.empty_action_add_subscription_desc,
                    icon = Icons.Default.Add,
                    action = EmptyStateActionType.OpenFeature("add_subscription"),
                    priority = 8
                ),
                EmptyStateAction(
                    id = "scan_bank",
                    titleRes = R.string.empty_action_scan_bank_title,
                    descriptionRes = R.string.empty_action_scan_bank_desc,
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
                    titleRes = R.string.empty_action_create_goal_title,
                    descriptionRes = R.string.empty_action_create_goal_desc,
                    icon = Icons.Default.Savings,
                    action = EmptyStateActionType.OpenFeature("create_savings_goal"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_recommendations",
                    titleRes = R.string.empty_action_view_recommendations_title,
                    descriptionRes = R.string.empty_action_view_recommendations_desc,
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
                    titleRes = R.string.empty_action_start_challenge_title,
                    descriptionRes = R.string.empty_action_start_challenge_desc,
                    icon = Icons.Default.EmojiEvents,
                    action = EmptyStateActionType.OpenFeature("create_challenge"),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_no_spend",
                    titleRes = R.string.empty_action_no_spend_streak_title,
                    descriptionRes = R.string.empty_action_no_spend_streak_desc,
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
                    titleRes = R.string.empty_action_track_carbon_title,
                    descriptionRes = R.string.empty_action_track_carbon_desc,
                    icon = Icons.Default.Spa,
                    action = EmptyStateActionType.ExecuteAction { },
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_offset",
                    titleRes = R.string.empty_action_offset_options_title,
                    descriptionRes = R.string.empty_action_offset_options_desc,
                    icon = Icons.Default.Forest,
                    action = EmptyStateActionType.OpenFeature("carbon_offset"),
                    priority = 7
                ),
                EmptyStateAction(
                    id = "add_transactions",
                    titleRes = R.string.empty_action_add_transactions_title,
                    descriptionRes = R.string.empty_action_add_transactions_desc,
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
                    titleRes = R.string.empty_action_analyze_patterns_title,
                    descriptionRes = R.string.empty_action_analyze_patterns_desc,
                    icon = Icons.Default.ShowChart,
                    action = EmptyStateActionType.ExecuteAction { },
                    priority = 10
                ),
                EmptyStateAction(
                    id = "view_income",
                    titleRes = R.string.empty_action_set_income_title,
                    descriptionRes = R.string.empty_action_set_income_desc,
                    icon = Icons.Default.TrendingUp,
                    action = EmptyStateActionType.OpenFeature("income_settings"),
                    priority = 8
                ),
                EmptyStateAction(
                    id = "add_expenses",
                    titleRes = R.string.empty_action_add_expenses_title,
                    descriptionRes = R.string.empty_action_add_expenses_desc,
                    icon = Icons.Default.ReceiptLong,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.AddExpense),
                    priority = 5
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerTransactionsActions() {
        registerActions(
            EmptyStateScreenKeys.TRANSACTIONS,
            listOf(
                EmptyStateAction(
                    id = "add_expense",
                    titleRes = R.string.empty_action_add_expense_title,
                    descriptionRes = R.string.empty_action_add_expense_desc,
                    icon = Icons.Default.Add,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.AddExpense),
                    priority = 10
                ),
                EmptyStateAction(
                    id = "scan_receipt",
                    titleRes = R.string.empty_action_scan_first_receipt_title,
                    descriptionRes = R.string.empty_action_scan_first_receipt_desc,
                    icon = Icons.Default.CameraAlt,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.ScanReceipt),
                    priority = 8
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerReceiptsActions() {
        registerActions(
            EmptyStateScreenKeys.RECEIPTS,
            listOf(
                EmptyStateAction(
                    id = "scan_receipt",
                    titleRes = R.string.empty_action_scan_first_receipt_title,
                    descriptionRes = R.string.empty_action_scan_first_receipt_desc,
                    icon = Icons.Default.CameraAlt,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.ScanReceipt),
                    priority = 10
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerAnalyticsActions() {
        registerActions(
            EmptyStateScreenKeys.ANALYTICS,
            listOf(
                EmptyStateAction(
                    id = "add_expense",
                    titleRes = R.string.empty_action_add_for_analytics_title,
                    descriptionRes = R.string.empty_action_add_for_analytics_desc,
                    icon = Icons.Default.Add,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.AddExpense),
                    priority = 10
                )
            )
        )
    }

    private fun ContextualActionRegistry.registerBudgetActions() {
        registerActions(
            EmptyStateScreenKeys.BUDGET,
            listOf(
                EmptyStateAction(
                    id = "create_budget",
                    titleRes = R.string.empty_action_create_budget_title,
                    descriptionRes = R.string.empty_action_create_budget_desc,
                    icon = Icons.Default.Add,
                    action = EmptyStateActionType.NavigateToDestination(NavigationDestination.Budget),
                    priority = 10
                )
            )
        )
    }
}
