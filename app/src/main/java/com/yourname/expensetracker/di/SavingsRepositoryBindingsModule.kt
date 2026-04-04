package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SavingsRepositoryBindingsModule {

    @Binds
    @Singleton
    abstract fun bindDomainSavingsGoalRepository(
        repository: SavingsGoalRepository
    ): DomainSavingsGoalRepository
}
