package com.yourname.expensetracker.ui.screens.currency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Currency
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for currency management screen.
 */
data class CurrencyManagementUiState(
    val homeCurrency: String = "EUR",
    val supportedCurrencies: List<CurrencyInfo> = emptyList(),
    val exchangeRates: List<ExchangeRateInfo> = emptyList(),
    val lastUpdated: Long? = null,
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
    private val exchangeRateDao: ExchangeRateDao,
    private val currencyConverter: CurrencyConverter
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CurrencyManagementUiState())
    val uiState: StateFlow<CurrencyManagementUiState> = _uiState.asStateFlow()
    
    // Top 20 most used currencies
    private val priorityCurrencies = listOf(
        "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNY", "SEK", "NZD",
        "MXN", "SGD", "HKD", "NOK", "KRW", "TRY", "RUB", "INR", "BRL", "ZAR"
    )
    
    init {
        loadCurrencyData()
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
                val rates = exchangeRateDao.getAllRatesForBase(homeCurrency).first()
                    .map { rate ->
                        ExchangeRateInfo(
                            fromCurrency = rate.fromCurrency,
                            toCurrency = rate.toCurrency,
                            rate = rate.rate,
                            lastUpdated = rate.lastUpdated
                        )
                    }
                
                val lastUpdated = rates.maxByOrNull { it.lastUpdated }?.lastUpdated
                
                _uiState.value = _uiState.value.copy(
                    supportedCurrencies = currencies,
                    exchangeRates = rates,
                    lastUpdated = lastUpdated,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load currency data: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Set the home currency.
     */
    fun setHomeCurrency(currencyCode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                homeCurrency = currencyCode,
                isLoading = true
            )
            
            // Reload exchange rates for new home currency
            loadCurrencyData()
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
                // In a real implementation, this would fetch from an API
                // For now, just reload from database
                loadCurrencyData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to refresh rates: ${e.message}"
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