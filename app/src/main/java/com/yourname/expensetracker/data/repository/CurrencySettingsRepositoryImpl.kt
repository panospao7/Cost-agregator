package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.currencyDataStore: DataStore<Preferences> by preferencesDataStore(name = "currency_settings")

@Singleton
class CurrencySettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) : CurrencySettingsRepository {
    
    companion object {
        private val HOME_CURRENCY_KEY = stringPreferencesKey("home_currency")
        private val LAST_RATE_UPDATE_KEY = longPreferencesKey("last_rate_update")
        private val EMERGENCY_BUFFER_KEY = doublePreferencesKey("emergency_buffer")

        private const val DEFAULT_CURRENCY = "EUR"
        // Note: 500.0 is in home currency units
        private const val DEFAULT_EMERGENCY_BUFFER_FALLBACK = 500.0
    }
    
    override fun homeCurrency(): Flow<String> = 
        context.currencyDataStore.data.map { prefs ->
            prefs[HOME_CURRENCY_KEY] ?: DEFAULT_CURRENCY
        }
    
    /**
     * CURR-6: Changing the home currency updates the preference but does NOT
     * re-normalize existing expense amounts, budgets, or aggregated analytics.
     * Callers MUST trigger a full re-normalization pass after invoking this to
     * avoid stale/mismatched amounts in reports and dashboard widgets.
     *
     * Re-normalization should iterate all expenses, budgets, and forecast data
     * and convert them to the new home currency using the latest exchange rates.
     */
    override suspend fun setHomeCurrency(currencyCode: String) {
        context.currencyDataStore.edit { prefs ->
            prefs[HOME_CURRENCY_KEY] = currencyCode
        }
    }
    
    override fun lastRateUpdate(): Flow<Long> =
        context.currencyDataStore.data.map { prefs ->
            prefs[LAST_RATE_UPDATE_KEY] ?: 0L
        }
    
    override suspend fun setLastRateUpdate(timestamp: Long) {
        context.currencyDataStore.edit { prefs ->
            prefs[LAST_RATE_UPDATE_KEY] = timestamp
        }
    }
    
    override suspend fun areRatesStale(thresholdMs: Long): Boolean {
        val lastUpdate = lastRateUpdate().first()
        if (lastUpdate == 0L) return true // Never updated
        
        val now = timeProvider.now()
        return (now - lastUpdate) > thresholdMs
    }
    
    override fun emergencyBuffer(): Flow<Double> =
        context.currencyDataStore.data.map { prefs ->
            prefs[EMERGENCY_BUFFER_KEY] ?: DEFAULT_EMERGENCY_BUFFER_FALLBACK
        }

    override suspend fun setEmergencyBuffer(amount: Double) {
        context.currencyDataStore.edit { prefs ->
            prefs[EMERGENCY_BUFFER_KEY] = amount
        }
    }

    override suspend fun clear() {
        context.currencyDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
