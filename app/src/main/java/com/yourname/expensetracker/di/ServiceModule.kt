package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.location.AndroidForegroundLocationProvider
import com.yourname.expensetracker.data.location.NominatimGeocodingService
import com.yourname.expensetracker.data.location.OverpassNearbyService
import com.yourname.expensetracker.data.service.AndroidNotificationService
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.NearbyPoiService
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

    @Provides
    @Singleton
    fun provideGeocodingService(
        service: NominatimGeocodingService
    ): GeocodingService = service

    @Provides
    @Singleton
    fun provideNearbyPoiService(
        service: OverpassNearbyService
    ): NearbyPoiService = service

    @Provides
    @Singleton
    fun provideForegroundLocationProvider(
        provider: AndroidForegroundLocationProvider
    ): ForegroundLocationProvider = provider
}

