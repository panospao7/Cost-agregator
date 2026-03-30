package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.DatabaseBackupRepositoryImpl
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupRepositoryModule {
    
    @Provides
    @Singleton
    fun provideDatabaseBackupRepository(
        impl: DatabaseBackupRepositoryImpl
    ): DatabaseBackupRepository = impl
}
