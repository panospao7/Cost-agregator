package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.privacy.AndroidKeystoreInstallationSecretKeyProvider
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.data.privacy.KeystoreInstallationSecretHasher
import com.yourname.expensetracker.data.privacy.PrivacyAuditLoggerImpl
import com.yourname.expensetracker.data.privacy.PrivacySettingsRepositoryImpl
import com.yourname.expensetracker.domain.privacy.BackupPrivacyGate
import com.yourname.expensetracker.domain.privacy.CloudAiPrivacyGate
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.InstallationSecretHasher
import com.yourname.expensetracker.data.privacy.InstallationSecretKeyProvider
import com.yourname.expensetracker.domain.privacy.LocationPrivacyGate
import com.yourname.expensetracker.domain.privacy.NotificationPrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import com.yourname.expensetracker.domain.privacy.PrivacyCapabilityHandlingPolicy
import com.yourname.expensetracker.domain.privacy.ExportPrivacyGate
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrivacyModule {

    @Binds
    @Singleton
    abstract fun bindPrivacySettingsRepository(
        impl: PrivacySettingsRepositoryImpl
    ): PrivacySettingsRepository

    @Binds
    @Singleton
    abstract fun bindCloudPayloadRedactor(
        impl: DefaultCloudPayloadRedactor
    ): CloudPayloadRedactor

    @Binds
    @Singleton
    abstract fun bindSensitiveHashingService(
        impl: DefaultSensitiveHashingService
    ): SensitiveHashingService

    @Binds
    @Singleton
    abstract fun bindCloudPayloadPolicy(
        impl: DefaultCloudPayloadPolicy
    ): CloudPayloadPolicy

    /** RP-15 (15-C, D13): the ONE Keystore-backed installation-secret HMAC service. */
    @Binds
    @Singleton
    abstract fun bindInstallationSecretHasher(
        impl: KeystoreInstallationSecretHasher
    ): InstallationSecretHasher

    @Binds
    @Singleton
    abstract fun bindInstallationSecretKeyProvider(
        impl: AndroidKeystoreInstallationSecretKeyProvider
    ): InstallationSecretKeyProvider

    companion object {

        @Provides
        @Singleton
        fun providePrivacyGate(
            notificationGate: NotificationPrivacyGate,
            locationGate: LocationPrivacyGate,
            cloudAiGate: CloudAiPrivacyGate,
            backupGate: BackupPrivacyGate,
            privacySettingsRepository: PrivacySettingsRepository,
            auditLogger: PrivacyAuditLogger
        ): PrivacyGate {
            // PR8: ExportPrivacyGate added — release builds always block RAW_DATABASE_EXPORT
            val exportGate = ExportPrivacyGate(
                settingsRepository = privacySettingsRepository,
                isDebugBuild = com.yourname.expensetracker.BuildConfig.DEBUG
            )
            // PRIV-441-03: Pass gateHandledCapabilities so composite fails closed for unhandled sensitive capabilities
            return CompositePrivacyGate(
                gates = listOf(notificationGate, locationGate, cloudAiGate, backupGate, exportGate),
                auditLogger = auditLogger,
                gateHandledCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
            )
        }

        @Provides
        @Singleton
        fun providePrivacyAuditLogger(
            impl: PrivacyAuditLoggerImpl
        ): PrivacyAuditLogger {
            return impl
        }
    }
}
