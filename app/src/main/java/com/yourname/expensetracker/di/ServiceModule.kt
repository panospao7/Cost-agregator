package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.service.AndroidNotificationService
import com.yourname.expensetracker.domain.service.NotificationService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideNotificationService(
        service: AndroidNotificationService
    ): NotificationService = service
}
