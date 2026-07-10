package com.yourname.expensetracker.ui.screens.negotiation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.negotiation.SmartBillNegotiationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class BillNegotiationViewModel @Inject constructor(
    private val negotiationEngine: SmartBillNegotiationEngine,
    currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()
    
    private val _opportunities = MutableStateFlow<List<SmartBillNegotiationEngine.NegotiationOpportunity>>(emptyList())
    val opportunities: StateFlow<List<SmartBillNegotiationEngine.NegotiationOpportunity>> = _opportunities.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun loadOpportunities() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = negotiationEngine.analyzeNegotiationOpportunities()
                _opportunities.value = result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _opportunities.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun recordNegotiationOutcome(
        subscriptionId: Long,
        outcome: SmartBillNegotiationEngine.NegotiationOutcome,
        newPrice: Double?,
        savings: Double?,
        notes: String?
    ) {
        viewModelScope.launch {
            val result = negotiationEngine.recordNegotiationOutcome(
                subscriptionId = subscriptionId,
                outcome = outcome,
                newPrice = newPrice,
                savings = savings,
                notes = notes
            )
            result.onFailure { error ->
                Timber.w(error, "Failed to record negotiation outcome")
            }
            loadOpportunities()
        }
    }
    
    fun recordOutcome(
        opportunity: SmartBillNegotiationEngine.NegotiationOpportunity,
        outcome: NegotiationOutcome,
        actualSavings: Double?,
        notes: String
    ) {
        viewModelScope.launch {
            val newMonthlyRate = if ((outcome == NegotiationOutcome.SUCCESS || outcome == NegotiationOutcome.PARTIAL) && actualSavings != null) {
                opportunity.currentPrice - actualSavings
            } else null
            
            val engineOutcome = when (outcome) {
                NegotiationOutcome.SUCCESS -> SmartBillNegotiationEngine.NegotiationOutcome.SUCCESS
                NegotiationOutcome.PARTIAL -> SmartBillNegotiationEngine.NegotiationOutcome.PARTIAL
                else -> SmartBillNegotiationEngine.NegotiationOutcome.FAILURE
            }
            
            val result = negotiationEngine.recordNegotiationOutcome(
                subscriptionId = opportunity.subscriptionId,
                outcome = engineOutcome,
                newPrice = newMonthlyRate?.takeIf { it > 0 },
                savings = actualSavings?.takeIf { it > 0 },
                notes = notes.takeIf { it.isNotBlank() }
            )
            
            result.onFailure { error ->
                Timber.w(error, "Failed to record negotiation outcome")
            }
            
            // Refresh the list
            loadOpportunities()
        }
    }
}
