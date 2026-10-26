package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.privacy.applock.AndroidDeviceAuthCapabilityProvider
import com.yourname.expensetracker.data.privacy.applock.AppLockSettingsRepositoryImpl
import com.yourname.expensetracker.data.privacy.applock.SystemMonotonicClock
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.MonotonicClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The main-thread dispatcher the app-lock controller hops to for the system prompt calls
 * (`DeviceAuthenticator.authenticate` and `cancel` must run on the main thread, S16 P5). Qualified so
 * it is never confused with the IO and default dispatchers of `DispatchersModule`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppLockMainDispatcher

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
    // created by the UI shell and is intentionally not bound here (S15, TECH-1). The controller takes a
    // DeviceAuthenticator port; S20 binds a process-level registration relay for it (the controller is
    // dormant until then, so the missing binding is not part of any reachable graph yet).
    @Binds
    @Singleton
    abstract fun bindDeviceAuthCapabilityProvider(
        impl: AndroidDeviceAuthCapabilityProvider
    ): DeviceAuthCapabilityProvider

    // The timer clock of AppLockController (S16). The controller itself is a @Singleton with an
    // @Inject constructor, so it needs no binding here.
    @Binds
    @Singleton
    abstract fun bindMonotonicClock(impl: SystemMonotonicClock): MonotonicClock

    companion object {
        @Provides
        @AppLockMainDispatcher
        fun provideAppLockMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
    }
}
