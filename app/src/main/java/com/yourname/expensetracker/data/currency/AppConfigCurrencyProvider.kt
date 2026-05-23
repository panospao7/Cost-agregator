package com.yourname.expensetracker.data.currency

import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.currency.UserCurrencyProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigCurrencyProvider @Inject constructor() : UserCurrencyProvider {
    override suspend fun getHomeCurrency(): String? = AppConfig.DEFAULT_CURRENCY
}
