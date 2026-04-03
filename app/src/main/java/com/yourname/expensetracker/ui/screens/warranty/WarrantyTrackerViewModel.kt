package com.yourname.expensetracker.ui.screens.warranty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WarrantyTrackerState(
    val warranties: List<Warranty> = emptyList(),
    val isLoading: Boolean = false,
    val activeCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val totalProtectedValue: Double = 0.0,
    val selectedFilter: WarrantyStatus? = null,
    // F1: Auto-detected warranties needing review
    val needsReviewCount: Int = 0,
    val autoDetectedWarranties: List<Warranty> = emptyList()
)

@HiltViewModel
class WarrantyTrackerViewModel @Inject constructor(
    private val warrantyRepository: WarrantyTrackerRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WarrantyTrackerState())
    val state: StateFlow<WarrantyTrackerState> = _state.asStateFlow()

    init {
        loadWarranties()
        loadStats()
    }

    private fun loadWarranties() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            warrantyRepository.getAllWarranties()
                .collect { warranties ->
                    val autoDetected = warranties.filter { it.autoDetected }
                    val needsReview = warranties.filter { it.needsReview }
                    
                    _state.update { 
                        it.copy(
                            warranties = warranties,
                            autoDetectedWarranties = autoDetected,
                            needsReviewCount = needsReview.size,
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            val activeCount = warrantyRepository.getActiveWarrantyCount()
            val expiringSoon = warrantyRepository.getWarrantiesExpiringSoon(30).size
            val protectedValue = warrantyRepository.getTotalProtectedValue()
            
            _state.update {
                it.copy(
                    activeCount = activeCount,
                    expiringSoonCount = expiringSoon,
                    totalProtectedValue = protectedValue
                )
            }
        }
    }

    fun filterByStatus(status: WarrantyStatus?) {
        _state.update { it.copy(selectedFilter = status) }
    }
    
    // F1: Filter for auto-detected warranties
    fun filterByAutoDetected() {
        _state.update { 
            it.copy(
                warranties = it.autoDetectedWarranties,
                selectedFilter = null
            ) 
        }
    }
    
    // F1: Show warranties needing review
    fun showNeedsReview() {
        viewModelScope.launch {
            val needsReview = _state.value.warranties.filter { it.needsReview }
            _state.update { 
                it.copy(
                    warranties = needsReview,
                    selectedFilter = null
                ) 
            }
        }
    }
    
    // F1: Confirm a low-confidence auto-detected warranty
    fun confirmWarranty(warranty: Warranty) {
        viewModelScope.launch {
            val updated = warranty.copy(
                needsReview = false,
                updatedAt = System.currentTimeMillis()
            )
            warrantyRepository.updateWarranty(updated)
            loadStats()
        }
    }
    
    // F1: Reject/delete an auto-detected warranty that was incorrect
    fun rejectAutoDetectedWarranty(warranty: Warranty) {
        viewModelScope.launch {
            warrantyRepository.deleteWarranty(warranty)
            loadStats()
        }
    }

    fun markAsClaimed(warrantyId: Long) {
        viewModelScope.launch {
            warrantyRepository.markWarrantyAsClaimed(warrantyId)
            loadStats()
        }
    }

    fun deleteWarranty(warranty: Warranty) {
        viewModelScope.launch {
            warrantyRepository.deleteWarranty(warranty)
            loadStats()
        }
    }

    fun refresh() {
        loadWarranties()
        loadStats()
    }
}
