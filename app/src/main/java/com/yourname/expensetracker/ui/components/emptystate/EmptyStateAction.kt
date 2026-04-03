package com.yourname.expensetracker.ui.components.emptystate

import androidx.compose.ui.graphics.vector.ImageVector
import com.yourname.expensetracker.ui.navigation.NavigationDestination

/**
 * Data class representing a single action available in an empty state.
 *
 * @property id Unique identifier for the action
 * @property title Display title for the action
 * @property description Brief description of what the action does
 * @property icon Icon to display for the action
 * @property action The type of action to perform when clicked
 * @property priority Higher values indicate more important actions (sorted descending)
 */
data class EmptyStateAction(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val action: EmptyStateActionType,
    val priority: Int = 0
)

/**
 * Sealed class representing different types of actions that can be performed
 * from an empty state.
 */
sealed class EmptyStateActionType {
    /**
     * Navigate to a specific destination in the app.
     */
    data class NavigateToDestination(val destination: NavigationDestination) : EmptyStateActionType()

    /**
     * Execute an arbitrary action (lambda).
     */
    data class ExecuteAction(val action: () -> Unit) : EmptyStateActionType()

    /**
     * Open a specific feature by its identifier.
     */
    data class OpenFeature(val feature: String) : EmptyStateActionType()
}
