package com.yourname.expensetracker.ui.navigation

import androidx.compose.animation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import com.yourname.expensetracker.ui.screens.analytics.AdvancedAnalyticsScreen
import com.yourname.expensetracker.ui.screens.bank.BankConnectionsScreen
import com.yourname.expensetracker.ui.screens.challenge.SpendingChallengesScreen
import com.yourname.expensetracker.ui.screens.investment.InvestmentPortfolioScreen
import com.yourname.expensetracker.ui.screens.reminder.BillRemindersScreen

/**
 * Navigation destinations for new Phase 4 features.
 * 
 * These screens can be accessed through:
 * 1. Menu items in existing screens
 * 2. Quick actions in Home screen
 * 3. Assistant/AI recommendations
 * 4. Deep links from notifications
 */
object NavigationDestinations {
    const val INVESTMENT_PORTFOLIO = "investment_portfolio"
    const val ADD_INVESTMENT = "add_investment"
    const val BANK_CONNECTIONS = "bank_connections"
    const val BILL_REMINDERS = "bill_reminders"
    const val SPENDING_CHALLENGES = "spending_challenges"
    const val ADVANCED_ANALYTICS = "advanced_analytics"
}

/**
 * Extension functions to integrate new screens into existing navigation.
 */
@Composable
fun Phase4NavigationIntegration(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Investment Portfolio
    when (currentRoute) {
        NavigationDestinations.INVESTMENT_PORTFOLIO -> {
            InvestmentPortfolioScreen(
                onNavigateBack = onNavigateBack,
                onAddInvestment = { onNavigate(NavigationDestinations.ADD_INVESTMENT) }
            )
        }
        
        NavigationDestinations.BANK_CONNECTIONS -> {
            BankConnectionsScreen(
                onNavigateBack = onNavigateBack,
                onAddConnection = { 
                    // Would initiate OAuth flow
                    // For now, just show placeholder
                }
            )
        }
        
        NavigationDestinations.BILL_REMINDERS -> {
            BillRemindersScreen(
                onNavigateBack = onNavigateBack
            )
        }
        
        NavigationDestinations.SPENDING_CHALLENGES -> {
            SpendingChallengesScreen(
                onNavigateBack = onNavigateBack,
                onCreateChallenge = { 
                    // Would open challenge creation dialog
                }
            )
        }
        
        NavigationDestinations.ADVANCED_ANALYTICS -> {
            AdvancedAnalyticsScreen(
                onNavigateBack = onNavigateBack
            )
        }
    }
}

/**
 * Menu items to add to existing screens.
 */
object MenuIntegration {
    
    /**
     * Menu items to add to Home screen (Tab 0).
     */
    val homeScreenMenuItems = listOf(
        MenuItem(
            icon = Icons.Filled.TrendingUp,
            label = "Investment Portfolio",
            route = NavigationDestinations.INVESTMENT_PORTFOLIO
        ),
        MenuItem(
            icon = Icons.Filled.LocalFireDepartment,
            label = "Spending Challenges",
            route = NavigationDestinations.SPENDING_CHALLENGES
        ),
        MenuItem(
            icon = Icons.Filled.Notifications,
            label = "Bill Reminders",
            route = NavigationDestinations.BILL_REMINDERS
        ),
        MenuItem(
            icon = Icons.Filled.AccountBalance,
            label = "Bank Connections",
            route = NavigationDestinations.BANK_CONNECTIONS
        ),
        MenuItem(
            icon = Icons.Filled.Assessment,
            label = "Advanced Analytics",
            route = NavigationDestinations.ADVANCED_ANALYTICS
        )
    )
    
    /**
     * Menu items to add to Analytics screen (Tab 4).
     */
    val analyticsScreenMenuItems = listOf(
        MenuItem(
            icon = Icons.Filled.Assessment,
            label = "Advanced Dashboard",
            route = NavigationDestinations.ADVANCED_ANALYTICS
        )
    )
    
    /**
     * Menu items to add to Budget screen (Tab 3).
     */
    val budgetScreenMenuItems = listOf(
        MenuItem(
            icon = Icons.Filled.Notifications,
            label = "Bill Reminders",
            route = NavigationDestinations.BILL_REMINDERS
        ),
        MenuItem(
            icon = Icons.Filled.LocalFireDepartment,
            label = "Spending Challenges",
            route = NavigationDestinations.SPENDING_CHALLENGES
        )
    )
}

data class MenuItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val route: String
)
