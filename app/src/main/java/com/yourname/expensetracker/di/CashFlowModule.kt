package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
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
        expenseRepository: ExpenseRepository,
        recurringPatternsProvider: MergedRecurringPatternsProvider,
        timeProvider: TimeProvider,
        recurringLifecycleCoordinator: RecurringLifecycleCoordinator,
        recurringOccurrenceDao: RecurringOccurrenceDao,
        analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
        currencyConverter: CurrencyConverter,
        currencySettingsRepository: CurrencySettingsRepository
    ): CashFlowCalculator = CashFlowCalculator(
        expenseRepository,
        recurringPatternsProvider,
        timeProvider,
        recurringLifecycleCoordinator,
        recurringOccurrenceDao,
        analyticsCurrencyNormalizer,
        currencySettingsRepository,
        currencyConverter
    )
}
