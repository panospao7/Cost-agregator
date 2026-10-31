package com.yourname.expensetracker.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.yourname.expensetracker.data.privacy.applock.AndroidDeviceAuthCapabilityProvider
import com.yourname.expensetracker.data.privacy.applock.AppLockSettingsRepositoryImpl
import com.yourname.expensetracker.data.privacy.applock.SystemMonotonicClock
import com.yourname.expensetracker.data.privacy.applock.pin.AndroidBootCountSource
import com.yourname.expensetracker.data.privacy.applock.pin.AndroidBiometricPinKeyManager
import com.yourname.expensetracker.data.privacy.applock.pin.AppPinStoreImpl
import com.yourname.expensetracker.data.privacy.applock.pin.KeystorePinKeyBinder
import com.yourname.expensetracker.data.privacy.applock.pin.PinKeyBinder
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import com.yourname.expensetracker.domain.privacy.applock.MonotonicClock
import com.yourname.expensetracker.domain.privacy.applock.PinFingerprintKeyGate
import com.yourname.expensetracker.domain.privacy.applock.PinFingerprintKeyState
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStore
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricKeyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricPinKeyManager
import com.yourname.expensetracker.domain.privacy.applock.pin.BootCountSource
import com.yourname.expensetracker.ui.applock.AppLockAuthenticatorRelay
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
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

/** The dedicated `app_lock_pin_store` DataStore of the app PIN (S24). Never share it with another store. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPinDataStore

/** App-lock / privacy-mode bindings. One module, one new single-instance settings store (OD-11a). */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppLockModule {

    @Binds
    @Singleton
    abstract fun bindAppLockSettingsRepository(
        impl: AppLockSettingsRepositoryImpl
    ): AppLockSettingsRepository

    // The prompt adapter (BiometricPromptDeviceAuthenticator) is bound to an Activity, so it is created
    // per Activity by the prompt host and is intentionally not bound here (S15, TECH-1). The controller
    // takes the DeviceAuthenticator port; this binds the process-level relay for it (S20-a). The relay
    // holds the current adapter weakly and is reachable only through this binding and the prompt host.
    @Binds
    @Singleton
    abstract fun bindDeviceAuthenticator(
        impl: AppLockAuthenticatorRelay
    ): DeviceAuthenticator

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

    // App PIN (S24). Dormant: nothing calls setup yet.
    @Binds
    @Singleton
    abstract fun bindAppPinStore(impl: AppPinStoreImpl): AppPinStore

    @Binds
    @Singleton
    abstract fun bindBootCountSource(impl: AndroidBootCountSource): BootCountSource

    @Binds
    @Singleton
    abstract fun bindPinKeyBinder(impl: KeystorePinKeyBinder): PinKeyBinder

    // Fingerprint key of PIN mode (S26, D-I2-4, G26-4). Binds the domain port (domain/privacy/applock/pin) to the
    // Android implementation in data; the prompt host, the key gate below and AppPinStoreImpl consume the port.
    // Its best-effort deletion is step 3 of AppPinStoreImpl.deleteAll().
    @Binds
    @Singleton
    abstract fun bindBiometricPinKeyManager(impl: AndroidBiometricPinKeyManager): BiometricPinKeyManager

    companion object {
        @Provides
        @AppLockMainDispatcher
        fun provideAppLockMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

        /**
         * Domain view of the PIN-mode fingerprint key (S26, S26b). `state` maps the port result to three bounded states;
         * `create` and `delete` (S26b, turning fingerprint unlock on and off) go to the port, not the interface defaults.
         */
        @Provides
        @Singleton
        fun providePinFingerprintKeyGate(manager: BiometricPinKeyManager): PinFingerprintKeyGate =
            object : PinFingerprintKeyGate {
                override fun state(): PinFingerprintKeyState =
                    when (manager.cipherForAuthentication()) {
                        is BiometricKeyResult.Ready -> PinFingerprintKeyState.READY
                        BiometricKeyResult.KeyInvalidated -> PinFingerprintKeyState.INVALIDATED
                        BiometricKeyResult.KeyMissing,
                        BiometricKeyResult.KeystoreError -> PinFingerprintKeyState.UNAVAILABLE
                    }

                override fun createKey(): Boolean = manager.createKey()

                override fun deleteKey() = manager.deleteKey()
            }

        /**
         * The dedicated PIN store (S24 design decision 5): app-private, excluded from backup, no corruption
         * handler (an unreadable file is present-but-unreadable, never silently replaced).
         */
        @Provides
        @Singleton
        @AppPinDataStore
        fun provideAppPinDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { File(context.noBackupFilesDir, PIN_STORE_FILE_NAME) }
            )

        private const val PIN_STORE_FILE_NAME = "app_lock_pin_store.preferences_pb"
    }
}
