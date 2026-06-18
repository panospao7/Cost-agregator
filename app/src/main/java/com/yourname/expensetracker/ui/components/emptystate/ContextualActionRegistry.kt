package com.yourname.expensetracker.ui.components.emptystate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton registry that manages contextual actions for empty states across the app.
 *
 * This registry allows screens to register available actions for empty states,
 * track which actions have been completed, and retrieve actions by screen key.
 */
class ContextualActionRegistry {
    private val actions = mutableMapOf<String, List<EmptyStateAction>>()
    private val completedActionsMap = mutableMapOf<String, Set<String>>()
    private val _completedActions = MutableStateFlow<Set<String>>(emptySet())
    val completedActions: StateFlow<Set<String>> = _completedActions.asStateFlow()

    /**
     * Register actions for a specific screen. Merges with existing actions
     * (does not overwrite). Duplicate action IDs from later registrations
     * replace earlier ones.
     *
     * @param screenKey Unique identifier for the screen (e.g., "warranty", "subscription")
     * @param actions List of available actions for this screen's empty state
     */
    fun registerActions(screenKey: String, actions: List<EmptyStateAction>) {
        val existing = this.actions[screenKey].orEmpty()
        val merged = (existing + actions)
            .associateBy { it.id } // later entries win on duplicate ID
            .values
            .sortedByDescending { it.priority }
        this.actions[screenKey] = merged
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
            val completed = completedActionsMap[screenKey] ?: emptySet()
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
        val updatedForScreen = (completedActionsMap[screenKey] ?: emptySet()) + actionId
        completedActionsMap[screenKey] = updatedForScreen
        _completedActions.value = _completedActions.value + "$screenKey:$actionId"
    }

    /**
     * Check if an action has been completed.
     *
     * @param screenKey Unique identifier for the screen
     * @param actionId Unique identifier for the action
     * @return True if the action has been marked as completed
     */
    fun isCompleted(screenKey: String, actionId: String): Boolean {
        return completedActionsMap[screenKey]?.contains(actionId) ?: false
    }

    /**
     * Clear all completed actions for a screen (e.g., when user resets progress).
     *
     * @param screenKey Unique identifier for the screen
     */
    fun clearCompleted(screenKey: String) {
        completedActionsMap.remove(screenKey)
        _completedActions.value = _completedActions.value.filterNot { it.startsWith("$screenKey:") }.toSet()
    }

    /**
     * Clear all registered actions and completion state.
     */
    fun clearAll() {
        actions.clear()
        completedActionsMap.clear()
        _completedActions.value = emptySet()
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
        val completed = completedActionsMap[screenKey] ?: emptySet()
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
