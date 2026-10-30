package com.yourname.expensetracker.domain.currency

import java.time.LocalDate

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

    /**
     * Downloads the ECB 90-day history file and stores it all-or-nothing (MEC 1b, INV-1b-1..19).
     *
     * Same privacy capability as [refresh], checked before any connection. Single-flight per
     * process. Every non-cancellation failure is returned as [CurrencyRateHistoryOutcome.Failed];
     * the method never throws one. A caller's `CancellationException` propagates and is never
     * mapped (the VM, not the repository, produces [CurrencyRateHistoryOutcome.Cancelled]).
     */
    suspend fun downloadRecentHistory(homeCurrency: String): CurrencyRateHistoryOutcome
}

/**
 * Closed result of [CurrencyRatesRepository.downloadRecentHistory] (INV-1b-17).
 *
 * The dates in [Stored] are public ECB dates for the UI only; they are never logged.
 */
sealed interface CurrencyRateHistoryOutcome {

    /** All rows of the download were committed in one transaction. */
    data class Stored(
        val days: Int,
        val rows: Int,
        val earliestDate: LocalDate,
        val latestDate: LocalDate
    ) : CurrencyRateHistoryOutcome

    /** The user has the exchange-rate download privacy switch off (default). */
    data object DisabledByPrivacy : CurrencyRateHistoryOutcome

    /** The privacy check failed closed. [reasonCode] is always a bounded privacy-gate reason code. */
    data class PrivacyCheckFailed(val reasonCode: String) : CurrencyRateHistoryOutcome

    /** Another history download is running in this process; no connection was opened. */
    data object AlreadyInProgress : CurrencyRateHistoryOutcome

    /** The attempt failed for exactly one bounded [reason]; the table is unchanged. */
    data class Failed(val reason: RateHistoryFailureReason) : CurrencyRateHistoryOutcome

    /**
     * The user cancelled (INV-1b-21). The repository never returns this variant: the caller's
     * `CancellationException` must propagate. Entry view models use it for their own status.
     */
    data object Cancelled : CurrencyRateHistoryOutcome
}

/** Bounded failure reasons of a history download (INV-1b-17). */
enum class RateHistoryFailureReason {
    OFFLINE,
    TIMEOUT,
    HTTP_ERROR,
    PAYLOAD_TOO_LARGE,
    PAYLOAD_EMPTY,
    PAYLOAD_MALFORMED,
    PAYLOAD_OUT_OF_BOUNDS,
    INVALID_HOME_CURRENCY,
    HOME_QUOTE_UNAVAILABLE,
    STORE_FAILED
}
