package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.investment.InvestmentTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InvestmentModule {
    
    @Provides
    @Singleton
    fun provideInvestmentTracker(tracker: InvestmentTracker): InvestmentTracker {
        return tracker
    }
}
