package com.yourname.expensetracker.di

import android.content.Context
import com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.savings.*
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SavingsModule {
    
    @Provides
    @Singleton
    fun provideSmartSavingsEngine(
        expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
        categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
        budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
        budgetCalculator: com.yourname.expensetracker.domain.budget.BudgetCalculator,
        monteCarloSimulator: com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
        analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
        cashFlowCalculator: com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
    ): SmartSavingsEngine = SmartSavingsEngine(
        expenseRepository,
        categoryRepository,
        budgetRepository,
        budgetCalculator,
        monteCarloSimulator,
        timeProvider,
        analyticsCurrencyNormalizer,
        cashFlowCalculator
    )
    
    @Provides
    @Singleton
    fun provideAutomatedSavingsRuleStateRepository(
        @ApplicationContext context: Context,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): AutomatedSavingsRuleStateRepository = AutomatedSavingsRuleStateRepository(
        AutomatedSavingsRuleStateRepository.createDataStore(context),
        timeProvider
    )

    @Provides
    @Singleton
    fun provideSavingsContributionHistoryRepository(
        @ApplicationContext context: Context,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): SavingsContributionHistoryRepository = SavingsContributionHistoryRepository(
        SavingsContributionHistoryRepository.createDataStore(context),
        timeProvider
    )

    @Provides
    @Singleton
    fun provideAutomatedSavingsRuleEngine(
        expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
        categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
        ruleStateRepository: AutomatedSavingsRuleStateRepository,
        analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
        currencySettingsRepository: CurrencySettingsRepository
    ): AutomatedSavingsRuleEngine = AutomatedSavingsRuleEngine(
        expenseRepository,
        categoryRepository,
        timeProvider,
        ruleStateRepository,
        analyticsCurrencyNormalizer,
        currencySettingsRepository
    )
    
    @Provides
    @Singleton
    fun provideSavingsGamificationEngine(
        savingsGoalRepository: DomainSavingsGoalRepository,
        savingsContributionHistoryRepository: SavingsContributionHistoryRepository,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
        analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
        currencySettingsRepository: CurrencySettingsRepository
    ): SavingsGamificationEngine = SavingsGamificationEngine(
        savingsGoalRepository,
        savingsContributionHistoryRepository,
        timeProvider,
        analyticsCurrencyNormalizer,
        currencySettingsRepository
    )
}
