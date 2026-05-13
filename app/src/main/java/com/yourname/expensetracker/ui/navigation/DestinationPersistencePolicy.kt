package com.yourname.expensetracker.ui.navigation

/**
 * Classifies how each destination behaves on state restoration.
 *
 * - FULL: Restores exactly as saved (all params preserved)
 * - DEGRADED: Restores but loses entity payload (navigates back on null payload)
 * - EPHEMERAL: Not meaningfully restorable (returns to previous on restore)
 */
enum class DestinationPersistencePolicy {
    FULL,
    DEGRADED,
    EPHEMERAL
}

/**
 * Returns the persistence policy for this destination.
 * Used by tests to document and enforce restoration behavior.
 */
fun NavigationDestination.persistencePolicy(): DestinationPersistencePolicy = when (this) {
    is NavigationDestination.BudgetForecasting -> DestinationPersistencePolicy.DEGRADED
    is NavigationDestination.VisualSplitEditor -> DestinationPersistencePolicy.DEGRADED
    is NavigationDestination.AddExpense -> DestinationPersistencePolicy.EPHEMERAL
    is NavigationDestination.ScanReceipt -> DestinationPersistencePolicy.EPHEMERAL
    is NavigationDestination.Assistant -> DestinationPersistencePolicy.EPHEMERAL
    is NavigationDestination.Debug -> DestinationPersistencePolicy.EPHEMERAL
    else -> DestinationPersistencePolicy.FULL
}
