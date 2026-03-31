package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.tax.GreeceTaxConfiguration
import com.yourname.expensetracker.domain.tax.TaxConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Tax DI Module
 * 
 * Provides TaxConfiguration for dependency injection.
 * Default implementation is GreeceTaxConfiguration.
 * Can be extended to support user-selected country or remote configuration.
 */
@Module
@InstallIn(SingletonComponent::class)
object TaxModule {
    
    @Provides
    @Singleton
    fun provideTaxConfiguration(): TaxConfiguration {
        // Default to Greece configuration
        // Future: Load from user preferences or remote config
        return GreeceTaxConfiguration()
    }
}
