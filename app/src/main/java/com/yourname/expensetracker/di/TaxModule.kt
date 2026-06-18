package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.tax.GreeceTaxConfiguration
import com.yourname.expensetracker.domain.tax.TaxConfiguration
import com.yourname.expensetracker.domain.tax.TaxRateProvider
import com.yourname.expensetracker.data.tax.DemoTaxRateProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TaxModule {
    
    @Provides
    @Singleton
    fun provideTaxConfiguration(): TaxConfiguration {
        return GreeceTaxConfiguration()
    }

    @Provides
    @Singleton
    fun provideTaxRateProvider(provider: DemoTaxRateProvider): TaxRateProvider = provider
}
