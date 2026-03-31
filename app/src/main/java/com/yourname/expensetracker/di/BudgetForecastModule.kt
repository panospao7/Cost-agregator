package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.budget.BudgetForecastingEngine
import com.yourname.expensetracker.domain.budget.BudgetRecommendationEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BudgetForecastModule {
    
    @Provides
    @Singleton
    fun provideBudgetForecastingEngine(engine: BudgetForecastingEngine): BudgetForecastingEngine {
        return engine
    }
    
    @Provides
    @Singleton
    fun provideBudgetRecommendationEngine(engine: BudgetRecommendationEngine): BudgetRecommendationEngine {
        return engine
    }
}
