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
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
        recurringLifecycleCoordinator: com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator,
        recurringOccurrenceDao: com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
    ): CashFlowCalculator = CashFlowCalculator(
        expenseRepository,
        recurringPatternsProvider,
        timeProvider,
        recurringLifecycleCoordinator,
        recurringOccurrenceDao
    )
}
