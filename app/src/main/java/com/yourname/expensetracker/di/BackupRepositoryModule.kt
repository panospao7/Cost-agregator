package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.TimberMaintenanceSafeDiagnosticSink
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

    @Binds
    @Singleton
    abstract fun bindMaintenanceSafeDiagnosticSink(
        impl: TimberMaintenanceSafeDiagnosticSink
    ): MaintenanceSafeDiagnosticSink

    companion object {
        @Provides
        @Singleton
        fun provideDatabaseBackupRepository(
            impl: DatabaseBackupRepositoryImpl
        ): DatabaseBackupRepository = impl
    }
}
