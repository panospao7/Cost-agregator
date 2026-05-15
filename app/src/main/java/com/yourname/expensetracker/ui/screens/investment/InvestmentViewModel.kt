package com.yourname.expensetracker.ui.screens.investment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.investment.InvestmentPerformance
import com.yourname.expensetracker.domain.investment.InvestmentTracker
import com.yourname.expensetracker.domain.investment.PortfolioSummary
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

    private val _portfolioSummary = MutableStateFlow(
        PortfolioSummary(
            totalValue = 0.0,
            totalInvested = 0.0,
            totalGainLoss = 0.0,
            totalGainLossPercent = 0.0,
            investmentCount = 0,
            byType = emptyMap()
        )
    )
    val portfolioSummary: StateFlow<PortfolioSummary> = _portfolioSummary.asStateFlow()
    
    private val _investments = MutableStateFlow<List<InvestmentPerformance>>(emptyList())
    val investments: StateFlow<List<InvestmentPerformance>> = _investments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** S12-022: null until home currency loads — never defaults to "EUR" */
    private val _homeCurrency = MutableStateFlow<String?>(null)
    val homeCurrency: StateFlow<String?> = _homeCurrency.asStateFlow()
    
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
                // S12-020: Use non-deprecated aggregate path
                val holdings = investmentTracker.getAllActiveInvestments()
                val (summary, _, _) = investmentTracker.getPortfolioSummaryAggregate(holdings)
                _portfolioSummary.value = summary

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
