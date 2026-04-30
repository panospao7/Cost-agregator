package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ParserModule {

    @Provides
    @Singleton
    fun provideGreekBankParser(
        currencyNormalizer: CurrencyNormalizer,
        merchantCleaner: MerchantCleaner,
        currencySettingsRepository: CurrencySettingsRepository
    ): GreekBankParser {
        val homeCurrency = runBlocking {
            currencySettingsRepository.homeCurrency().first()
        }
        return GreekBankParser(
            currencyNormalizer = currencyNormalizer,
            merchantCleaner = merchantCleaner,
            homeCurrency = homeCurrency
        )
    }
}
