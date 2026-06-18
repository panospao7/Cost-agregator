package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepositoryImpl
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
}
