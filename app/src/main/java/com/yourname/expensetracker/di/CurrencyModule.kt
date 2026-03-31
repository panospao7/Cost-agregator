package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.CurrencySettingsRepositoryImpl
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
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
    
    // Note: CurrencyConverter and MultiCurrencyRepository are injected directly
    // via their @Inject constructors - no provider methods needed
}
