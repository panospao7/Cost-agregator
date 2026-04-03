package com.yourname.expensetracker.ui.components.emptystate

import androidx.compose.ui.graphics.vector.ImageVector
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton registry that manages contextual actions for empty states across the app.
 *
 * This registry allows screens to register available actions for empty states,
 * track which actions have been completed, and retrieve actions by screen key.
 */
@Singleton
class ContextualActionRegistry @Inject constructor() {
    private val actions = mutableMapOf<String, List<EmptyStateAction>>()
    private val completedActions = mutableMapOf<String, MutableSet<String>>()

    /**
     * Register actions for a specific screen.
     *
     * @param screenKey Unique identifier for the screen (e.g., "warranty", "subscription")
     * @param actions List of available actions for this screen's empty state
     */
    fun registerActions(screenKey: String, actions: List<EmptyStateAction>) {
        // Sort by priority (descending) so most important actions appear first
        this.actions[screenKey] = actions.sortedByDescending { it.priority }
    }

    /**
     * Get all registered actions for a screen, filtering out completed ones.
     *
     * @param screenKey Unique identifier for the screen
     * @param excludeCompleted If true, filters out actions marked as completed
     * @return List of available actions, sorted by priority
     */
    fun getActions(
        screenKey: String,
        excludeCompleted: Boolean = true
    ): List<EmptyStateAction> {
        val allActions = actions[screenKey] ?: emptyList()
        return if (excludeCompleted) {
            val completed = completedActions[screenKey] ?: emptySet()
            allActions.filter { it.id !in completed }
        } else {
            allActions
        }
    }

    /**
     * Mark an action as completed for a specific screen.
     *
     * @param screenKey Unique identifier for the screen
     * @param actionId Unique identifier for the action
     */
    fun markCompleted(screenKey: String, actionId: String) {
        completedActions.getOrPut(screenKey) { mutableSetOf() }.add(actionId)
    }

    /**
     * Check if an action has been completed.
     *
     * @param screenKey Unique identifier for the screen
     * @param actionId Unique identifier for the action
     * @return True if the action has been marked as completed
     */
    fun isCompleted(screenKey: String, actionId: String): Boolean {
        return completedActions[screenKey]?.contains(actionId) ?: false
    }

    /**
     * Clear all completed actions for a screen (e.g., when user resets progress).
     *
     * @param screenKey Unique identifier for the screen
     */
    fun clearCompleted(screenKey: String) {
        completedActions.remove(screenKey)
    }

    /**
     * Clear all registered actions and completion state.
     */
    fun clearAll() {
        actions.clear()
        completedActions.clear()
    }

    /**
     * Check if any actions are registered for a screen.
     *
     * @param screenKey Unique identifier for the screen
     * @return True if actions have been registered for this screen
     */
    fun hasActions(screenKey: String): Boolean {
        return actions.containsKey(screenKey)
    }

    /**
     * Get the number of uncompleted actions for a screen.
     *
     * @param screenKey Unique identifier for the screen
     * @return Count of actions not yet completed
     */
    fun getRemainingActionCount(screenKey: String): Int {
        val allActions = actions[screenKey] ?: return 0
        val completed = completedActions[screenKey] ?: emptySet()
        return allActions.count { it.id !in completed }
    }
}

/**
 * Predefined screen keys for consistency across the app.
 */
object EmptyStateScreenKeys {
    const val WARRANTY = "warranty"
    const val SUBSCRIPTION = "subscription"
    const val SAVINGS = "savings"
    const val CHALLENGES = "challenges"
    const val CARBON = "carbon"
    const val LIFESTYLE = "lifestyle"
    const val TRANSACTIONS = "transactions"
    const val RECEIPTS = "receipts"
    const val ANALYTICS = "analytics"
    const val BUDGET = "budget"
}
