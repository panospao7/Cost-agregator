package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.privacy.PrivacySettingsRepositoryImpl
import com.yourname.expensetracker.domain.privacy.BackupPrivacyGate
import com.yourname.expensetracker.domain.privacy.CloudAiPrivacyGate
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.LocationPrivacyGate
import com.yourname.expensetracker.domain.privacy.NotificationPrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
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

    companion object {

        @Provides
        @Singleton
        fun providePrivacyGate(
            notificationGate: NotificationPrivacyGate,
            locationGate: LocationPrivacyGate,
            cloudAiGate: CloudAiPrivacyGate,
            backupGate: BackupPrivacyGate
        ): PrivacyGate {
            return CompositePrivacyGate(listOf(notificationGate, locationGate, cloudAiGate, backupGate))
        }

        @Provides
        @Singleton
        fun providePrivacyAuditLogger(): PrivacyAuditLogger {
            return object : PrivacyAuditLogger {
                override suspend fun logDecision(
                    capability: PrivacyCapability,
                    decision: PrivacyDecision,
                    context: Map<String, String>
                ) {
                    // Stub — no-op until the audit database DAO is wired in
                }
            }
        }
    }
}
