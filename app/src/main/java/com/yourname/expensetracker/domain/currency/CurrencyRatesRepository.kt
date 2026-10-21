package com.yourname.expensetracker.domain.currency

/**
 * Repository responsible for refreshing and persisting currency exchange rates.
 */
interface CurrencyRatesRepository {

    /**
     * Refreshes exchange rates for the given home currency.
     *
     * Gated by the exchange-rate download privacy capability (default OFF); a blocked
     * refresh returns before any network call.
     *
     * @return [CurrencyRateRefreshOutcome.Refreshed] with the number of stored pairs, or a
     * privacy outcome when the download is not permitted.
     */
    suspend fun refresh(homeCurrency: String): CurrencyRateRefreshOutcome
}
