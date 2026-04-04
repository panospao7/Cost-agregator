package com.yourname.expensetracker.ui.components.emptystate

import com.yourname.expensetracker.di.EmptyStateRegistryInitializer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EmptyStatePresentationModule {

    @Binds
    @IntoSet
    abstract fun bindDefaultInitializer(
        impl: DefaultEmptyStateRegistryInitializer
    ): EmptyStateRegistryInitializer

    companion object {
        @Provides
        @Singleton
        fun provideContextualActionRegistry(
            initializers: Set<@JvmSuppressWildcards EmptyStateRegistryInitializer>
        ): ContextualActionRegistry {
            return ContextualActionRegistry().apply {
                initializers.forEach { initializer ->
                    initializer.initialize(this)
                }
            }
        }
    }
}
