package com.yourname.expensetracker.domain.currency

interface UserCurrencyProvider {
    suspend fun getHomeCurrency(): String?
}
