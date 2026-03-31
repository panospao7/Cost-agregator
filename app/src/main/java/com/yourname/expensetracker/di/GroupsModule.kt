package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.groups.SettlementCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GroupsModule {
    
    @Provides
    @Singleton
    fun provideSharedExpenseManager(manager: SharedExpenseManager): SharedExpenseManager {
        return manager
    }
    
    @Provides
    @Singleton
    fun provideSettlementCalculator(calculator: SettlementCalculator): SettlementCalculator {
        return calculator
    }
}
