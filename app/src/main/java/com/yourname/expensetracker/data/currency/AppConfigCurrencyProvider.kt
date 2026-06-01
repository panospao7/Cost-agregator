package com.yourname.expensetracker.data.currency

import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigCurrencyProvider @Inject constructor(
    private val currencySettingsRepository: CurrencySettingsRepository
) : UserCurrencyProvider {
    // P3-P1-07: Resolve the real user home currency from settings; fall back to
    // AppConfig.DEFAULT_CURRENCY only as a last resort.
    override suspend fun getHomeCurrency(): String? =
        currencySettingsRepository.resolveHomeCurrency().currencyOrNull?.code
            ?: AppConfig.DEFAULT_CURRENCY
}
