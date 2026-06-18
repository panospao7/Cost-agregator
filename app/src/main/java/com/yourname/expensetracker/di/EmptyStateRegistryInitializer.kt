package com.yourname.expensetracker.di

import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry

/**
 * Contributes empty-state action registration logic.
 *
 * Implementations can live in feature/presentation modules while the core DI layer
 * remains free of Compose/icon/navigation wiring details.
 */
interface EmptyStateRegistryInitializer {
    fun initialize(registry: ContextualActionRegistry)
}
