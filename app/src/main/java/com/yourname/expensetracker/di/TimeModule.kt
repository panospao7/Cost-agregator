package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.SystemTimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for binding the TimeProvider interface.
 * Uses an abstract module because @Binds methods must be declared on abstract types.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    
    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
}
