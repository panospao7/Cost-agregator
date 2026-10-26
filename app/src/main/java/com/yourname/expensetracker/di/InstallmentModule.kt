package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.data.repository.InstallmentExpenseFactsReaderImpl
import com.yourname.expensetracker.data.repository.InstallmentPlanReadRepositoryImpl
import com.yourname.expensetracker.data.repository.InstallmentProjectionPortImpl
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFactsReader
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings of the loan-installments feature (schema 152).
 *
 * InstallmentPlanLifecycleCoordinator is a singleton with an inject constructor, so Hilt builds it
 * without a binding here. Its inputs are the plan DAO (DaoModule), the transaction lifecycle
 * coordinator, the write barrier and the time provider, plus the read-only expense facts port
 * bound below. The read repository (overview, detail, history, suggestions) and the projection
 * port (INV-15) are read-only and bound below.
 */
@Module
@InstallIn(SingletonComponent::class)
object InstallmentModule {

    @Provides
    @Singleton
    fun provideInstallmentExpenseFactsReader(
        impl: InstallmentExpenseFactsReaderImpl
    ): InstallmentExpenseFactsReader = impl

    @Provides
    @Singleton
    fun provideInstallmentEntryLinkReader(
        impl: InstallmentEntryLinkReaderImpl
    ): InstallmentEntryLinkReader = impl

    @Provides
    @Singleton
    fun provideInstallmentPlanReadRepository(
        impl: InstallmentPlanReadRepositoryImpl
    ): InstallmentPlanReadRepository = impl

    @Provides
    @Singleton
    fun provideInstallmentProjectionPort(
        impl: InstallmentProjectionPortImpl
    ): InstallmentProjectionPort = impl
}
