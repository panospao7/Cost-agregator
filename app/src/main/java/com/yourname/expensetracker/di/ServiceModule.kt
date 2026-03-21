package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.location.AndroidForegroundLocationProvider
import com.yourname.expensetracker.data.location.CompositeGeocodingService
import com.yourname.expensetracker.data.location.GeoapifyGeocodingService
import com.yourname.expensetracker.data.location.GooglePlacesGeocodingService
import com.yourname.expensetracker.data.location.NominatimGeocodingService
import com.yourname.expensetracker.data.location.OverpassNearbyService
import com.yourname.expensetracker.data.location.PhotonGeocodingService
import com.yourname.expensetracker.data.service.AndroidNotificationService
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.location.NearbyPoiService
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.service.NavigationTargetResolver
import com.yourname.expensetracker.service.NavigationTargetResolverImpl
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

    /**
     * Binds the cascade geocoding service as the app-wide [GeocodingService].
     * - Interactive picker ([searchMultiple]): Photon → Geoapify → Google Places → Nominatim
     * - Background resolution ([search]): Nominatim only (unchanged behaviour)
     */
    @Provides
    @Singleton
    fun provideGeocodingService(
        photon: PhotonGeocodingService,
        geoapify: GeoapifyGeocodingService,
        googlePlaces: GooglePlacesGeocodingService,
        nominatim: NominatimGeocodingService
    ): GeocodingService = CompositeGeocodingService(photon, geoapify, googlePlaces, nominatim)

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

    @Provides
    @Singleton
    fun provideNavigationTargetResolver(
        impl: NavigationTargetResolverImpl
    ): NavigationTargetResolver = impl
}
