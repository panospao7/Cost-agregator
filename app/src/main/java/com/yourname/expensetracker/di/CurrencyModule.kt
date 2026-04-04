package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.CurrencyRatesRepositoryImpl
import com.yourname.expensetracker.data.repository.CurrencySettingsRepositoryImpl
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CurrencyModule {
    
    @Binds
    @Singleton
    abstract fun bindCurrencySettingsRepository(
        impl: CurrencySettingsRepositoryImpl
    ): CurrencySettingsRepository

    @Binds
    @Singleton
    abstract fun bindCurrencyRatesRepository(
        impl: CurrencyRatesRepositoryImpl
    ): CurrencyRatesRepository
    
    // Note: CurrencyConverter and MultiCurrencyRepository are injected directly
    // via their @Inject constructors - no provider methods needed
}
