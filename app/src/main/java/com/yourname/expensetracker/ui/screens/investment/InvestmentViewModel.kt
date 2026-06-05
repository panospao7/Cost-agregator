package com.yourname.expensetracker.ui.screens.investment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.investment.InvestmentPerformance
import com.yourname.expensetracker.domain.investment.InvestmentTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InvestmentViewModel @Inject constructor(
    private val investmentTracker: InvestmentTracker,
    private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private val _portfolioSummaryAggregate = MutableStateFlow<com.yourname.expensetracker.domain.investment.PortfolioSummaryAggregate?>(null)
    val portfolioSummaryAggregate: StateFlow<com.yourname.expensetracker.domain.investment.PortfolioSummaryAggregate?> = _portfolioSummaryAggregate.asStateFlow()
    
    private val _investments = MutableStateFlow<List<InvestmentPerformance>>(emptyList())
    val investments: StateFlow<List<InvestmentPerformance>> = _investments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** S12-022: null until home currency loads — never defaults to "EUR" */
    private val _homeCurrency = MutableStateFlow<String?>(null)
    val homeCurrency: StateFlow<String?> = _homeCurrency.asStateFlow()

    private val _portfolioDataQuality = MutableStateFlow<com.yourname.expensetracker.domain.investment.InvestmentDataQuality?>(null)
    val portfolioDataQuality: StateFlow<com.yourname.expensetracker.domain.investment.InvestmentDataQuality?> = _portfolioDataQuality.asStateFlow()

    init {
        viewModelScope.launch {
            // S12-022: Collect home currency reactively — no EUR fallback
            currencySettingsRepository.homeCurrency().collect { hc ->
                _homeCurrency.value = hc
            }
        }
        loadPortfolioData()
    }
    
    private fun loadPortfolioData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // S12-020: Use aggregate path via PortfolioSummaryAggregate
                val holdings = investmentTracker.getAllActiveInvestments()
                val portfolioAggregate = investmentTracker.getPortfolioSummaryAggregate(holdings)
                _portfolioSummaryAggregate.value = portfolioAggregate
                _portfolioDataQuality.value = portfolioAggregate.dataQuality

                // S12-021: Load individual investment performances
                val performances = holdings.mapNotNull { investment ->
                    investmentTracker.getInvestmentPerformance(investment.id)
                }
                _investments.value = performances
            } catch (e: Exception) {
                _error.value = "Failed to load portfolio: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun refreshData() {
        loadPortfolioData()
    }

    fun clearError() {
        _error.value = null
    }
}
