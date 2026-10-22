package com.yourname.expensetracker.domain.currency

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Repository for currency-related settings.
 * Backed by DataStore for lightweight key-value storage.
 */
interface CurrencySettingsRepository {
    
    /**
     * Get the current home currency code (e.g., "EUR", "USD").
     */
    fun homeCurrency(): Flow<String>
    
    /**
     * Set the home currency.
     */
    suspend fun setHomeCurrency(currencyCode: String)
    
    /**
     * Get the timestamp of last successful rate update.
     */
    /**
     * CURR-9: Currently reads from DataStore. Should be migrated to Room so that
     * the last-update timestamp lives alongside exchange rates in a single
     * transaction, eliminating the risk of reading stale timestamps after a DB restore.
     */
    fun lastRateUpdate(): Flow<Long>
    
    /**
     * Set the timestamp of last successful rate update.
     */
    /**
     * CURR-9: Currently stored in DataStore. Should be migrated to Room so that
     * the last-update timestamp lives alongside exchange rates in a single
     * transaction, eliminating the risk of a stale DataStore after a DB restore.
     *
     * @see CurrencyRatesRepositoryImpl
     */
    suspend fun setLastRateUpdate(timestamp: Long)
    
    /**
     * Check if rates are considered stale (older than threshold).
     * Default threshold is 24 hours.
     */
    suspend fun areRatesStale(thresholdMs: Long = 24 * 60 * 60 * 1000): Boolean
    
    /**
     * Get the emergency buffer amount in home currency units.
     * Default is 500.0 (in home currency).
     */
    fun emergencyBuffer(): Flow<Double>

    /**
     * Set the emergency buffer amount.
     */
    suspend fun setEmergencyBuffer(amount: Double)

    /**
     * Clear all currency settings.
     */
    suspend fun clear()

    /**
     * Resolve the home currency with explicit first-run vs failure distinction.
     * Implementations should override if they can distinguish "no value set" from "read error".
     */
    suspend fun resolveHomeCurrency(): HomeCurrencyResolution {
        return try {
            val code = withTimeoutOrNull(5_000L) { homeCurrency().first() }
            if (code == null) {
                return HomeCurrencyResolution.Failed("Timeout reading home currency (5s)")
            }
            if (code.isBlank()) {
                HomeCurrencyResolution.FirstRunDefault(
                    com.yourname.expensetracker.domain.core.money.CurrencyCode.EUR
                )
            } else {
                HomeCurrencyResolution.Resolved(
                    com.yourname.expensetracker.domain.core.money.CurrencyCode(code)
                )
            }
        } catch (e: Exception) {
            HomeCurrencyResolution.Failed(e.message ?: "Unknown error reading home currency")
        }
    }

    /**
     * Typed read-time home currency (MC 1a-3a). Read-only; behaviour-neutral.
     *
     * Emits only [ReadHomeCurrencyState.Available] or [ReadHomeCurrencyState.Unavailable]
     * ([ReadHomeFailure.READ_ERROR] / [ReadHomeFailure.INVALID_CODE]); never raw error text.
     * [ReadHomeCurrencyState.Loading] and [ReadHomeFailure.TIMEOUT] are applied by the consumer.
     * CancellationException is always rethrown. After a read error the flow emits
     * Unavailable and completes.
     *
     * Default maps [homeCurrency]; implementations that can distinguish "unset" from
     * a stored value should override.
     */
    fun observeReadHomeCurrency(): Flow<ReadHomeCurrencyState> =
        homeCurrency()
            .map { raw -> ReadHomeCurrencyState.fromStoredValue(raw) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR))
            }
}
