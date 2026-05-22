package com.yourname.expensetracker.di

import android.content.Context
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.workers.WorkerDrainController
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {
    @Binds
    @Singleton
    abstract fun bindWorkerLeaseRegistry(impl: WorkerLeaseRegistryImpl): WorkerLeaseRegistry

    @Binds
    @Singleton
    abstract fun bindWorkerDrainController(impl: WorkerLeaseRegistryImpl): WorkerDrainController

    companion object {
        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
            return WorkManager.getInstance(context)
        }
    }
}
