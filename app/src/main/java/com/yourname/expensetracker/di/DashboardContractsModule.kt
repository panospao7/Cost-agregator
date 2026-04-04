package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.DashboardContractsAdapter
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardBudgetRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardCategoryRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardExpenseRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardFinancialWeatherRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardReviewQueueRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardSavingsGoalRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DashboardContractsModule {

    @Binds
    @Singleton
    abstract fun bindDashboardExpenseRepository(
        adapter: DashboardContractsAdapter
    ): DashboardExpenseRepository

    @Binds
    @Singleton
    abstract fun bindDashboardCategoryRepository(
        adapter: DashboardContractsAdapter
    ): DashboardCategoryRepository

    @Binds
    @Singleton
    abstract fun bindDashboardBudgetRepository(
        adapter: DashboardContractsAdapter
    ): DashboardBudgetRepository

    @Binds
    @Singleton
    abstract fun bindDashboardReviewQueueRepository(
        adapter: DashboardContractsAdapter
    ): DashboardReviewQueueRepository

    @Binds
    @Singleton
    abstract fun bindDashboardFinancialWeatherRepository(
        adapter: DashboardContractsAdapter
    ): DashboardFinancialWeatherRepository

    @Binds
    @Singleton
    abstract fun bindDashboardSavingsGoalRepository(
        adapter: DashboardContractsAdapter
    ): DashboardSavingsGoalRepository

    @Binds
    @Singleton
    abstract fun bindDashboardAnalyticsRepository(
        adapter: DashboardContractsAdapter
    ): DashboardAnalyticsRepository
}
