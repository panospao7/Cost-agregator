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
        // CRIT-05 FIX: API keys removed from BuildConfig
        // Keys must now be configured via runtime secure storage
        // Users should set keys through app settings or secure configuration
        return SecureKeyStorage(context)
    }
}
