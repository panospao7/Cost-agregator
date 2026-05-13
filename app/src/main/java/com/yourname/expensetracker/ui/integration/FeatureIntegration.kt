package com.yourname.expensetracker.ui.integration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R

/**
 * Integration points for adding new features to existing screens.
 * 
 * Usage: Add these composables to the respective screens as menu items
 * or quick action cards.
 */

object FeatureIntegration {

    /**
     * Menu section for Home screen overflow menu or drawer.
     */
    @Composable
    fun HomeScreenFeatureMenu(
        onInvestmentPortfolio: () -> Unit,
        onBankConnections: () -> Unit,
        onBillReminders: () -> Unit,
        onSpendingChallenges: () -> Unit,
        onAdvancedAnalytics: () -> Unit
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_investment_portfolio)) },
            onClick = onInvestmentPortfolio,
            leadingIcon = { Icon(Icons.Default.TrendingUp, null) }
        )
        
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_bank_connections)) },
            onClick = onBankConnections,
            leadingIcon = { Icon(Icons.Default.AccountBalance, null) }
        )
        
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_bill_reminders)) },
            onClick = onBillReminders,
            leadingIcon = { Icon(Icons.Default.Notifications, null) }
        )
        
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_spending_challenges)) },
            onClick = onSpendingChallenges,
            leadingIcon = { Icon(Icons.Default.LocalFireDepartment, null) }
        )
        
        HorizontalDivider()
        
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_advanced_analytics)) },
            onClick = onAdvancedAnalytics,
            leadingIcon = { Icon(Icons.Default.Analytics, null) }
        )
    }

    /**
     * Quick action cards for Home screen.
     */
    @Composable
    fun HomeScreenQuickActions(
        onInvestmentPortfolio: () -> Unit,
        onBankConnections: () -> Unit,
        onBillReminders: () -> Unit,
        onSpendingChallenges: () -> Unit
    ) {
        Text(
            text = stringResource(R.string.feature_quick_actions_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_investment_portfolio)) },
            supportingContent = { Text(stringResource(R.string.desc_track_investments)) },
            leadingContent = { 
                Icon(Icons.Default.TrendingUp, null, tint = MaterialTheme.colorScheme.tertiary) 
            },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onInvestmentPortfolio() },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_bank_connections)) },
            supportingContent = { Text(stringResource(R.string.desc_connect_banks)) },
            leadingContent = { 
                Icon(Icons.Default.AccountBalance, null, tint = MaterialTheme.colorScheme.primary) 
            },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onBankConnections() },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_bill_reminders)) },
            supportingContent = { Text(stringResource(R.string.desc_upcoming_payments)) },
            leadingContent = { 
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.error) 
            },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onBillReminders() },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_spending_challenges)) },
            supportingContent = { Text(stringResource(R.string.desc_no_spend_streaks)) },
            leadingContent = { 
                Icon(Icons.Default.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.tertiary) 
            },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onSpendingChallenges() },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
    }

    /**
     * Additional actions for Budget screen.
     */
    @Composable
    fun BudgetScreenActions(
        onBillReminders: () -> Unit,
        onSpendingChallenges: () -> Unit
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.feature_budget_tools_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_bill_reminders)) },
            supportingContent = { Text(stringResource(R.string.desc_track_recurring)) },
            leadingContent = { Icon(Icons.Default.Notifications, null) },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onBillReminders() }
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_spending_challenges)) },
            supportingContent = { Text(stringResource(R.string.desc_complete_challenges)) },
            leadingContent = { Icon(Icons.Default.LocalFireDepartment, null) },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onSpendingChallenges() }
        )
    }

    /**
     * Additional section for Analytics screen.
     */
    @Composable
    fun AnalyticsScreenAdvancedOption(
        onAdvancedAnalytics: () -> Unit
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onAdvancedAnalytics,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Default.Analytics, null)
            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
            Text(stringResource(R.string.button_open_advanced_analytics))
        }
    }

    /**
     * Navigation drawer items for new features.
     */
    @Composable
    fun NavigationDrawerItems(
        onInvestmentPortfolio: () -> Unit,
        onBankConnections: () -> Unit,
        onBillReminders: () -> Unit,
        onSpendingChallenges: () -> Unit,
        onAdvancedAnalytics: () -> Unit
    ) {
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.TrendingUp, null) },
            label = { Text(stringResource(R.string.menu_investment_portfolio)) },
            selected = false,
            onClick = onInvestmentPortfolio
        )
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.AccountBalance, null) },
            label = { Text(stringResource(R.string.menu_bank_connections)) },
            selected = false,
            onClick = onBankConnections
        )
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Notifications, null) },
            label = { Text(stringResource(R.string.menu_bill_reminders)) },
            selected = false,
            onClick = onBillReminders
        )
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.LocalFireDepartment, null) },
            label = { Text(stringResource(R.string.menu_spending_challenges)) },
            selected = false,
            onClick = onSpendingChallenges
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Analytics, null) },
            label = { Text(stringResource(R.string.menu_advanced_analytics)) },
            selected = false,
            onClick = onAdvancedAnalytics
        )
    }
}
