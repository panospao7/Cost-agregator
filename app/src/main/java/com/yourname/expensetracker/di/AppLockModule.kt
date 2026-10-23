package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.privacy.applock.AppLockSettingsRepositoryImpl
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App-lock / privacy-mode bindings. One module, one new single-instance settings store (OD-11a). */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppLockModule {

    @Binds
    @Singleton
    abstract fun bindAppLockSettingsRepository(
        impl: AppLockSettingsRepositoryImpl
    ): AppLockSettingsRepository
}
