package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CurrencyModule {
    
    @Provides
    @Singleton
    fun provideCurrencyConverter(converter: CurrencyConverter): CurrencyConverter {
        return converter
    }
    
    @Provides
    @Singleton
    fun provideMultiCurrencyRepository(repository: MultiCurrencyRepository): MultiCurrencyRepository {
        return repository
    }
}
