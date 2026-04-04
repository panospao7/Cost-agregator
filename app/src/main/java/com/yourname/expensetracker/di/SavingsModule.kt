package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.savings.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SavingsModule {
    
    @Provides
    @Singleton
    fun provideSmartSavingsEngine(
        expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
        budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
        budgetCalculator: com.yourname.expensetracker.domain.budget.BudgetCalculator,
        monteCarloSimulator: com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator,
        savingsGoalRepository: com.yourname.expensetracker.data.repository.SavingsGoalRepository,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): SmartSavingsEngine = SmartSavingsEngine(
        expenseRepository,
        budgetRepository,
        budgetCalculator,
        monteCarloSimulator,
        savingsGoalRepository,
        timeProvider
    )
    
    @Provides
    @Singleton
    fun provideAutomatedSavingsRuleEngine(
        expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
        categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
        savingsGoalRepository: com.yourname.expensetracker.data.repository.SavingsGoalRepository,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): AutomatedSavingsRuleEngine = AutomatedSavingsRuleEngine(
        expenseRepository,
        categoryRepository,
        savingsGoalRepository,
        timeProvider
    )
    
    @Provides
    @Singleton
    fun provideSavingsGamificationEngine(
        savingsGoalRepository: com.yourname.expensetracker.data.repository.SavingsGoalRepository,
        timeProvider: com.yourname.expensetracker.domain.util.TimeProvider
    ): SavingsGamificationEngine = SavingsGamificationEngine(
        savingsGoalRepository,
        timeProvider
    )
}
