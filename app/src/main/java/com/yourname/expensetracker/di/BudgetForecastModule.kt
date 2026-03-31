package com.yourname.expensetracker.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Budget Forecast DI Module
 * 
 * BudgetForecastingEngine and BudgetRecommendationEngine are injected directly
 * via their @Inject constructors - no provider methods needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object BudgetForecastModule {
    // Empty - classes use constructor injection via @Inject
}
