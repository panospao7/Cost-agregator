package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.currencyDataStore: DataStore<Preferences> by preferencesDataStore(name = "currency_settings")

@Singleton
class CurrencySettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CurrencySettingsRepository {
    
    companion object {
        private val HOME_CURRENCY_KEY = stringPreferencesKey("home_currency")
        private val LAST_RATE_UPDATE_KEY = longPreferencesKey("last_rate_update")
        
        private const val DEFAULT_CURRENCY = "EUR"
    }
    
    override fun homeCurrency(): Flow<String> = 
        context.currencyDataStore.data.map { prefs ->
            prefs[HOME_CURRENCY_KEY] ?: DEFAULT_CURRENCY
        }
    
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
        
        val now = System.currentTimeMillis()
        return (now - lastUpdate) > thresholdMs
    }
    
    override suspend fun clear() {
        context.currencyDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
