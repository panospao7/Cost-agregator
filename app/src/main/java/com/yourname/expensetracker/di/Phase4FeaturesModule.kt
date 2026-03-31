package com.yourname.expensetracker.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * REMOVED: All classes now use @Inject constructors.
 * Previous provider methods were causing circular dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object Phase4FeaturesModule {
    // All classes have @Inject constructors, no provider methods needed
}
