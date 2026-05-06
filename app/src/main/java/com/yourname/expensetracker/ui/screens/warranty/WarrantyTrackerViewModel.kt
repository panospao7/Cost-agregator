package com.yourname.expensetracker.ui.screens.warranty

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

data class WarrantyTrackerState(
    val warranties: List<Warranty> = emptyList(),
    val allWarranties: List<Warranty> = emptyList(),
    val isLoading: Boolean = false,
    val activeCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val totalProtectedValue: Double = 0.0,
    val selectedFilter: WarrantyStatus? = null,
    // F1: Auto-detected warranties needing review
    val needsReviewCount: Int = 0,
    val autoDetectedWarranties: List<Warranty> = emptyList(),
    val showAutoDetectedOnly: Boolean = false,
    val showNeedsReviewOnly: Boolean = false,
    val referenceNowMillis: Long = 0L
)

@HiltViewModel
class WarrantyTrackerViewModel @Inject constructor(
    private val warrantyRepository: WarrantyTrackerRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(WarrantyTrackerState())
    val state: StateFlow<WarrantyTrackerState> = _state.asStateFlow()
    private var warrantiesCollectorJob: Job? = null

    init {
        loadWarranties()
        loadStats()
    }

    private fun loadWarranties() {
        warrantiesCollectorJob?.cancel()
        warrantiesCollectorJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, referenceNowMillis = timeProvider.now()) }
            
            warrantyRepository.getAllWarranties()
                .collect { warranties ->
                    _state.update {
                        val autoDetected = warranties.filter { warranty -> warranty.autoDetected }
                        val needsReview = warranties.filter { warranty -> warranty.needsReview }
                        val updated = it.copy(
                            allWarranties = warranties,
                            autoDetectedWarranties = autoDetected,
                            needsReviewCount = needsReview.size,
                            isLoading = false
                        )
                        updated.withDerivedWarranties()
                    }
                }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            _state.update { it.copy(referenceNowMillis = timeProvider.now()) }
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
        _state.update {
            it.copy(
                selectedFilter = status,
                showAutoDetectedOnly = false,
                showNeedsReviewOnly = false
            ).withDerivedWarranties()
        }
    }
    
    // F1: Filter for auto-detected warranties
    fun filterByAutoDetected() {
        _state.update {
            val enableAutoDetectedOnly = !it.showAutoDetectedOnly
            it.copy(
                showAutoDetectedOnly = enableAutoDetectedOnly,
                showNeedsReviewOnly = false,
                selectedFilter = null
            ).withDerivedWarranties()
        }
    }
    
    // F1: Show warranties needing review
    fun showNeedsReview() {
        _state.update {
            it.copy(
                showNeedsReviewOnly = true,
                showAutoDetectedOnly = false,
                selectedFilter = null
            ).withDerivedWarranties()
        }
    }

    /**
     * WRN-4: Manual warranties no longer create a placeholder receipt.
     * Previously we called [WarrantyTrackerRepository.createManualPlaceholderReceipt]
     * which generated a fake EUR receipt just to satisfy the FK constraint.
     * Since [Warranty.receiptId] is now nullable, we skip the placeholder entirely
     * and store a null receiptId, avoiding fake EUR records in the receipt table.
     */
    fun addManualWarranty(
        productName: String,
        merchantName: String,
        purchaseDate: Long,
        warrantyDurationMonths: Int,
        supportPhone: String?
    ) {
        viewModelScope.launch {
            val purchaseStart = TimePeriodUtils.getStartOfDay(purchaseDate)
            val endDateMidnight = TimePeriodUtils.addMonths(purchaseStart, warrantyDurationMonths)
            // Use half-open end-of-day semantics so the warranty survives
            // through its entire expiration day (matches WarrantyTrackerRepository).
            val endDate = TimePeriodUtils.getEndOfDay(endDateMidnight)
            // WRN-4: receiptId is null — no placeholder receipt is created.
            val manualWarranty = Warranty(
                receiptId = null,
                expenseId = null,
                productName = productName,
                merchantName = merchantName,
                purchaseDate = purchaseStart,
                warrantyDurationMonths = warrantyDurationMonths,
                warrantyEndDate = endDate,
                supportPhone = supportPhone?.takeIf { it.isNotBlank() },
                extractionSource = "manual"
            )
            warrantyRepository.addWarranty(manualWarranty)
            loadStats()
        }
    }
    
    // F1: Confirm a low-confidence auto-detected warranty
    fun confirmWarranty(warranty: Warranty) {
        viewModelScope.launch {
            val updated = warranty.copy(
                status = WarrantyStatus.ACTIVE,
                needsReview = false,
                updatedAt = timeProvider.now()
            )
            warrantyRepository.updateWarranty(updated)
            loadStats()
        }
    }
    
    // F1: Reject/delete an auto-detected warranty that was incorrect
    fun rejectAutoDetectedWarranty(warranty: Warranty) {
        viewModelScope.launch {
            // I5: Also delete the associated return window to keep data consistent
            if (warranty.receiptId != null) {
                val returnWindow = warrantyRepository.getReturnWindowByReceiptId(warranty.receiptId)
                if (returnWindow != null) {
                    warrantyRepository.deleteReturnWindow(returnWindow)
                }
            }
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

    private fun WarrantyTrackerState.withDerivedWarranties(): WarrantyTrackerState {
        val derived = when {
            showNeedsReviewOnly -> allWarranties.filter { it.needsReview }
            showAutoDetectedOnly -> allWarranties.filter { it.autoDetected }
            selectedFilter != null -> allWarranties.filter { it.status == selectedFilter }
            else -> allWarranties
        }
        return copy(warranties = derived)
    }
}
