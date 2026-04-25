package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.AnomalyAlertRepositoryImpl
import com.yourname.expensetracker.domain.alerts.AnomalyAlertRepository
import com.yourname.expensetracker.domain.usecase.dashboard.AnomalyAlertRepository as DashboardAnomalyAlertRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DashboardAnomalyModule {

    @Binds
    @Singleton
    abstract fun bindDomainAnomalyAlertRepository(
        repository: AnomalyAlertRepositoryImpl
    ): AnomalyAlertRepository

    @Binds
    @Singleton
    abstract fun bindDashboardAnomalyAlertRepository(
        repository: AnomalyAlertRepositoryImpl
    ): DashboardAnomalyAlertRepository
}
