package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.ai.service.AppForegroundState
import com.yourname.expensetracker.startup.ProcessAppForegroundState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the [AppForegroundState] port to the process-lifecycle implementation (ai-fixes B2c).
 * The same singleton instance is registered as a lifecycle observer by AppStartupCoordinator.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppForegroundModule {

    @Binds
    @Singleton
    abstract fun bindAppForegroundState(impl: ProcessAppForegroundState): AppForegroundState
}
