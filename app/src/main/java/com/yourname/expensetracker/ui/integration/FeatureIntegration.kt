package com.yourname.expensetracker.ui.integration

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
            text = { Text("Investment Portfolio") },
            onClick = onInvestmentPortfolio,
            leadingIcon = { Icon(Icons.Default.TrendingUp, null) }
        )
        
        DropdownMenuItem(
            text = { Text("Bank Connections") },
            onClick = onBankConnections,
            leadingIcon = { Icon(Icons.Default.AccountBalance, null) }
        )
        
        DropdownMenuItem(
            text = { Text("Bill Reminders") },
            onClick = onBillReminders,
            leadingIcon = { Icon(Icons.Default.Notifications, null) }
        )
        
        DropdownMenuItem(
            text = { Text("Spending Challenges") },
            onClick = onSpendingChallenges,
            leadingIcon = { Icon(Icons.Default.LocalFireDepartment, null) }
        )
        
        HorizontalDivider()
        
        DropdownMenuItem(
            text = { Text("Advanced Analytics") },
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
            text = "Quick Actions",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        ListItem(
            headlineContent = { Text("Investment Portfolio") },
            supportingContent = { Text("Track your investments") },
            leadingContent = { 
                Icon(Icons.Default.TrendingUp, null, tint = MaterialTheme.colorScheme.tertiary) 
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        ListItem(
            headlineContent = { Text("Bank Connections") },
            supportingContent = { Text("Connect your banks") },
            leadingContent = { 
                Icon(Icons.Default.AccountBalance, null, tint = MaterialTheme.colorScheme.primary) 
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        ListItem(
            headlineContent = { Text("Bill Reminders") },
            supportingContent = { Text("Upcoming payments") },
            leadingContent = { 
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.error) 
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
        
        ListItem(
            headlineContent = { Text("Spending Challenges") },
            supportingContent = { Text("No-spend streaks") },
            leadingContent = { 
                Icon(Icons.Default.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.tertiary) 
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
            text = "Budget Tools",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        ListItem(
            headlineContent = { Text("Bill Reminders") },
            supportingContent = { Text("Track recurring payments") },
            leadingContent = { Icon(Icons.Default.Notifications, null) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        
        ListItem(
            headlineContent = { Text("Spending Challenges") },
            supportingContent = { Text("Complete challenges to save") },
            leadingContent = { Icon(Icons.Default.LocalFireDepartment, null) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
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
            Text("Open Advanced Analytics Dashboard")
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
            label = { Text("Investment Portfolio") },
            selected = false,
            onClick = onInvestmentPortfolio
        )
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.AccountBalance, null) },
            label = { Text("Bank Connections") },
            selected = false,
            onClick = onBankConnections
        )
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Notifications, null) },
            label = { Text("Bill Reminders") },
            selected = false,
            onClick = onBillReminders
        )
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.LocalFireDepartment, null) },
            label = { Text("Spending Challenges") },
            selected = false,
            onClick = onSpendingChallenges
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Analytics, null) },
            label = { Text("Advanced Analytics") },
            selected = false,
            onClick = onAdvancedAnalytics
        )
    }
}
