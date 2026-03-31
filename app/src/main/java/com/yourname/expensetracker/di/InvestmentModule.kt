package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.investment.InvestmentTracker
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * REMOVED: InvestmentTracker is already injectable via @Inject constructor.
 * This module was causing circular dependency issues.
 */
@Module
@InstallIn(SingletonComponent::class)
object InvestmentModule {
    // InvestmentTracker has @Inject constructor, no need for provides method
}
