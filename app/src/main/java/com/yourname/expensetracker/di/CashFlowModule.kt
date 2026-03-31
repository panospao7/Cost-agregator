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
        recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine,
        recurringExpenseRepository: com.yourname.expensetracker.data.repository.RecurringExpenseRepository,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): CashFlowCalculator = CashFlowCalculator(
        expenseRepository,
        recurringExpenseEngine,
        recurringExpenseRepository,
        timeProvider
    )
}
