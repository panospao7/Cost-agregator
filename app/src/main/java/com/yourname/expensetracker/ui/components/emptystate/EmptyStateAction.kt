package com.yourname.expensetracker.ui.components.emptystate

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.yourname.expensetracker.ui.navigation.NavigationDestination

/**
 * Data class representing a single action available in an empty state.
 *
 * @property id Unique identifier for the action
 * @property titleRes String resource ID for the action title
 * @property descriptionRes String resource ID for the action description
 * @property icon Icon to display for the action
 * @property action The type of action to perform when clicked
 * @property priority Higher values indicate more important actions (sorted descending)
 */
data class EmptyStateAction(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val action: EmptyStateActionType,
    val priority: Int = 0
)

/**
 * S2-007R: Typed feature action enum — replaces raw string IDs in OpenFeature.
 * Adding a new value here requires updating the executor (compile-time enforcement).
 */
enum class EmptyStateFeatureAction {
    AddWarranty,
    NotificationSettings,
    AddSubscription,
    CreateSavingsGoal,
    SavingsRecommendations,
    CreateChallenge,
    NoSpendStreak,
    CarbonOffset,
    IncomeSettings
}

/**
 * Sealed class representing different types of actions that can be performed
 * from an empty state.
 */
sealed class EmptyStateActionType {
    data class NavigateToDestination(val destination: NavigationDestination) : EmptyStateActionType()
    data class ExecuteAction(val action: () -> Unit) : EmptyStateActionType()
    /** S2-007R: Typed — no raw strings. Executor must handle every enum value. */
    data class OpenFeature(val feature: EmptyStateFeatureAction) : EmptyStateActionType()
}
