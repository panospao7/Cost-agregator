package com.yourname.expensetracker.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Configuration for a feature item in the FeaturesMenu.
 * 
 * @property id Unique identifier for the feature
 * @property titleRes String resource ID for the feature title
 * @property icon Icon to display in the menu
 * @property color Color used for the feature's accent/icon
 * @property destination Navigation destination when clicked
 * @property descriptionRes String resource ID for the description (optional)
 * @property isNew Whether to show a "New" badge
 * @property isBeta Whether to show a "Beta" badge
 */
data class FeatureConfig(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val color: Color,
    val destination: NavigationDestination,
    @StringRes val descriptionRes: Int? = null,
    val isNew: Boolean = false,
    val isBeta: Boolean = false
) {
    companion object {
        /**
         * All available features in the FeaturesMenu.
         * Add new features here to automatically appear in the menu.
         */
        val allFeatures = listOf(
            FeatureConfig(
                id = "savings",
                titleRes = R.string.feature_savings_goals,
                icon = Icons.Rounded.Savings,
                color = Color(0xFF4CAF50),
                destination = NavigationDestination.SavingsGoals,
                descriptionRes = R.string.feature_savings_goals_desc
            ),
            FeatureConfig(
                id = "carbon",
                titleRes = R.string.feature_carbon_footprint,
                icon = Icons.Rounded.Eco,
                color = Color(0xFF8BC34A),
                destination = NavigationDestination.CarbonFootprint,
                descriptionRes = R.string.feature_carbon_footprint_desc
            ),
            FeatureConfig(
                id = "warranty",
                titleRes = R.string.feature_warranty_tracker,
                icon = Icons.Rounded.Security,
                color = Color(0xFF2196F3),
                destination = NavigationDestination.WarrantyTracker,
                descriptionRes = R.string.feature_warranty_tracker_desc
            ),
            FeatureConfig(
                id = "price",
                titleRes = R.string.feature_price_protection,
                icon = Icons.Rounded.PriceChange,
                color = Color(0xFFFF9800),
                destination = NavigationDestination.PriceProtection,
                descriptionRes = R.string.feature_price_protection_desc
            ),
            FeatureConfig(
                id = "negotiation",
                titleRes = R.string.feature_bill_negotiation,
                icon = Icons.Rounded.Handshake,
                color = Color(0xFF9C27B0),
                destination = NavigationDestination.BillNegotiation,
                descriptionRes = R.string.feature_bill_negotiation_desc
            ),
            FeatureConfig(
                id = "smart-search",
                titleRes = R.string.feature_smart_search,
                icon = Icons.Rounded.Search,
                color = Color(0xFF3F51B5),
                destination = NavigationDestination.SmartSearch,
                descriptionRes = R.string.feature_smart_search_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "receipt-matching",
                titleRes = R.string.feature_receipt_matching,
                icon = Icons.Rounded.Receipt,
                color = Color(0xFF009688),
                destination = NavigationDestination.ReceiptMatching,
                descriptionRes = R.string.feature_receipt_matching_desc
            ),
            FeatureConfig(
                id = "investment",
                titleRes = R.string.feature_investment_portfolio,
                icon = Icons.Rounded.TrendingUp,
                color = Color(0xFF673AB7),
                destination = NavigationDestination.InvestmentPortfolio,
                descriptionRes = R.string.feature_investment_portfolio_desc
            ),
            FeatureConfig(
                id = "bank-connections",
                titleRes = R.string.feature_bank_connections,
                icon = Icons.Rounded.AccountBalance,
                color = Color(0xFF795548),
                destination = NavigationDestination.BankConnections,
                descriptionRes = R.string.feature_bank_connections_desc
            ),
            FeatureConfig(
                id = "bill-reminders",
                titleRes = R.string.feature_bill_reminders,
                icon = Icons.Rounded.Notifications,
                color = Color(0xFFE91E63),
                destination = NavigationDestination.BillReminders,
                descriptionRes = R.string.feature_bill_reminders_desc
            ),
            FeatureConfig(
                id = "challenges",
                titleRes = R.string.feature_spending_challenges,
                icon = Icons.Rounded.EmojiEvents,
                color = Color(0xFFFF5722),
                destination = NavigationDestination.SpendingChallenges(),
                descriptionRes = R.string.feature_spending_challenges_desc
            ),
            FeatureConfig(
                id = "analytics",
                titleRes = R.string.feature_advanced_analytics,
                icon = Icons.Rounded.Analytics,
                color = Color(0xFF607D8B),
                destination = NavigationDestination.AdvancedAnalytics,
                descriptionRes = R.string.feature_advanced_analytics_desc
            ),
            FeatureConfig(
                id = "cashflow",
                titleRes = R.string.feature_cashflow_calendar,
                icon = Icons.Rounded.CalendarMonth,
                color = Color(0xFF00BCD4),
                destination = NavigationDestination.CashFlowCalendar,
                descriptionRes = R.string.feature_cashflow_calendar_desc
            ),
            FeatureConfig(
                id = "lifestyle",
                titleRes = R.string.feature_lifestyle_inflation,
                icon = Icons.Rounded.TrendingUp,
                color = Color(0xFFFF9800),
                destination = NavigationDestination.LifestyleInflation,
                descriptionRes = R.string.feature_lifestyle_inflation_desc
            ),
            FeatureConfig(
                id = "split-templates",
                titleRes = R.string.feature_split_templates,
                icon = Icons.Rounded.CallSplit,
                color = Color(0xFF3F51B5),
                destination = NavigationDestination.SplitTemplates,
                descriptionRes = R.string.feature_split_templates_desc
            ),
            FeatureConfig(
                id = "visual-split",
                titleRes = R.string.feature_visual_split,
                icon = Icons.Rounded.PieChart,
                color = Color(0xFF9C27B0),
                destination = NavigationDestination.VisualSplitEditor.forTemplateCreation(),
                descriptionRes = R.string.feature_visual_split_desc
            ),
            FeatureConfig(
                id = "currency",
                titleRes = R.string.feature_currency_management,
                icon = Icons.Rounded.CurrencyExchange,
                color = Color(0xFF4CAF50),
                destination = NavigationDestination.CurrencyManagement,
                descriptionRes = R.string.feature_currency_management_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "subscriptions",
                titleRes = R.string.feature_subscription_management,
                icon = Icons.Rounded.Subscriptions,
                color = Color(0xFF2196F3),
                destination = NavigationDestination.SubscriptionManagement,
                descriptionRes = R.string.feature_subscription_management_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "tax",
                titleRes = R.string.feature_tax_configuration,
                icon = Icons.Rounded.Calculate,
                color = Color(0xFF795548),
                destination = NavigationDestination.TaxConfiguration,
                descriptionRes = R.string.feature_tax_configuration_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "export",
                titleRes = R.string.feature_export_options,
                icon = Icons.Rounded.FileDownload,
                color = Color(0xFF607D8B),
                destination = NavigationDestination.ExportOptions,
                descriptionRes = R.string.feature_export_options_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "recurring",
                titleRes = R.string.feature_manual_recurring,
                icon = Icons.Rounded.Repeat,
                color = Color(0xFFE91E63),
                destination = NavigationDestination.ManualRecurringExpense,
                descriptionRes = R.string.feature_manual_recurring_desc,
                isNew = true
            ),
            // loan-installments S10a: withheld from the menu while InstallmentsFeature.ENABLED is false
            // (see availableFeatures); listed here so the id/destination contract tests cover it.
            FeatureConfig(
                id = InstallmentsFeature.FEATURE_ID,
                titleRes = R.string.installments_title,
                icon = Icons.Rounded.Payments,
                color = Color(0xFF009688),
                destination = NavigationDestination.Installments,
                descriptionRes = R.string.feature_installments_desc
            ),
            FeatureConfig(
                id = "groups",
                titleRes = R.string.feature_shared_groups,
                icon = Icons.Rounded.Groups,
                color = Color(0xFF00BCD4),
                destination = NavigationDestination.SharedExpenseGroups,
                descriptionRes = R.string.feature_shared_groups_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "backup-restore",
                titleRes = R.string.feature_backup_restore,
                icon = Icons.Rounded.Backup,
                color = Color(0xFF607D8B),
                destination = NavigationDestination.BackupRestore,
                descriptionRes = R.string.feature_backup_restore_desc,
                isNew = true
            ),
            FeatureConfig(
                id = "privacy",
                titleRes = R.string.feature_privacy_settings,
                icon = Icons.Rounded.Security,
                color = Color(0xFF9C27B0),
                destination = NavigationDestination.PrivacySettings,
                descriptionRes = R.string.feature_privacy_settings_desc,
                isNew = false
            )
        )
        
        /**
         * RP-17 17-A / D1: features actually offered in the menu. The bank-sync
         * entry is withheld entirely when the central availability policy says
         * the surface is unavailable (release builds) — it is never rendered as
         * a dead/empty destination.
         *
         * R19 (H-1 hide): deferred features (see [DeferredFeatureGate]) are also
         * withheld when [deferredAvailable] is false (release builds).
         */
        fun availableFeatures(
            bankSyncAvailable: Boolean,
            deferredAvailable: Boolean = DeferredFeatureGate.available,
            installmentsEnabled: Boolean = InstallmentsFeature.ENABLED
        ): List<FeatureConfig> =
            allFeatures.filter { feature ->
                (bankSyncAvailable || feature.id != BANK_CONNECTIONS_FEATURE_ID) &&
                    (deferredAvailable || !DeferredFeatureGate.isDeferredFeatureId(feature.id)) &&
                    (installmentsEnabled || feature.id != InstallmentsFeature.FEATURE_ID)
            }

        /**
         * Get a feature by its ID.
         */
        fun getById(id: String): FeatureConfig? {
            return allFeatures.find { it.id == id }
        }
        
        /**
         * Get features marked as new.
         */
        fun getNewFeatures(): List<FeatureConfig> {
            return allFeatures.filter { it.isNew }
        }
        
        /**
         * Get features marked as beta.
         */
        fun getBetaFeatures(): List<FeatureConfig> {
            return allFeatures.filter { it.isBeta }
        }

        /** RP-17 17-A: stable id of the bank-connections feature entry. */
        const val BANK_CONNECTIONS_FEATURE_ID = "bank-connections"
    }
}

/**
 * Composable extension to get the resolved title string.
 */
@Composable
fun FeatureConfig.title(): String = stringResource(titleRes)

/**
 * Composable extension to get the resolved description string.
 */
@Composable
fun FeatureConfig.description(): String? = descriptionRes?.let { stringResource(it) }
