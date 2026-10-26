package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.privacy.applock.AndroidDeviceAuthCapabilityProvider
import com.yourname.expensetracker.data.privacy.applock.AppLockSettingsRepositoryImpl
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
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

    // The prompt adapter (BiometricPromptDeviceAuthenticator) is bound to an Activity, so it is
    // created by the UI shell and is intentionally not bound here (S15, TECH-1).
    @Binds
    @Singleton
    abstract fun bindDeviceAuthCapabilityProvider(
        impl: AndroidDeviceAuthCapabilityProvider
    ): DeviceAuthCapabilityProvider
}
