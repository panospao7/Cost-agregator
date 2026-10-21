package com.yourname.expensetracker.startup

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

object AppStartupDelegate {

    fun initialize(application: Application) {
        val coordinator: AppStartupCoordinator = EntryPointAccessors.fromApplication(
            application,
            AppStartupEntryPoint::class.java
        ).appStartupCoordinator()
        coordinator.initialize(application)
    }

    /** R25 D-M2: journal crash-recovery stage only (see [AppStartupCoordinator.recoverRestoreJournalOnly]). */
    fun recoverRestoreJournalOnly(application: Application) {
        EntryPointAccessors.fromApplication(
            application,
            AppStartupEntryPoint::class.java
        ).appStartupCoordinator().recoverRestoreJournalOnly()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppStartupEntryPoint {
    fun appStartupCoordinator(): AppStartupCoordinator
}
