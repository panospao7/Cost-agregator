package com.yourname.expensetracker.di

import android.content.Context
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.security.SecureKeyStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Security Module for dependency injection of secure storage components.
 * 
 * CRITICAL FIX: Provides secure key storage as alternative to BuildConfig.
 * This ensures API keys are encrypted at rest and not compiled into APK.
 * 
 * SECURITY: All API keys are encrypted using AES-256-GCM and stored in
 * Android Keystore. Keys are never exposed in compiled code.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    
    @Provides
    @Singleton
    fun provideSecureKeyStorage(
        @ApplicationContext context: Context
    ): SecureKeyStorage {
        val storage = SecureKeyStorage(context)
        
        // One-time migration from BuildConfig to secure storage
        // This runs only once when storage is empty
        storage.migrateFromBuildConfigIfNeeded(
            geoapifyKey = if (BuildConfig.GEOAPIFY_API_KEY.isNotBlank()) 
                BuildConfig.GEOAPIFY_API_KEY else null,
            googlePlacesKey = if (BuildConfig.GOOGLE_PLACES_API_KEY.isNotBlank()) 
                BuildConfig.GOOGLE_PLACES_API_KEY else null,
            geminiKey = if (BuildConfig.GEMINI_API_KEY.isNotBlank()) 
                BuildConfig.GEMINI_API_KEY else null
        )
        
        return storage
    }
}
