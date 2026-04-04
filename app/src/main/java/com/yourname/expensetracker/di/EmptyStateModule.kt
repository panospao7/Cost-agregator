package com.yourname.expensetracker.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Infrastructure-only bindings for empty-state extension points.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmptyStateModule {

    @Multibinds
    abstract fun bindEmptyStateRegistryInitializers(): Set<EmptyStateRegistryInitializer>
}
