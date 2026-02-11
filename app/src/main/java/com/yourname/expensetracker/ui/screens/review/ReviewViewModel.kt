package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.NotificationRepository
// ...
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: com.yourname.expensetracker.data.repository.ReceiptRepository
) : ViewModel() {
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    val batchProgress = _batchProgress.asStateFlow()

    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing = _isBatchProcessing.asStateFlow()

    val pendingReviews: StateFlow<List<PendingReviewWithReceipt>> = repository
        .getPendingReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun approveReview(reviewId: Long) {
        viewModelScope.launch {
            try {
                repository.approveReview(reviewId)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve: ${e.message}"
            }
        }
    }

    fun rejectReview(reviewId: Long) {
        viewModelScope.launch {
            repository.rejectReview(reviewId)
        }
    }

    fun approveReviewWithEdits(
        reviewId: Long,
        finalAmount: Double?,
        finalMerchant: String?,
        finalCategoryId: Long?
    ) {
        viewModelScope.launch {
            try {
                repository.approveReview(
                    reviewId = reviewId,
                    finalAmount = finalAmount,
                    finalMerchant = finalMerchant,
                    finalCategoryId = finalCategoryId
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve edits: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun approveAll() {
        viewModelScope.launch {
            try {
                repository.approveAllReview()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve all: ${e.message}"
            }
        }
    }

    fun processBatch(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true
                _batchProgress.value = Pair(0, uris.size)
                
                val result = receiptRepository.processBatch(uris) { current, total ->
                    _batchProgress.value = Pair(current, total)
                }
                
                if (result.failureCount > 0) {
                    val firstError = result.errors.firstOrNull()?.let { 
                        if (it.length > 60) it.take(57) + "..." else it 
                    }
                    _errorMessage.value = "Processed ${result.successCount} ok. ${result.failureCount} failed: $firstError"
                } else {
                    _errorMessage.value = "Successfully processed all ${result.successCount} receipts!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Batch failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    fun processStatement(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true // Reuse batch loading state
                _batchProgress.value = Pair(0, 1)
                
                val result = receiptRepository.processStatement(uri)
                
                if (result.failureCount > 0) {
                    _errorMessage.value = "Failed to parse screenshot: ${result.errors.firstOrNull()}"
                } else {
                    _errorMessage.value = "Imported ${result.successCount} transactions from screenshot!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    suspend fun getDebugExportData(): String {
        return receiptRepository.exportParserDebugData()
    }

    fun clearScannedData() {
        viewModelScope.launch {
            receiptRepository.clearAllScannedReceipts()
            _errorMessage.value = "All scanned debug data cleared."
        }
    }
}
