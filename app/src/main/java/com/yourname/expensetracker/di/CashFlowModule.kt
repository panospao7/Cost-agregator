package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CashFlowModule {
    
    @Provides
    @Singleton
    fun provideCashFlowCalculator(
        expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
        recurringPatternsProvider: com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): CashFlowCalculator = CashFlowCalculator(
        expenseRepository,
        recurringPatternsProvider,
        timeProvider
    )
}
