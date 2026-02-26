package com.yourname.expensetracker.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Database and DAO providers are now in DatabaseModule and DaoModule
    // Service providers are now in ServiceModule
    // This module exists for backwards compatibility and future expansion
}
