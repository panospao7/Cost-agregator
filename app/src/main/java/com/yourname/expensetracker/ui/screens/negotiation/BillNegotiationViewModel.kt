package com.yourname.expensetracker.ui.screens.negotiation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.negotiation.SmartBillNegotiationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillNegotiationViewModel @Inject constructor(
    private val negotiationEngine: SmartBillNegotiationEngine
) : ViewModel() {
    
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
            } catch (e: Exception) {
                e.printStackTrace()
                _opportunities.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun recordNegotiationOutcome(
        subscriptionId: Long,
        outcome: SmartBillNegotiationEngine.NegotiationOutcome
    ) {
        viewModelScope.launch {
            negotiationEngine.recordNegotiationOutcome(
                subscriptionId = subscriptionId,
                outcome = outcome,
                newPrice = outcome.newMonthlyRate,
                savings = outcome.newMonthlyRate?.let { newRate ->
                    _opportunities.value.find { it.subscriptionId == subscriptionId }?.let { opp ->
                        opp.currentPrice - newRate
                    }
                },
                notes = outcome.notes
            )
            // Refresh the list
            loadOpportunities()
        }
    }
}
