package com.yourname.expensetracker.ui.components.emptystate

import org.junit.Assert.*
import org.junit.Test

/**
 * Contract test ensuring all EmptyStateScreenKeys have registered actions
 * in the DefaultEmptyStateRegistryInitializer.
 */
class EmptyStateRegistryCompletenessTest {

    @Test
    fun `all screen keys have registered actions after initialization`() {
        val registry = ContextualActionRegistry()
        val initializer = DefaultEmptyStateRegistryInitializer()
        initializer.initialize(registry)

        val allKeys = listOf(
            EmptyStateScreenKeys.WARRANTY,
            EmptyStateScreenKeys.SUBSCRIPTION,
            EmptyStateScreenKeys.SAVINGS,
            EmptyStateScreenKeys.CHALLENGES,
            EmptyStateScreenKeys.CARBON,
            EmptyStateScreenKeys.LIFESTYLE,
            EmptyStateScreenKeys.TRANSACTIONS,
            EmptyStateScreenKeys.RECEIPTS,
            EmptyStateScreenKeys.ANALYTICS,
            EmptyStateScreenKeys.BUDGET
        )

        allKeys.forEach { key ->
            assertTrue(
                "Screen key '$key' has no registered actions",
                registry.hasActions(key)
            )
            assertTrue(
                "Screen key '$key' has 0 actions",
                registry.getActions(key, excludeCompleted = false).isNotEmpty()
            )
        }
    }

    @Test
    fun `all registered actions have non-blank titles and ids`() {
        val registry = ContextualActionRegistry()
        val initializer = DefaultEmptyStateRegistryInitializer()
        initializer.initialize(registry)

        val allKeys = listOf(
            EmptyStateScreenKeys.WARRANTY,
            EmptyStateScreenKeys.SUBSCRIPTION,
            EmptyStateScreenKeys.SAVINGS,
            EmptyStateScreenKeys.CHALLENGES,
            EmptyStateScreenKeys.CARBON,
            EmptyStateScreenKeys.LIFESTYLE,
            EmptyStateScreenKeys.TRANSACTIONS,
            EmptyStateScreenKeys.RECEIPTS,
            EmptyStateScreenKeys.ANALYTICS,
            EmptyStateScreenKeys.BUDGET
        )

        allKeys.forEach { key ->
            registry.getActions(key, excludeCompleted = false).forEach { action ->
                assertTrue("Action in '$key' has blank id", action.id.isNotBlank())
                assertTrue("Action in '$key' has blank title", action.title.isNotBlank())
                assertTrue("Action in '$key' has blank description", action.description.isNotBlank())
            }
        }
    }
}
