package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.email.EmailReceiptIngestionService
import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI module for email receipt ingestion feature (F14).
 * Provides parsers and ingestion service for processing email receipts
 * from providers like Amazon, Uber, and Apple.
 */
@Module
@InstallIn(SingletonComponent::class)
object EmailIngestionModule {

    @Provides
    @Singleton
    fun provideAmazonReceiptParser(): AmazonReceiptParser {
        return AmazonReceiptParser()
    }

    @Provides
    @Singleton
    fun provideUberReceiptParser(): UberReceiptParser {
        return UberReceiptParser()
    }

    @Provides
    @Singleton
    fun provideAppleReceiptParser(): AppleReceiptParser {
        return AppleReceiptParser()
    }
}
