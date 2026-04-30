package com.yourname.expensetracker.domain.currency

import kotlinx.coroutines.flow.Flow

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
    fun lastRateUpdate(): Flow<Long>
    
    /**
     * Set the timestamp of last successful rate update.
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
}
