package com.yourname.expensetracker.ui.screens.currency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.CurrencyDataRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Currency
import javax.inject.Inject

/**
 * UI state for currency management screen.
 */
data class CurrencyManagementUiState(
    val homeCurrency: String = "EUR",
    val supportedCurrencies: List<CurrencyInfo> = emptyList(),
    val exchangeRates: List<ExchangeRateInfo> = emptyList(),
    val lastUpdated: Long? = null,
    val isRatesStale: Boolean = false,
    val isOffline: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val conversionResult: ConversionResult? = null
)

data class CurrencyInfo(
    val code: String,
    val name: String,
    val symbol: String,
    val flag: String
)

data class ExchangeRateInfo(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val lastUpdated: Long
)

@HiltViewModel
class CurrencyManagementViewModel @Inject constructor(
    private val currencyDataRepository: CurrencyDataRepository,
    private val currencyConverter: CurrencyConverter,
    private val currencyRatesRepository: CurrencyRatesRepository,
    private val settingsRepository: CurrencySettingsRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CurrencyManagementUiState())
    val uiState: StateFlow<CurrencyManagementUiState> = _uiState.asStateFlow()
    
    // Top 20 most used currencies
    private val priorityCurrencies = listOf(
        "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY", "SEK", "NZD",
        "MXN", "SGD", "HKD", "NOK", "KRW", "TRY", "RUB", "INR", "BRL", "ZAR"
    )
    
    init {
        viewModelScope.launch {
            // Load home currency from preferences
            settingsRepository.homeCurrency().collect { homeCurrency ->
                _uiState.value = _uiState.value.copy(homeCurrency = homeCurrency)
                loadCurrencyData()
            }
        }
    }
    
    private fun loadCurrencyData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                // Load supported currencies
                val currencies = priorityCurrencies.mapNotNull { code ->
                    try {
                        val currency = Currency.getInstance(code)
                        CurrencyInfo(
                            code = code,
                            name = currency.displayName,
                            symbol = currency.symbol,
                            flag = getCurrencyFlag(code)
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                
                // Load exchange rates
                val homeCurrency = _uiState.value.homeCurrency
                val rates = currencyDataRepository.getAllRatesForBase(homeCurrency).first()
                    .map { rate ->
                        ExchangeRateInfo(
                            fromCurrency = rate.fromCurrency,
                            toCurrency = rate.toCurrency,
                            rate = rate.rate,
                            lastUpdated = rate.lastUpdated
                        )
                    }
                
                // Get the most recent update time
                val lastUpdated = rates.maxByOrNull { it.lastUpdated }?.lastUpdated
                    ?: settingsRepository.lastRateUpdate().first()
                
                // Check if rates are stale (older than 24 hours)
                val isRatesStale = settingsRepository.areRatesStale()
                
                // Check if we're offline (no rates available and stale)
                val isOffline = rates.isEmpty() || (isRatesStale && rates.isNotEmpty())
                
                _uiState.value = _uiState.value.copy(
                    supportedCurrencies = currencies,
                    exchangeRates = rates,
                    lastUpdated = lastUpdated,
                    isRatesStale = isRatesStale,
                    isOffline = isOffline,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load currency data: ${e.message}",
                    isOffline = true
                )
            }
        }
    }
    
    /**
     * Set the home currency.
     */
    fun setHomeCurrency(currencyCode: String) {
        viewModelScope.launch {
            // Persist to settings
            settingsRepository.setHomeCurrency(currencyCode)
            
            // State will be updated by the flow collector in init
            _uiState.value = _uiState.value.copy(
                isLoading = true
            )
        }
    }
    
    /**
     * Convert an amount between currencies.
     */
    fun convert(amount: Double, fromCurrency: String, toCurrency: String) {
        viewModelScope.launch {
            try {
                val result = currencyConverter.convert(
                    amount = amount,
                    fromCurrency = fromCurrency,
                    toCurrency = toCurrency
                )
                
                if (result != null) {
                    _uiState.value = _uiState.value.copy(
                        conversionResult = result
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "No exchange rate available for $fromCurrency to $toCurrency"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Conversion failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Refresh exchange rates from server.
     */
    fun refreshRates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val refreshedCount = currencyRatesRepository.refresh(_uiState.value.homeCurrency)
                if (refreshedCount <= 0) {
                    throw IllegalStateException("No rates returned from provider")
                }
                loadCurrencyData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to refresh rates: ${e.message}",
                    isOffline = true
                )
            }
        }
    }
    
    /**
     * Clear any error state.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Clear conversion result.
     */
    fun clearConversion() {
        _uiState.value = _uiState.value.copy(conversionResult = null)
    }
    
    private fun getCurrencyFlag(code: String): String {
        return when (code) {
            "USD" -> "🇺🇸"
            "EUR" -> "🇪🇺"
            "GBP" -> "🇬🇧"
            "JPY" -> "🇯🇵"
            "AUD" -> "🇦🇺"
            "CAD" -> "🇨🇦"
            "CHF" -> "🇨🇭"
            "CNY" -> "🇨🇳"
            "SEK" -> "🇸🇪"
            "NZD" -> "🇳🇿"
            "MXN" -> "🇲🇽"
            "SGD" -> "🇸🇬"
            "HKD" -> "🇭🇰"
            "NOK" -> "🇳🇴"
            "KRW" -> "🇰🇷"
            "TRY" -> "🇹🇷"
            "RUB" -> "🇷🇺"
            "INR" -> "🇮🇳"
            "BRL" -> "🇧🇷"
            "ZAR" -> "🇿🇦"
            else -> "💱"
        }
    }
}
