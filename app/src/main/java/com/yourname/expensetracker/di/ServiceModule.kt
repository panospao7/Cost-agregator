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
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.service.NavigationTargetResolver
import com.yourname.expensetracker.service.NavigationTargetResolverImpl
import com.yourname.expensetracker.data.repository.WidgetStyleRepositoryImpl
import com.yourname.expensetracker.data.speech.AndroidSpeechInputGateway
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputGateway
import com.yourname.expensetracker.domain.widget.service.WidgetStyleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import com.google.gson.Gson
import com.google.gson.GsonBuilder

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }

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
        nominatim: NominatimGeocodingService,
        privacyGate: PrivacyGate
    ): GeocodingService = CompositeGeocodingService(photon, geoapify, googlePlaces, nominatim, privacyGate)

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
    
    @Provides
    @Singleton
    fun provideWidgetStyleRepository(
        impl: WidgetStyleRepositoryImpl
    ): WidgetStyleRepository = impl

    @Provides
    @Singleton
    fun provideSpeechInputGateway(
        impl: AndroidSpeechInputGateway
    ): SpeechInputGateway = impl

    @Provides
    @Singleton
    fun provideStringDistanceUtils(): com.yourname.expensetracker.domain.util.StringDistanceUtils {
        return com.yourname.expensetracker.domain.util.StringDistanceUtils
    }
}
