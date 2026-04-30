package com.yourname.expensetracker.ui.screens.investment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.investment.InvestmentPerformance
import com.yourname.expensetracker.domain.investment.InvestmentTracker
import com.yourname.expensetracker.domain.investment.PortfolioSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InvestmentViewModel @Inject constructor(
    private val investmentTracker: InvestmentTracker,
    currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()
    
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
    
    init {
        loadPortfolioData()
    }
    
    private fun loadPortfolioData() {
        viewModelScope.launch {
            try {
                val summary = investmentTracker.getPortfolioSummary()
                _portfolioSummary.value = summary
                
                // Load individual investment performances
                // This would typically come from a Flow in the repository
                _investments.value = emptyList() // Placeholder
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    fun refreshData() {
        loadPortfolioData()
    }
}
