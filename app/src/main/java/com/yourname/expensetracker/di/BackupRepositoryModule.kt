package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpenerImpl
import com.yourname.expensetracker.data.backup.DataStoreMaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.repository.DatabaseBackupRepositoryImpl
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupRepositoryModule {

    @Binds @Singleton
    abstract fun bindMaintenanceSafeDiagnosticSink(impl: DataStoreMaintenanceSafeDiagnosticSink): MaintenanceSafeDiagnosticSink

    @Binds @Singleton
    abstract fun bindRestoreDatabaseOpener(impl: RestoreDatabaseOpenerImpl): RestoreDatabaseOpener

    companion object {
        @Provides @Singleton
        fun provideDatabaseBackupRepository(impl: DatabaseBackupRepositoryImpl): DatabaseBackupRepository = impl
    }
}
