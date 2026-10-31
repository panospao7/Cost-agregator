package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepositoryImpl
import com.yourname.expensetracker.domain.reminder.InstallmentReminderLedger
import com.yourname.expensetracker.domain.reminder.InstallmentReminderLedgerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderSettingsModule {

    @Binds
    @Singleton
    abstract fun bindBillReminderSettingsRepository(
        impl: BillReminderSettingsRepositoryImpl
    ): BillReminderSettingsRepository

    @Binds
    @Singleton
    abstract fun bindInstallmentReminderLedger(
        impl: InstallmentReminderLedgerImpl
    ): InstallmentReminderLedger
}
