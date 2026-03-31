package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.analytics.AdvancedAnalyticsDashboard
import com.yourname.expensetracker.domain.bank.BankApiIntegration
import com.yourname.expensetracker.domain.budget.SharedBudgetManager
import com.yourname.expensetracker.domain.challenge.SpendingChallengeManager
import com.yourname.expensetracker.domain.income.RecurringIncomeTracker
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import com.yourname.expensetracker.domain.tax.TaxEstimator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object Phase4FeaturesModule {
    
    @Provides
    @Singleton
    fun provideBankApiIntegration(integration: BankApiIntegration): BankApiIntegration = integration
    
    @Provides
    @Singleton
    fun provideAdvancedAnalyticsDashboard(dashboard: AdvancedAnalyticsDashboard): AdvancedAnalyticsDashboard = dashboard
    
    @Provides
    @Singleton
    fun provideSharedBudgetManager(manager: SharedBudgetManager): SharedBudgetManager = manager
    
    @Provides
    @Singleton
    fun provideRecurringIncomeTracker(tracker: RecurringIncomeTracker): RecurringIncomeTracker = tracker
    
    @Provides
    @Singleton
    fun provideTaxEstimator(estimator: TaxEstimator): TaxEstimator = estimator
    
    @Provides
    @Singleton
    fun provideBillReminderManager(manager: BillReminderManager): BillReminderManager = manager
    
    @Provides
    @Singleton
    fun provideSpendingChallengeManager(manager: SpendingChallengeManager): SpendingChallengeManager = manager
}
