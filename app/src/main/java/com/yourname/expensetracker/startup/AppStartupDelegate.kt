package com.yourname.expensetracker.startup

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

object AppStartupDelegate {

    fun initialize(application: Application) {
        EntryPointAccessors.fromApplication(
            application,
            AppStartupEntryPoint::class.java
        ).appStartupCoordinator().initialize(application)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppStartupEntryPoint {
    fun appStartupCoordinator(): AppStartupCoordinator
}
