package com.yourname.expensetracker.domain.currency

/**
 * Repository responsible for refreshing and persisting currency exchange rates.
 */
interface CurrencyRatesRepository {

    /**
     * Refreshes exchange rates for the given home currency.
     *
     * @return number of stored exchange-rate pairs.
     */
    suspend fun refresh(homeCurrency: String): Int
}
