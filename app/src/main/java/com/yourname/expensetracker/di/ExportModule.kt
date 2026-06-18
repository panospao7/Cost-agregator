package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExportModule {
    
    @Provides
    @Singleton
    fun provideQuickBooksIIFExporter(): QuickBooksIIFExporter = QuickBooksIIFExporter()
    
    @Provides
    @Singleton
    fun provideXeroCSVExporter(): XeroCSVExporter = XeroCSVExporter()
    
    @Provides
    @Singleton
    fun provideFreshBooksExporter(): FreshBooksExporter = FreshBooksExporter()
}
