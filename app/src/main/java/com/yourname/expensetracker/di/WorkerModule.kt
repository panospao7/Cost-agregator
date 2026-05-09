package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.workers.WorkerRunLogger
import com.yourname.expensetracker.domain.workers.WorkerRunLoggerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {
    @Binds
    @Singleton
    abstract fun bindWorkerRunLogger(impl: WorkerRunLoggerImpl): WorkerRunLogger
}