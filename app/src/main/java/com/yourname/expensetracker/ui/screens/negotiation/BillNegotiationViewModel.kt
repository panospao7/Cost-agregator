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
    
    fun recordOutcome(
        opportunity: SmartBillNegotiationEngine.NegotiationOpportunity,
        outcome: NegotiationOutcome,
        actualSavings: Double?,
        notes: String
    ) {
        viewModelScope.launch {
            val newMonthlyRate = if (outcome == NegotiationOutcome.SUCCESS && actualSavings != null) {
                opportunity.currentPrice - actualSavings
            } else null
            
            val outcomeType = when (outcome) {
                NegotiationOutcome.SUCCESS -> SmartBillNegotiationEngine.OutcomeType.SUCCESSFUL_NEGOTIATION
                NegotiationOutcome.PARTIAL -> SmartBillNegotiationEngine.OutcomeType.PARTIAL_SUCCESS
                NegotiationOutcome.FAILED -> SmartBillNegotiationEngine.OutcomeType.NO_CHANGE
                NegotiationOutcome.CANCELLED -> SmartBillNegotiationEngine.OutcomeType.CANCELLED
                NegotiationOutcome.PENDING -> SmartBillNegotiationEngine.OutcomeType.NO_CHANGE
            }
            
            val negotiationOutcome = SmartBillNegotiationEngine.NegotiationOutcome(
                success = outcome == NegotiationOutcome.SUCCESS || outcome == NegotiationOutcome.PARTIAL,
                newMonthlyRate = newMonthlyRate?.takeIf { it > 0 },
                outcomeType = outcomeType,
                notes = notes.takeIf { it.isNotBlank() }
            )
            
            negotiationEngine.recordNegotiationOutcome(
                subscriptionId = opportunity.subscriptionId,
                outcome = negotiationOutcome,
                newPrice = newMonthlyRate?.takeIf { it > 0 },
                savings = actualSavings?.takeIf { it > 0 },
                notes = notes.takeIf { it.isNotBlank() }
            )
            // Refresh the list
            loadOpportunities()
        }
    }
}
