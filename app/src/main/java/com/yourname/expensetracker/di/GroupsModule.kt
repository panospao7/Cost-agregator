package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.data.repository.GroupsRepositoryImpl
import com.yourname.expensetracker.data.repository.SharedExpenseDataPortAdapter
import com.yourname.expensetracker.domain.groups.usecase.AddGroupExpenseUseCase
import com.yourname.expensetracker.domain.groups.usecase.DeleteGroupMemberUseCase
import com.yourname.expensetracker.domain.groups.usecase.DeleteGroupUseCase
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
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
    fun provideGroupsRepository(impl: GroupsRepositoryImpl): GroupsRepository = impl

    @Provides
    @Singleton
    fun bindSharedExpenseDataPort(
        adapter: SharedExpenseDataPortAdapter
    ): SharedExpenseDataPort = adapter

    @Provides
    fun provideDeleteGroupMemberUseCase(
        repository: GroupsRepository
    ): DeleteGroupMemberUseCase = DeleteGroupMemberUseCase(repository)

    @Provides
    fun provideDeleteGroupUseCase(
        repository: GroupsRepository
    ): DeleteGroupUseCase = DeleteGroupUseCase(repository)

    @Provides
    fun provideAddGroupExpenseUseCase(
        repository: GroupsRepository
    ): AddGroupExpenseUseCase = AddGroupExpenseUseCase(repository)
}
