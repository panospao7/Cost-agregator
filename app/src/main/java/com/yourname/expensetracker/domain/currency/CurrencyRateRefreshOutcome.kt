package com.yourname.expensetracker.domain.currency

/**
 * Typed result of [CurrencyRatesRepository.refresh].
 *
 * Privacy outcomes are returned before any network call is made.
 */
sealed interface CurrencyRateRefreshOutcome {

    /** Rates were downloaded and persisted; [storedPairs] may be 0 if the provider returned nothing usable. */
    data class Refreshed(val storedPairs: Int) : CurrencyRateRefreshOutcome

    /** The user has the exchange-rate download privacy switch off (default). */
    data object DisabledByPrivacy : CurrencyRateRefreshOutcome

    /** The privacy check failed closed. [reasonCode] is always a bounded privacy-gate reason code. */
    data class PrivacyCheckFailed(val reasonCode: String) : CurrencyRateRefreshOutcome
}
