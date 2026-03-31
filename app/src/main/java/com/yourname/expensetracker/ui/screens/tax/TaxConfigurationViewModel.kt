package com.yourname.expensetracker.ui.screens.tax

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.tax.GreeceTaxConfiguration
import com.yourname.expensetracker.domain.tax.TaxBracket
import com.yourname.expensetracker.domain.tax.TaxConfiguration
import com.yourname.expensetracker.domain.tax.TaxConfigurationFactory
import com.yourname.expensetracker.domain.tax.TaxEstimator
import com.yourname.expensetracker.domain.tax.TaxEstimate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for tax configuration screen.
 */
data class TaxConfigurationUiState(
    val selectedCountry: String = "GR",
    val supportedCountries: List<CountryInfo> = listOf(
        CountryInfo("GR", "🇬🇷", "Greece", "EUR"),
        CountryInfo("US", "🇺🇸", "United States", "USD")
    ),
    val taxBrackets: List<TaxBracket> = emptyList(),
    val vatRate: Double = 0.24,
    val currency: String = "EUR",
    val sampleEstimate: TaxEstimate? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class CountryInfo(
    val code: String,
    val flag: String,
    val name: String,
    val currency: String
)

@HiltViewModel
class TaxConfigurationViewModel @Inject constructor(
    private val taxEstimator: TaxEstimator
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(TaxConfigurationUiState())
    val uiState: StateFlow<TaxConfigurationUiState> = _uiState.asStateFlow()
    
    init {
        loadTaxConfiguration()
    }
    
    fun loadTaxConfiguration() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val config = TaxConfigurationFactory.getConfiguration(_uiState.value.selectedCountry)
                
                _uiState.value = _uiState.value.copy(
                    taxBrackets = config.getTaxBrackets(),
                    vatRate = config.getVatRate(),
                    currency = config.getCurrency(),
                    isLoading = false,
                    error = null
                )
                
                // Generate sample estimate
                calculateSampleEstimate(50000.0)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load tax configuration: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Select a different country tax configuration.
     */
    fun selectCountry(countryCode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                selectedCountry = countryCode,
                isLoading = true
            )
            loadTaxConfiguration()
        }
    }
    
    /**
     * Calculate sample tax estimate using the currently selected country configuration.
     */
    fun calculateSampleEstimate(annualIncome: Double) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val oneMonthAgo = now - (30L * 24 * 60 * 60 * 1000)
                
                // Use the same config as the UI for consistency
                val config = TaxConfigurationFactory.getConfiguration(_uiState.value.selectedCountry)
                
                val estimate = taxEstimator.estimateTaxes(
                    startDate = oneMonthAgo,
                    endDate = now,
                    estimatedAnnualIncome = annualIncome,
                    taxConfig = config
                )
                
                _uiState.value = _uiState.value.copy(
                    sampleEstimate = estimate
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to calculate estimate: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Clear error.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}